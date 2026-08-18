package com.omnitribo.missoes.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Contrato da resolução de disputa por ADMIN. */
@Schema(description = "Decisão do administrador sobre uma missão em disputa")
public record ResolverDisputaRequest(
    @NotNull(message = "Resultado é obrigatório") Resultado resultado,
    @NotBlank(message = "Justificativa é obrigatória")
        @Size(max = 1000, message = "Justificativa deve ter no máximo 1000 caracteres")
        String justificativa) {

  public enum Resultado {
    /** Conclui a missão e credita o executor. */
    CONCLUIR,
    /** Cancela a missão sem crédito. */
    CANCELAR
  }
}
