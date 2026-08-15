package com.omnitribo.logistica.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Porta pela qual {@code missoes} avisa a logística de que a encomenda saiu da custódia.
 *
 * <p>É a contraparte de {@code missoes/api/ConversaoEntregaFalida}: uma cria a missão quando a
 * encomenda chega, a outra libera a vaga quando ela sai.
 *
 * <p><b>Chamada de dentro da transação da conclusão</b>, junto com o crédito da recompensa. Passar
 * isto pela outbox seria mais desacoplado e estaria errado: a entrega é at-least-once, e um
 * decremento de ocupação redespachado liberaria uma vaga que nunca existiu — erro que só apareceria
 * muito depois, quando um ponto aceitasse mais encomendas do que cabe. Crédito e baixa commitam
 * juntos ou não commitam.
 */
public interface BaixaCustodia {

  /**
   * Libera a vaga e carimba a saída da encomenda.
   *
   * <p>Silenciosa e idempotente quando a missão não veio de entrega falida (o caso de toda missão
   * ENTREGA criada à mão) ou quando a baixa já foi dada.
   */
  void darBaixa(UUID missaoId, Instant quando);
}
