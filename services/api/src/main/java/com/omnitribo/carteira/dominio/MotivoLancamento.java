package com.omnitribo.carteira.dominio;

/**
 * Por que um lançamento existe. Espelhado num {@code CHECK} da V5.
 *
 * <p>Cada motivo corresponde a exatamente um call site de {@code LivroRazaoService.registrar} — que
 * é o único ponto do sistema autorizado a mexer em saldo. A exceção é {@link #BONUS}.
 */
public enum MotivoLancamento {

  /**
   * Crédito da conclusão.
   *
   * <p>Pago DO POTE sempre que {@code missao.fonte_pote} for COMUNIDADE ou PATROCINADOR; cunhado só
   * quando for CUNHAGEM, o que desde o ADR 0025 significa apenas ENTREGA criada por humano. Até a
   * V23 a decisão era por CATEGORIA e ENTREGA cunhava sempre — ver ADR 0024.
   */
  RECOMPENSA_MISSAO,

  /** Perna de débito da transferência P2P. Soma zero com {@link #TRANSFERENCIA_RECEBIDA}. */
  TRANSFERENCIA_ENVIADA,

  /** Perna de crédito da transferência P2P, na mesma transação da perna de débito. */
  TRANSFERENCIA_RECEBIDA,

  /** Débito de quem financia o pote de uma missão da própria tribo. */
  FINANCIAMENTO_TRIBO,

  /**
   * Débito do PATROCINADOR ao financiar o pote de uma missão de retirada, na conversão do webhook.
   *
   * <p>Motivo próprio, e não reuso de {@link #FINANCIAMENTO_TRIBO}: o extrato e a exportação LGPD
   * mostram o motivo cru, e "financiamento de tribo" na carteira de um patrocinador afirmaria um
   * pertencimento que não existe — ele não tem tribo, e é justamente por isso que não passa por
   * {@code FinanciamentoService.validarAutorizacao}.
   *
   * <p><b>Quem acrescentar um terceiro motivo de financiamento precisa alterar {@code
   * LancamentoRepository.buscarFinanciamentosDaMissao} junto.</b> Aquela query é o que o estorno de
   * missão cancelada ou expirada enxerga; um motivo fora dela deixa o dinheiro preso na missão
   * morta enquanto a reconciliação segue respondendo {@code integro=true}, porque ledger e projeção
   * continuam batendo. A falha fica invisível exatamente para o endpoint que existe para achá-la.
   */
  FINANCIAMENTO_PATROCINADOR,

  /**
   * Emissão de token na carteira de um patrocinador. É o ÚNICO ponto de cunhagem do sistema.
   *
   * <p>Diferente de {@link #BONUS}, este TEM sumidouro e por isso pode existir: o token aportado
   * sai da carteira do patrocinador para o pote da missão, e do pote para o executor. A conservação
   * {@code SUM(carteiras) + SUM(potes)} vale em todo o ciclo de missões — só o aporte a altera, e
   * ele é endpoint ADMIN, auditado e idempotente.
   *
   * <p>Antes da V23 a cunhagem acontecia na CONCLUSÃO de toda missão ENTREGA e AJUDA, implícita e
   * por missão. Ela não foi eliminada: foi movida para um evento explícito, com ator identificado e
   * trilha. Ver ADR 0024 §2.
   */
  APORTE_PATROCINADOR,

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
