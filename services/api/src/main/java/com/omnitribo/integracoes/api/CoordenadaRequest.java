package com.omnitribo.integracoes.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Par de coordenadas de uma consulta.
 *
 * <p>Os limites não são decoração: sem eles, uma latitude 999 viraria uma chamada externa com
 * parâmetro absurdo — e uma chave de cache a mais, que é o que um cliente hostil precisaria para
 * encher a memória.
 */
public record CoordenadaRequest(
    @NotNull(message = "Latitude é obrigatória")
        @DecimalMin(value = "-90.0", message = "Latitude fora do intervalo")
        @DecimalMax(value = "90.0", message = "Latitude fora do intervalo")
        BigDecimal lat,
    @NotNull(message = "Longitude é obrigatória")
        @DecimalMin(value = "-180.0", message = "Longitude fora do intervalo")
        @DecimalMax(value = "180.0", message = "Longitude fora do intervalo")
        BigDecimal lon) {}
