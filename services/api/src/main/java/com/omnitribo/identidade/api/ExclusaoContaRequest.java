package com.omnitribo.identidade.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Confirmação da exclusão de conta.
 *
 * <p>Exige a SENHA ATUAL, e não só um toque em "confirmar". A operação é irreversível e o token de
 * acesso vive 15 minutos: um aparelho desbloqueado e esquecido em cima da mesa bastaria para apagar
 * a identidade de alguém. A senha prova que quem está pedindo é o titular, e não quem pegou o
 * telefone.
 *
 * <p>A dupla confirmação na tela é a outra metade da defesa, e não substitui esta: uma protege
 * contra o toque acidental, a outra contra a pessoa errada.
 */
public record ExclusaoContaRequest(
    @NotBlank(message = "Senha atual é obrigatória")
        @Schema(description = "Senha atual do titular, para provar que o pedido é dele")
        String senha) {}
