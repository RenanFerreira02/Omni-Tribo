package com.omnitribo.carteira.api;

import java.util.UUID;

/**
 * Saída de {@link FinanciamentoMissao#debitar}.
 *
 * @param replay {@code true} quando a chave já existia e nada foi debitado nesta chamada — o
 *     chamador NÃO deve creditar o pote de novo
 */
public record ResultadoFinanciamento(UUID lancamentoId, long saldoTokensRestante, boolean replay) {}
