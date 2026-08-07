package com.omnitribo.carteira.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Porta de devolução do pote aos financiadores, quando a missão termina sem pagar ninguém.
 *
 * <p>Separada de {@link CreditoRecompensa} porque a operação é outra: recompensa credita UM
 * executor a partir de uma decisão de negócio, estorno credita N financiadores a partir do que o
 * ledger já registra. Juntá-las numa interface só faria a implementação da recompensa carregar a
 * leitura do histórico de financiamento sem precisar dela.
 */
public interface EstornoPote {

  /**
   * Credita de volta cada financiador da missão e devolve o total estornado.
   *
   * <p>Na transação do chamador, que já segura o lock da linha da missão — ordem global {@code
   * missao} → {@code carteira}.
   *
   * <p>Idempotente: a chave de cada estorno vem de {@code (missaoId, carteiraFinanciadorId)}, sem
   * participação do cliente, então reprocessar a mesma missão não credita duas vezes. Nesse caso
   * devolve o total que JÁ havia sido estornado, e não zero — quem chama compara esse número com o
   * pote para detectar divergência, e devolver zero num replay disfarçaria de erro o caso correto.
   *
   * @return soma dos tokens estornados, contando lançamentos já existentes
   */
  long estornarFinanciadores(UUID missaoId, Instant agora);
}
