package com.omnitribo.carteira.api;

import java.util.UUID;

/**
 * Saída de {@link AporteToken#aportar}.
 *
 * @param saldoTokens saldo DEPOIS do aporte, ou o saldo atual quando é replay
 * @param replay {@code true} quando a chave já existia e nada foi emitido nesta chamada. O cliente
 *     recebe o mesmo corpo da primeira vez, que é o que torna o retry seguro num endpoint que cunha
 *     moeda
 */
public record ResultadoAporte(UUID lancamentoId, long saldoTokens, boolean replay) {}
