package com.omnitribo.missoes.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * Missão próxima com a distância calculada pelo servidor.
 *
 * <p>Compõe MissaoResponse em vez de repetir seus 28 campos: a missão continua tendo uma única
 * representação de leitura na API, e acrescentar campo a MissaoResponse não exige tocar aqui.
 *
 * <p>A distância vem de ST_Distance no PostGIS, em metros, sobre a coordenada que o cliente
 * informou. Valor calculado no cliente nunca é aceito nem usado.
 */
@Schema(description = "Missão próxima, com a distância medida pelo servidor")
public record MissaoProximaResponse(
    MissaoResponse missao,
    @Schema(description = "Distância em metros da coordenada informada até a origem da missão")
        BigDecimal distanciaM) {}
