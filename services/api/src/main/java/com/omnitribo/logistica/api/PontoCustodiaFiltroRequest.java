package com.omnitribo.logistica.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Centro da busca por pontos de custódia. Mesmos limites de {@code MissaoProximaFiltroRequest}. */
public record PontoCustodiaFiltroRequest(
    @NotNull(message = "Latitude é obrigatória")
        @DecimalMin(value = "-90.0", message = "Latitude fora do intervalo")
        @DecimalMax(value = "90.0", message = "Latitude fora do intervalo")
        BigDecimal lat,
    @NotNull(message = "Longitude é obrigatória")
        @DecimalMin(value = "-180.0", message = "Longitude fora do intervalo")
        @DecimalMax(value = "180.0", message = "Longitude fora do intervalo")
        BigDecimal lon) {}
