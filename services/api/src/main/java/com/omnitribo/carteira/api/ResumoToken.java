package com.omnitribo.carteira.api;

/**
 * As duas pontas do ciclo do TOKEN mais o estoque parado em carteira.
 *
 * <p>A economia é um CICLO, não um estoque (ADR 0027): {@code aportados} é a única entrada — {@code
 * APORTE_PATROCINADOR}, o único ponto de emissão — e {@code resgatados} a única saída, o {@code
 * RESGATE} que queima sem creditar contraparte. Tudo entre as duas apenas muda token de lugar.
 *
 * <p><b>Não espere {@code aportados - resgatados == emCarteiras + potes}.</b> Antes do ADR 0024 a
 * emissão acontecia na CONCLUSÃO de cada ENTREGA e AJUDA, implícita e sem lançamento de aporte, e a
 * conversão 1:2 do seed (ADR 0009) criou saldo que nenhum aporte explica. Esse legado é real e
 * transformar a diferença em asserção reprovaria por um motivo que não é defeito.
 */
public record ResumoToken(long aportados, long resgatados, long emCarteiras) {}
