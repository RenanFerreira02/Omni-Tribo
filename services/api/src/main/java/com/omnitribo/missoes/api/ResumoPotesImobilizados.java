package com.omnitribo.missoes.api;

/**
 * Quanto token está preso em missão não-terminal parada, e desde qual limiar isso conta.
 *
 * <p>É a METADE da conservação (ADR 0027) que a reconciliação não enxerga. A reconciliação compara
 * o saldo de cada carteira com a soma do ledger dela, e um pote imobilizado não quebra essa
 * igualdade: o token saiu da carteira de quem financiou com lançamento e projeção escritos na mesma
 * transação, e simplesmente nunca chegou na de quem executaria. Por isso {@code integro=true} e
 * {@code missoes > 0} coexistem — são invariantes diferentes, e a primeira passa enquanto a segunda
 * é violada. Ver ADR 0032.
 *
 * @param missoes quantas missões não-terminais estão paradas há mais que {@code limiar} com pote
 * @param tokens soma dos potes dessas missões — o valor que a conservação perdeu de vista
 * @param limiar quanto tempo parado uma missão precisa ter para entrar nesta conta, em ISO-8601
 *     ("PT96H"). É o valor de {@code app.missoes.diagnostico.pote-imobilizado-apos}, literalmente,
 *     para que a resposta e a configuração possam ser comparadas a olho. Viaja junto do número de
 *     propósito: sem ele "3 missões imobilizadas" não é interpretável, porque quem lê não sabe se o
 *     corte foi de uma hora ou de um mês.
 *     <p><b>String e não {@code Duration}</b>: sem configuração explícita, Jackson serializa {@code
 *     Duration} como número de segundos, e o corpo publicaria {@code 345600.000000000} onde um
 *     leitor humano precisa de {@code PT96H}. Ligar {@code write-durations-as-timestamps: false}
 *     mudaria o formato de toda a API por causa de um campo.
 */
public record ResumoPotesImobilizados(long missoes, long tokens, String limiar) {}
