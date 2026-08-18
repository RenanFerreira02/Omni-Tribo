package com.omnitribo.notificacoes.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Uma notificação, como o app a vê.
 *
 * <p>{@code usuarioId} NÃO sai: a caixa é sempre a de quem perguntou, resolvida do JWT, e devolver
 * o dono só daria ao cliente um campo para tentar variar.
 */
@Schema(description = "Notificação da caixa de entrada do usuário autenticado")
public record AlertaResponse(
    UUID id,
    @Schema(
            description =
                "Discriminador estável do evento, ex. MISSAO_CONCLUIDA. O app ramifica "
                    + "por ele; título e corpo são texto para humano e mudam com a copy.")
        String tipo,
    String titulo,
    String corpo,
    @Schema(description = "Missão relacionada, quando houver — é o destino do toque na notificação")
        UUID missaoId,
    boolean lido,
    Instant criadoEm) {}
