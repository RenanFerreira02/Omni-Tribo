package com.omnitribo.missoes.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/** Corpo de {@code POST /api/v1/tribos/{triboId}/financiamentos}. */
@Schema(description = "Financiamento de uma missão comunitária com tokens")
public record FinanciarMissaoRequest(
    @NotNull(message = "Missão é obrigatória") UUID missaoId,
    @NotNull(message = "Quantidade de tokens é obrigatória")
        @Positive(message = "Financiamento deve ser positivo")
        Long tokens) {}
