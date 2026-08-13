package com.omnitribo.carteira.dominio;

/**
 * Por que um lançamento existe. Espelhado num {@code CHECK} da V5.
 *
 * <p>Cada motivo corresponde a exatamente um call site de {@code LivroRazaoService.registrar} — que
 * é o único ponto do sistema autorizado a mexer em saldo. A exceção é {@link #BONUS}.
 */
public enum MotivoLancamento {

  /**
   * Crédito da conclusão. Pago do pote em TRIBO e COLETA; CUNHADO em ENTREGA e AJUDA (Pend. #2).
   */
  RECOMPENSA_MISSAO,

  /** Perna de débito da transferência P2P. Soma zero com {@link #TRANSFERENCIA_RECEBIDA}. */
  TRANSFERENCIA_ENVIADA,

  /** Perna de crédito da transferência P2P, na mesma transação da perna de débito. */
  TRANSFERENCIA_RECEBIDA,

  /** Débito de quem financia o pote de uma missão da própria tribo. */
  FINANCIAMENTO_TRIBO,

  /** Saída de BRL. Desligado por padrão desde o ADR 0009 — ver {@code PoliticaCarteira}. */
  SAQUE,

  /**
   * RESERVADO. Nenhum caminho de produção emite este motivo; ele aparece só em fixture de teste.
   *
   * <p>Seria a cunhagem por promoção ou recompensa de campanha, e é a única constante aqui que
   * criaria token do nada fora do ciclo de missões. Justamente por isso não deve ganhar um call
   * site sem antes existir um sumidouro correspondente — senão a conservação {@code SUM(carteiras)
   * + SUM(potes)} passa a depender de quantas campanhas rodaram.
   */
  BONUS,

  /** Devolução do pote aos financiadores quando a missão é cancelada ou expira. */
  ESTORNO
}
