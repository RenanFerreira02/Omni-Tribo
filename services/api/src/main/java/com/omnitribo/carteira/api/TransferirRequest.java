package com.omnitribo.carteira.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Corpo de {@code POST /api/v1/carteira/transferencias}.
 *
 * <p>O REMETENTE não está aqui — vem sempre do JWT. Aceitá-lo do corpo permitiria transferir da
 * carteira alheia trocando um campo do JSON.
 */
@Schema(description = "Transferência de tokens entre membros da mesma tribo")
public record TransferirRequest(
    @NotNull(message = "Destinatário é obrigatório") UUID destinatarioId,
    @NotNull(message = "Quantidade de tokens é obrigatória")
        @Positive(message = "Transferência deve ser positiva")
        Long tokens,
    @Size(max = 200, message = "Mensagem deve ter no máximo 200 caracteres") String mensagem) {}
