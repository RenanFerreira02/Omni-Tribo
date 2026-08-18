package com.omnitribo.identidade.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Mudança de consentimento.
 *
 * <p>{@code versaoTexto} é obrigatório e vem do cliente porque é a versão do texto que a pessoa
 * REALMENTE viu na tela. Preenchê-lo no servidor registraria a versão vigente no momento da
 * gravação, que pode ser outra se um deploy acontecer no meio — e o registro passaria a afirmar que
 * ela concordou com um texto que nunca leu.
 */
public record AtualizarConsentimentoRequest(
    @NotNull(message = "Informe se o consentimento foi concedido ou revogado") Boolean concedido,
    @NotBlank(message = "Versão do texto é obrigatória")
        @Size(max = 20, message = "Versão do texto deve ter no máximo 20 caracteres")
        @Schema(example = "2026-08-01", description = "Versão do texto exibida ao usuário")
        String versaoTexto) {}
