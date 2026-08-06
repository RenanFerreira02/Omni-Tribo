package com.omnitribo.missoes.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Contrato do check-in geolocalizado. A implementação chega em F6.
 *
 * <p>A distância até a origem é calculada no SERVIDOR por PostGIS e comparada com raio_checkin_m —
 * nenhum valor de distância informado pelo cliente é aceito.
 */
@Schema(description = "Coordenada capturada pelo dispositivo no momento do check-in")
public record RegistrarCheckinRequest(
    @NotNull(message = "Latitude é obrigatória")
        @DecimalMin(value = "-90.0", message = "Latitude deve estar entre -90 e 90")
        @DecimalMax(value = "90.0", message = "Latitude deve estar entre -90 e 90")
        BigDecimal lat,
    @NotNull(message = "Longitude é obrigatória")
        @DecimalMin(value = "-180.0", message = "Longitude deve estar entre -180 e 180")
        @DecimalMax(value = "180.0", message = "Longitude deve estar entre -180 e 180")
        BigDecimal lon,
    @NotNull(message = "Acurácia é obrigatória")
        @DecimalMin(value = "0.0", message = "Acurácia não pode ser negativa")
        @DecimalMax(value = "10000.0", message = "Acurácia acima de 10 km é inutilizável")
        BigDecimal acuraciaM) {}
