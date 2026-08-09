package com.omnitribo.integracoes.dominio;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.omnitribo.integracoes.api.ClimaResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
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
public class ClimaService {

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
}
