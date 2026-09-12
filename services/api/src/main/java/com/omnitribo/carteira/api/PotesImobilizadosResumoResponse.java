package com.omnitribo.carteira.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Token preso em missão parada, publicado AO LADO de {@code integro} — nunca dentro dele.
 *
 * <h2>Por que é um campo separado, e não uma condição de {@code integro}</h2>
 *
 * <p>Porque são invariantes DIFERENTES, e misturá-las apagaria as duas. A <b>reconciliação</b>
 * compara o saldo materializado de cada carteira com a soma do ledger dela; a <b>conservação</b>
 * (ADR 0027) compara a soma do sistema consigo mesma ao longo do ciclo. Um pote imobilizado não
 * quebra a primeira: o token saiu da carteira de quem financiou com lançamento e projeção escritos
 * na mesma transação, e as duas somas continuam batendo exatamente. Ele quebra a segunda, porque o
 * token nunca chegou em carteira nenhuma e não volta sozinho.
 *
 * <p><b>{@code integro=true} com {@code missoes > 0} é um estado coerente, não uma contradição.</b>
 * Dizer o contrário — fazer {@code integro} virar {@code false} aqui — destruiria a única pergunta
 * que este endpoint sabe responder com precisão ("alguma carteira diverge do seu ledger?") para
 * responder mal uma segunda. E o projeto já pagou por confundir as duas três vezes: o estorno na
 * expiração, a cunhagem de ENTREGA e a queima do resgate.
 *
 * <p>Os números vêm da porta {@code missoes/api/DiagnosticoPotes}, no mesmo snapshot transacional
 * que produziu {@code integro}, e são remapeados para este record para que o contrato publicado por
 * {@code carteira} continue sendo de {@code carteira}. Ver ADR 0032.
 *
 * @param missoes quantas missões não-terminais estão paradas além do limiar segurando pote
 * @param tokens soma desses potes
 * @param limiar o corte que produziu os dois, em ISO-8601 ("PT96H"). Viaja junto porque "3 missões"
 *     não é interpretável sem saber se o corte foi de uma hora ou de um mês. String e não {@code
 *     Duration} porque Jackson serializaria a segunda como número de segundos
 */
@Schema(description = "Token preso em missão parada — a invariante que a reconciliação NÃO mede")
public record PotesImobilizadosResumoResponse(long missoes, long tokens, String limiar) {}
