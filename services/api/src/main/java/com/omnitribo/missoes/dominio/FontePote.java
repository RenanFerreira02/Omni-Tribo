package com.omnitribo.missoes.dominio;

/**
 * De onde sai o token que a missão paga ao executor. Congelado na criação, coluna {@code
 * missao.fonte_pote} (V23).
 *
 * <p>Substitui a decisão por CATEGORIA que vivia em {@code MissaoService.pagaTokensDoPote}. A troca
 * não foi cosmética: com a regra por categoria, ligar ENTREGA ao pote quebraria a ENTREGA criada
 * por HUMANO, que ficaria impublicável — {@code FinanciamentoService.validarEstado} recusa
 * financiamento de ENTREGA, então o pote nunca alcançaria a recompensa e a missão morreria em
 * RASCUNHO. As duas ENTREGAs precisam ser distinguíveis, e categoria não as distingue.
 *
 * <p>Coluna por missão, e não constante no serviço, pelas mesmas três razões que a V21 deu para
 * {@code nivel_minimo}: o app consegue explicar de onde vem a recompensa, a regra fica auditável
 * junto com a missão que a aplicou, e recalibrar depois não reescreve o passado.
 */
public enum FontePote {

  /** Pote financiado por membros da tribo. TRIBO e COLETA. */
  COMUNIDADE,

  /**
   * Pote financiado pelo patrocinador da transportadora, na conversão de uma entrega falida.
   *
   * <p>O financiamento acontece na MESMA transação que cria a missão — ela nunca existe publicada
   * com pote vazio. Sem isso, o executor faria a entrega e a conclusão falharia com 422 para
   * sempre; e como missão de retirada só conclui pela varredura de prazo, o erro apareceria no job,
   * não numa requisição, com o token do executor perdido e a vaga do ponto nunca liberada.
   */
  PATROCINADOR,

  /**
   * Token EMITIDO na conclusão, sem financiador. Hoje: AJUDA e ENTREGA criada por humano.
   *
   * <p>É a lacuna que sobrou, e ela está declarada em vez de escondida. AJUDA não tem financiador
   * plausível: quem pede ajuda não paga (é a premissa do produto), e exigir pote da tribo faria o
   * vizinho custear o favor que ele mesmo pediu. ENTREGA de humano tem o problema simétrico do
   * varejista — ver ADR 0024 §8, que registra as duas como trabalho seguinte, não como
   * esquecimento.
   */
  CUNHAGEM
}
