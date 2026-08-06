package com.omnitribo.missoes.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** Corpo opcional de cancelar, desistir e contestar. Vai para o payload da trilha de eventos. */
@Schema(description = "Motivo livre, opcional, registrado na trilha da missão")
public record MotivoRequest(
    @Size(max = 500, message = "Motivo deve ter no máximo 500 caracteres") String motivo) {}
