package com.omnitribo.identidade.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Uma tribo.
 *
 * <p>{@code centroLat}/{@code centroLon} são DERIVADOS por PostGIS a partir das missões e pontos de
 * custódia da tribo, e não colunas. Vêm nulos quando a tribo ainda não tem nenhum dos dois — nesse
 * caso o app centra o mapa onde já estava, em vez de saltar para uma coordenada inventada.
 */
@Schema(description = "Tribo do bairro")
public record TriboResponse(
    UUID id,
    String nome,
    String bairro,
    @Schema(
            description =
                "Centro derivado das missões e pontos da tribo; nulo se ela não tem nenhum")
        BigDecimal centroLat,
    BigDecimal centroLon) {}
