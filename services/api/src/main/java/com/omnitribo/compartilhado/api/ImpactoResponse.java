package com.omnitribo.compartilhado.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * O painel de impacto: o que a tese do produto economizou, e sob qual premissa.
 *
 * <p>Todos os campos são derivados por agregação, a cada chamada, sobre tabelas que já existem.
 * Nada aqui é armazenado — ver ADR 0029 para por que não há tabela de agregação nem cache.
 *
 * @param geradoEm instante da apuração. Está aqui porque o número é volátil: copiar o painel para
 *     um slide sem a data produz uma afirmação sem validade, e é o tipo de coisa que uma banca
 *     pergunta ("isso é de quando?").
 */
@Schema(description = "Indicadores de impacto do ciclo de entrega falida")
public record ImpactoResponse(
    Instant geradoEm,
    EntregasFalidas entregasFalidas,
    MissoesDeRetirada missoesDeRetirada,
    CustoEvitado custoEvitado,
    Tokens tokens) {

  /**
   * Topo do funil. Os QUATRO desfechos somam o total — ver {@code ResumoEntregasFalidas}.
   *
   * @param pendentes recebidas que não viraram missão E não foram recusadas: encomenda parada na
   *     custódia. O webhook não produz esse estado; o seed histórico e o schema permitem. Está no
   *     painel porque é o número que explica uma taxa de conversão baixa — sem ele, quem lê conclui
   *     que o bairro não responde, quando a maioria das linhas nunca chegou a ser oferecida a
   *     ninguém
   * @param taxaConversao {@code convertidas / recebidas}, fração entre 0 e 1, <b>ou nulo quando
   *     nada foi recebido</b>. Nulo e não zero: "0% de conversão" é uma afirmação sobre desempenho
   *     ruim, e com denominador zero não há desempenho nenhum a relatar. A tela mostra travessão
   */
  public record EntregasFalidas(
      long recebidas,
      long convertidas,
      long pendentes,
      long recusadasPontoLotado,
      long recusadasSemPatrocinio,
      Double taxaConversao) {}

  /**
   * Meio do funil: o que aconteceu com as missões que nasceram das entregas falidas.
   *
   * @param criadas missões com o usuário-sistema como criador. <b>Pode ser MENOR que {@code
   *     convertidas}</b> sem que nada esteja errado: entrega falida do seed histórico aponta para
   *     missão criada por humano, porque o seed é anterior ao usuário-sistema. Os dois números
   *     aparecem no painel e a tela explica a diferença — ver {@code ResumoMissoesDoSistema}
   * @param taxaConclusao {@code concluidas / criadas}. O denominador é {@code criadas} e não {@code
   *     convertidas} — mesma tabela do numerador, então a taxa não pode passar de 1 por divergência
   *     entre módulos
   * @param medianaAteCheckinSegundos do instante declarado pela transportadora até o PRIMEIRO
   *     check-in válido. Nulo quando a amostra é vazia
   * @param amostraMediana quantas missões entraram na mediana. Viaja junto porque mediana sem
   *     tamanho de amostra não é interpretável
   */
  public record MissoesDeRetirada(
      long criadas,
      long concluidas,
      Double taxaConclusao,
      Long medianaAteCheckinSegundos,
      int amostraMediana) {}

  /**
   * A conta que o parceiro compraria — e a premissa que a sustenta, exposta ao lado dela.
   *
   * @param reentregasEvitadas <b>é o mesmo número que {@code missoesDeRetirada.concluidas}, com
   *     outro nome.</b> Não é uma segunda medição que confirma a primeira: é a INTERPRETAÇÃO dela,
   *     e assume que a encomenda teria sido re-entregue se o vizinho não a tivesse retirado. Ver
   *     ADR 0029 §4
   * @param premissaCustoReentregaBrl o fator vigente, ecoado. Um painel que mostra o produto sem o
   *     fator convida quem lê a supor que o fator foi medido
   * @param menos50Brl e {@code mais50Brl} são a análise de sensibilidade: a premissa pela metade e
   *     uma vez e meia. Se a conclusão muda de sinal entre as duas, ela nunca foi sobre o dado
   */
  public record CustoEvitado(
      long reentregasEvitadas,
      BigDecimal premissaCustoReentregaBrl,
      BigDecimal baseBrl,
      BigDecimal menos50Brl,
      BigDecimal mais50Brl) {}

  /**
   * O ciclo do TOKEN (ADR 0027): sobe no aporte, desce no resgate, e o resto só muda de lugar.
   *
   * @param emCirculacao {@code emCarteiras + emPotes}. É a invariante de conservação exibida como
   *     número — token em pote saiu de uma carteira e ainda não chegou na outra, e omiti-lo faria a
   *     circulação parecer menor do que é
   */
  public record Tokens(
      long aportados, long emCarteiras, long emPotes, long emCirculacao, long resgatados) {}
}
