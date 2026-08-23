package com.omnitribo.logistica.api;

/**
 * O topo do funil de impacto: o que a transportadora reportou e o que aconteceu com cada encomenda.
 *
 * <p><b>São QUATRO desfechos, não três, e o quarto foi descoberto por teste.</b> O webhook produz
 * três (ADR 0021 e ADR 0024): {@code CONVERTIDA}, {@code RECUSADA} por ponto lotado e {@code
 * SEM_PATROCINIO}. Mas o schema permite — e o seed V901 usa — a linha com {@code missao_id} nulo
 * <i>e</i> {@code motivo_recusa} nulo: encomenda recebida, fisicamente na custódia, que nunca virou
 * missão nem foi recusada. É o {@code pendentes}, e são 16 das 22 linhas do banco de
 * desenvolvimento.
 *
 * <p>A primeira versão deste record tinha três campos e um javadoc afirmando que eles somavam o
 * total. {@code ImpactoTest.funilBateComOBanco} reprovou na hora — a soma dava 6 contra 22
 * recebidas. Sem esse teste, o painel teria publicado uma taxa de conversão calculada sobre um
 * denominador que ninguém sabia conter um quarto grupo invisível.
 *
 * <p>Com os quatro, a identidade vale de novo: {@code recebidas = convertidas + pendentes +
 * recusadasPontoLotado + recusadasSemPatrocinio}. Um quinto desfecho futuro quebra o teste em vez
 * de sumir dentro de um resto.
 *
 * <p>Os dois motivos de recusa vêm separados de propósito: lotação é problema de CAPACIDADE do
 * bairro e patrocínio é problema de DINHEIRO da transportadora. Agregá-los num "recusadas" único
 * esconderia qual gargalo apertar.
 */
public record ResumoEntregasFalidas(
    long recebidas,
    long convertidas,
    long pendentes,
    long recusadasPontoLotado,
    long recusadasSemPatrocinio) {}
