package com.omnitribo.missoes.dominio;

/**
 * De onde sai o token que a missão paga ao executor. Congelado na criação, coluna {@code
 * missao.fonte_pote} (V23).
 *
 * <p>Coluna por missão, e não regra por categoria no serviço: o app consegue explicar de onde vem a
 * recompensa, a regra fica auditável junto com a missão que a aplicou, e recalibrar depois não
 * reescreve o passado.
 */
public enum FontePote {

  /**
   * Pote financiado por OUTROS membros da tribo — nunca pelo criador (ADR 0009, imposto pela 0037).
   * Toda missão criada por pessoa com recompensa em token: TRIBO, COLETA, AJUDA e, desde o ADR
   * 0038, ENTREGA.
   */
  COMUNIDADE,

  /**
   * Pote financiado pelo patrocinador da transportadora, na conversão de uma entrega falida.
   *
   * <p>O financiamento acontece na MESMA transação que cria a missão — ela nunca existe publicada
   * com pote vazio. Sem isso, o executor faria a entrega e a conclusão falharia com 422 para
   * sempre.
   */
  PATROCINADOR,

  /**
   * LEGADO: token EMITIDO na conclusão, sem financiador. Nenhuma missão nova nasce assim desde o
   * ADR 0038 (V31).
   *
   * <p>Era a ENTREGA criada por humano. Continua existindo só para as missões publicadas antes da
   * V31, que mantêm a recompensa com que foram prometidas; um RASCUNHO legado virou {@link
   * #COMUNIDADE} na própria migration. Quando a última delas terminar, a conclusão de missão deixa
   * de ser uma ponta da invariante de conservação.
   */
  CUNHAGEM,

  /**
   * Nenhum token: a recompensa é só XP. TRIBO, AJUDA e ENTREGA criadas com {@code
   * recompensaEmToken=false} (ADR 0035 e 0038).
   *
   * <p><b>Não é uma ponta da invariante.</b> Ela tem {@code tokens_recompensa = 0} e {@code
   * pote_tokens = 0}, e a conclusão não escreve lançamento nenhum — não participa da soma, o que é
   * diferente de mudá-la em zero.
   *
   * <p><b>Não aceita financiamento</b> ({@code FinanciamentoService.validarEstado}): o token do
   * financiador ficaria preso numa missão cuja conclusão não paga do pote, e a reconciliação não
   * veria a perda.
   */
  SEM_TOKEN
}
