package com.omnitribo.integracoes.dominio;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.omnitribo.integracoes.api.ClimaResponse;
import com.omnitribo.integracoes.api.ConsultaClima;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Clima com cache, arredondamento de coordenada e teto de entradas.
 *
 * <p><b>A coordenada é arredondada para 2 casas antes de virar chave</b>, o que agrega ~1,1 km. Sem
 * isso o cache seria inútil por construção: cada micro-tremida do GPS produziria uma chave nova e
 * toda leitura viraria uma chamada externa. E é meteorologicamente honesto — a previsão do provedor
 * já tem resolução de quilômetros, então duas coordenadas a 300 m uma da outra recebem a mesma
 * resposta de qualquer forma.
 *
 * <p>{@code maximumSize} existe pela mesma razão do cache de proximidade: a chave é escolhida pelo
 * cliente, e sem teto um cliente hostil varreria o globo enchendo a heap.
 *
 * <p>Caffeine direto, sem {@code @Cacheable}, para manter o padrão já adotado em {@code
 * CacheMissoesProximas} — e porque um cache observável num teste vale mais que a indireção.
 */
@Service
public class ClimaService implements ConsultaClima {

  private static final Logger log = LoggerFactory.getLogger(ClimaService.class);

  private static final int MAXIMO_ENTRADAS = 5_000;
  private static final int CASAS_DA_CHAVE = 2;

  private final FonteClima fonte;
  private final Cache<String, ClimaResponse> cache;

  public ClimaService(FonteClima fonte, @Value("${app.integracoes.clima.ttl:PT10M}") Duration ttl) {
    this.fonte = fonte;
    this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(MAXIMO_ENTRADAS).build();
  }

  public ClimaResponse consultar(BigDecimal lat, BigDecimal lon) {
    BigDecimal latChave = lat.setScale(CASAS_DA_CHAVE, RoundingMode.HALF_UP);
    BigDecimal lonChave = lon.setScale(CASAS_DA_CHAVE, RoundingMode.HALF_UP);

    // A falha do provedor NÃO é cacheada: `Cache.get` não guarda quando a carga lança. É o que
    // queremos — cachear indisponibilidade prolongaria uma interrupção de segundos pelo TTL
    // inteiro.
    return cache.get(latChave + "," + lonChave, chave -> fonte.consultar(latChave, lonChave));
  }

  /**
   * Mesma consulta, mas indisponibilidade vira VALOR em vez de exceção.
   *
   * <p>Serve o modelo de risco, cujo consumidor principal é o webhook de transportadora. Ali,
   * deixar a exceção subir transformaria em 5xx o registro de uma entrega falida — e a
   * transportadora reenviaria em laço enquanto a encomenda continua no ponto de custódia sem
   * missão. O clima é uma das oito características do modelo; perdê-la degrada o score (a
   * característica é imputada), e degradar o score é infinitamente melhor que recusar o fato.
   *
   * <p>Reaproveita o MESMO cache da consulta normal: uma rajada de webhooks na mesma região consome
   * uma única chamada externa.
   */
  @Override
  public Optional<CondicaoClimatica> consultarParaRisco(BigDecimal lat, BigDecimal lon) {
    try {
      ClimaResponse clima = consultar(lat, lon);
      return Optional.of(new CondicaoClimatica(clima.chuvaMm(), clima.temperaturaC()));
    } catch (RuntimeException e) {
      // Captura larga de propósito: provedor fora do ar, tempo esgotado e recusa do bulkhead têm
      // tipos diferentes e a MESMA reação correta aqui. Log em debug porque, ao contrário do card
      // de clima, esta falha é esperada e já tem tratamento — não é sintoma de nada.
      log.debug("Clima indisponível para o modelo de risco, será imputado: {}", e.toString());
      return Optional.empty();
    }
  }
}
