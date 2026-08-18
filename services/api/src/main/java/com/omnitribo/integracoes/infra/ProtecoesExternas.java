package com.omnitribo.integracoes.infra;

import com.omnitribo.integracoes.api.ServicoExternoIndisponivelException;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Fábrica das proteções, e o único lugar onde a calibração é lida.
 *
 * <p>Um disjuntor e um bulkhead POR PROVEDOR — o CEP fora do ar não pode abrir o circuito do clima
 * nem consumir as permissões dele. A política de retry, ao contrário, é COMPARTILHADA: ela não
 * depende do formato do tráfego de cada provedor, só do que é transitório em HTTP, e duplicá-la por
 * provedor seria calibração que ninguém faria.
 *
 * <p><b>A política usa lista BRANCA, e os três mecanismos juntos.</b> {@code includes} admite
 * timeout e qualquer resposta de erro; {@code excludes} tira os 4xx de status padrão e a recusa do
 * nosso próprio bulkhead; e o {@code predicate} fecha o buraco que os dois deixam — um status 4xx
 * NÃO padronizado (499, que alguns proxies devolvem) não vira {@code HttpClientErrorException},
 * vira {@code UnknownHttpStatusCodeException}, que passaria pelo {@code includes}. Sem o predicado,
 * "nunca repetir em 4xx" seria falso justamente na borda.
 */
@Component
class ProtecoesExternas {

  private final int falhasParaAbrir;
  private final Duration esperaAberto;
  private final RetryTemplate repeticao;
  private final Clock relogio;

  ProtecoesExternas(
      @Value("${app.integracoes.disjuntor.falhas-para-abrir:5}") int falhasParaAbrir,
      @Value("${app.integracoes.disjuntor.espera-aberto:PT30S}") Duration esperaAberto,
      @Value("${app.integracoes.retry.repeticoes:1}") int repeticoes,
      @Value("${app.integracoes.retry.espera:PT0.2S}") Duration espera,
      @Value("${app.integracoes.retry.jitter:PT0.05S}") Duration jitter,
      Clock relogio) {
    this.falhasParaAbrir = falhasParaAbrir;
    this.esperaAberto = esperaAberto;
    this.relogio = relogio;
    this.repeticao = new RetryTemplate(politica(repeticoes, espera, jitter));
  }

  ProtecaoDeChamadasExternas para(String provedor, int chamadasSimultaneas) {
    return new ProtecaoDeChamadasExternas(
        new DisjuntorDeChamadasExternas(provedor, falhasParaAbrir, esperaAberto, relogio),
        new LimiteDeChamadasExternas(provedor, chamadasSimultaneas),
        repeticao);
  }

  /**
   * {@code maxRetries} conta REPETIÇÕES, não tentativas: 1 aqui significa 2 idas ao provedor. Mais
   * que isso, com timeout de 2 s cada, faria a espera do usuário passar de 6 s antes do 503.
   *
   * <p>A espera precisa ser declarada: o default do Framework é 1 s, e herdá-lo dobraria o pior
   * caso sem que ninguém tivesse decidido isso.
   */
  private static RetryPolicy politica(int repeticoes, Duration espera, Duration jitter) {
    return RetryPolicy.builder()
        .maxRetries(repeticoes)
        .delay(espera)
        // Dessincroniza rajadas: sem jitter, N threads que falharam juntas repetem juntas e batem
        // no
        // provedor doente exatamente no mesmo instante.
        .jitter(jitter)
        .includes(ResourceAccessException.class, RestClientResponseException.class)
        .excludes(HttpClientErrorException.class, ServicoExternoIndisponivelException.class)
        .predicate(ClassificacaoDeFalhaExterna::eTransitoria)
        .build();
  }
}
