package com.omnitribo.identidade.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Estado ATUAL de um consentimento.
 *
 * <p>A tabela é append-only por escolha — cada mudança grava uma linha nova, e o estado atual é a
 * mais recente por tipo. É o que permite responder "quando ele consentiu, e sob qual versão do
 * texto?", pergunta que uma coluna sobrescrita não responde.
 */
@Schema(description = "Consentimento do titular, no estado atual")
public record ConsentimentoResponse(
    @Schema(description = "LOCALIZACAO, NOTIFICACAO ou TERMOS") String tipo,
    boolean concedido,
    @Schema(description = "Versão do texto vigente quando a escolha foi feita") String versaoTexto,
    @Schema(description = "Quando esta escolha foi registrada") Instant registradoEm) {}
