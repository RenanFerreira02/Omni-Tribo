package com.omnitribo.missoes.infra;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.omnitribo.missoes.api.MissaoProximaResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Cache em memória da busca por proximidade. TTL de 30 s.
 *
 * <p>Caffeine direto, sem a abstração de cache do Spring e sem {@code @Cacheable}. Três motivos: o
 * {@code @CacheEvict} dispara dentro da transação, que é o momento errado (ver {@link
 * #invalidarAposCommit()}); um objeto {@link Cache} explícito deixa o tamanho observável num teste;
 * e a indireção não compra nada quando existe exatamente um cache no sistema.
 *
 * <p>Sem Redis, pela mesma razão já registrada para o rate limiting: uma instância só no MVP. Um
 * cache em processo resolve, e um broker a mais seria infraestrutura sem problema correspondente.
 */
@Component
public class CacheMissoesProximas {

  private static final Duration TTL = Duration.ofSeconds(30);

  // Teto contra crescimento sem limite: a chave inclui uma célula de geohash escolhida pelo
  // cliente, então sem maximumSize um cliente hostil varreria o globo e encheria a heap.
  private static final int MAXIMO_ENTRADAS = 10_000;

  private final Cache<ChaveProximidade, List<MissaoProximaResponse>> cache =
      Caffeine.newBuilder().expireAfterWrite(TTL).maximumSize(MAXIMO_ENTRADAS).build();

  /** Devolve do cache ou calcula e guarda. A carga só roda no MISS. */
  public List<MissaoProximaResponse> obter(
      ChaveProximidade chave, Function<ChaveProximidade, List<MissaoProximaResponse>> carga) {
    return cache.get(chave, carga);
  }

  /**
   * Invalida DEPOIS do commit, nunca durante.
   *
   * <p>Chamar {@code invalidateAll()} dentro da transação abriria uma janela em que uma busca
   * concorrente repopula o cache lendo o estado PRÉ-commit. Essa entrada obsoleta sobreviveria os
   * 30 s inteiros do TTL — exatamente o bug que a invalidação existe para evitar, e agora sem
   * nenhuma invalidação posterior para corrigi-lo.
   *
   * <p>Fora de transação (job, teste unitário) invalida na hora.
   *
   * <p>Invalidação global e não por chave: uma missão publicada afeta toda célula cujo raio a
   * alcance, e enumerar essas células custaria mais que recalcular. Com TTL de 30 s o custo de um
   * flush é baixo por construção.
   */
  public void invalidarAposCommit() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              cache.invalidateAll();
            }
          });
    } else {
      cache.invalidateAll();
    }
  }

  /** Invalidação imediata e incondicional. Existe para o arrange de teste, não para produção. */
  public void invalidarAgora() {
    cache.invalidateAll();
  }

  /**
   * Entradas vivas no cache. Só para teste.
   *
   * <p>cleanUp() antes de contar porque o Caffeine executa manutenção de forma assíncrona e
   * estimatedSize() sozinho pode devolver valor defasado — o que tornaria qualquer asserção sobre
   * invalidação intermitente.
   */
  public long tamanho() {
    cache.cleanUp();
    return cache.estimatedSize();
  }
}
