package com.omnitribo.missoes.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Motivo de um destravamento manual por ADMIN.
 *
 * <p>A justificativa é obrigatória pelo mesmo motivo de {@code ResolverDisputaRequest}: cancelar a
 * missão de outra pessoa e devolver o pote é ato discricionário, e um ato discricionário sem motivo
 * registrado é indefensável depois — nem para quem executa, nem para quem financiou.
 */
@Schema(description = "Justificativa do destravamento manual")
public record DestravarMissaoRequest(
    @NotBlank(message = "Justificativa é obrigatória")
        @Size(max = 1000, message = "Justificativa deve ter no máximo 1000 caracteres")
        @Schema(example = "Executor confirmou por telefone que não poderá concluir.")
        String justificativa) {}
