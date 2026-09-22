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

  /**
   * Pote financiado por membros da tribo. TRIBO, COLETA e — desde o ADR 0025 — AJUDA.
   *
   * <p>Em nenhuma delas quem financia é o criador: o ADR 0009 mantém "quem cria a missão NÃO paga",
   * e o pote é formado por OUTROS membros. Foi essa distinção que trouxe AJUDA para cá — o
   * argumento que a mantinha fora ("faria membros da tribo custearem a logística do varejista")
   * descreve ENTREGA, que tem um varejista do outro lado; AJUDA é entre vizinhos, como TRIBO.
   */
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
   * Token EMITIDO na conclusão, sem financiador. Desde o ADR 0025, só ENTREGA criada por humano.
   *
   * <p>É a última lacuna de cunhagem, e ela está declarada em vez de escondida. O financiador
   * correto de uma ENTREGA é o PATROCINADOR — entrega que falhou custa re-entrega e armazenagem a
   * ele —, mas uma ENTREGA criada por um usuário não está ligada a transportadora nenhuma, então
   * não há patrocinador a debitar. Exigir pote da tribo aqui faria vizinhos custearem logística de
   * varejista, que é o inverso do modelo.
   *
   * <p><b>AJUDA saiu daqui.</b> O argumento acima é sobre varejista, e nunca foi sobre ela — ver a
   * retificação do §8 do ADR 0024.
   */
  CUNHAGEM,

  /**
   * Nenhum token: a recompensa é só XP. TRIBO e AJUDA criadas com {@code recompensaEmToken=false}.
   *
   * <p>É a categoria de missão que a tese do produto sempre teve e a economia nunca escreveu:
   * mutirão e favor de vizinho que valem REPUTAÇÃO, não moeda. Existe porque o ADR 0025, ao trazer
   * AJUDA para {@link #COMUNIDADE}, tornou obrigatório um financiador para publicar — e previu, em
   * "Negativas", que financiar favor alheio poderia se mostrar pouco atraente e represar AJUDA em
   * RASCUNHO. Foi o que aconteceu. Ver ADR 0035.
   *
   * <p><b>Não é uma quarta ponta da invariante de conservação.</b> As três pontas que movem a soma
   * {@code SUM(carteiras) + SUM(potes)} são o aporte (sobe), a conclusão de ENTREGA em {@link
   * #CUNHAGEM} (sobe) e o resgate (desce). Uma missão SEM_TOKEN não emite nem queima: ela tem
   * {@code tokens_recompensa = 0} e {@code pote_tokens = 0}, e a conclusão dela não escreve
   * lançamento nenhum. Ela não participa da invariante — o que é diferente de mudá-la em zero.
   *
   * <p><b>Não aceita financiamento</b>, e a recusa é explícita em {@code
   * FinanciamentoService.validarEstado}. Sem esse ramo, quem tentasse financiar receberia de {@code
   * validarTeto} a frase sem sentido "pote ficaria com N tokens, acima da recompensa de 0. Faltam
   * apenas 0.". Deixar o pote crescer seria pior: a recompensa congelada é 0, a conclusão não paga
   * do pote, e o token do financiador ficaria preso numa missão que nunca o devolve — perda que a
   * reconciliação não vê, porque ledger e projeção continuam batendo.
   */
  SEM_TOKEN
}
