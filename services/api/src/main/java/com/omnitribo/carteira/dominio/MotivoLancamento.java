package com.omnitribo.carteira.dominio;

/**
 * Por que um lançamento existe (CHECK da V5). Cada motivo tem um call site de {@code
 * LivroRazaoService.registrar}, exceto {@link #BONUS}.
 */
public enum MotivoLancamento {

  /** Crédito da conclusão: pago do pote, ou emitido só na CUNHAGEM legada (ADR 0038). */
  RECOMPENSA_MISSAO,

  /** Perna de débito da transferência P2P. Soma zero com {@link #TRANSFERENCIA_RECEBIDA}. */
  TRANSFERENCIA_ENVIADA,

  /** Perna de crédito da transferência P2P, na mesma transação da perna de débito. */
  TRANSFERENCIA_RECEBIDA,

  /** Débito de quem financia o pote de uma missão da própria tribo. */
  FINANCIAMENTO_TRIBO,

  /**
   * Débito do PATROCINADOR ao financiar o pote de uma missão de retirada. Motivo próprio porque ele
   * não tem tribo. <b>Motivo novo de financiamento precisa entrar em {@code
   * LancamentoRepository.buscarFinanciamentosDaMissao}</b>, senão o estorno não o vê e o dinheiro
   * fica preso com {@code integro=true}.
   */
  FINANCIAMENTO_PATROCINADOR,

  /**
   * Emissão de token no patrocinador — o ÚNICO ponto explícito de emissão (ADMIN, auditado, ADR
   * 0024).
   */
  APORTE_PATROCINADOR,

  /**
   * Resgate de benefício: debita sem creditar ninguém — QUEIMA token (ADR 0027). Com o aporte,
   * fecha a economia como ciclo:
   *
   * <pre>
   *   APORTE_PATROCINADOR  credita sem contraparte  -&gt; EMITE
   *   RESGATE              debita  sem contraparte  -&gt; QUEIMA
   * </pre>
   */
  RESGATE,

  /** Saída de BRL. Desligado por padrão desde o ADR 0009 — ver {@code PoliticaCarteira}. */
  SAQUE,

  /**
   * RESERVADO, só em fixture de teste. Emitiria token fora do ciclo de missões — não deve ganhar
   * call site sem um sumidouro correspondente.
   */
  BONUS,

  /** Devolução do pote aos financiadores quando a missão é cancelada ou expira. */
  ESTORNO
}
