package com.omnitribo.carteira.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Saída de {@link CreditoRecompensa}.
 *
 * @param replay {@code true} quando a chave já existia e nada foi gravado nesta chamada. O chamador
 *     usa isso para responder 200 com o estado atual em vez de 409 — um retry não é conflito.
 */
public record ResultadoCredito(
    UUID lancamentoId, BigDecimal saldoBrl, long saldoTokens, boolean replay) {}
