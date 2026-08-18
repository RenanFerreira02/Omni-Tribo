package com.omnitribo.carteira.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/** Corpo de {@code POST /api/v1/carteira/saques}. */
@Schema(description = "Solicitação de saque de BRL")
public record SacarRequest(
    @NotNull(message = "Valor é obrigatório")
        @Positive(message = "Valor do saque deve ser positivo")
        // Espelha numeric(12,2) do banco: sem isto, um valor com 3 casas seria arredondado em
        // silêncio pelo driver e o cliente veria debitado um número diferente do que pediu.
        @Digits(integer = 10, fraction = 2, message = "Valor deve ter no máximo 2 casas decimais")
        BigDecimal valorBrl) {}
