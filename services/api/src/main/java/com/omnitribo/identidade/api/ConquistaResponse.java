package com.omnitribo.identidade.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Uma conquista do catálogo, com o quanto o usuário já tem dela.
 *
 * <p>O catálogo volta INTEIRO, inclusive o que ainda não foi conquistado: uma lista só de medalhas
 * ganhas não diz ao usuário qual é o próximo objetivo.
 */
@Schema(description = "Conquista derivada de XP, nível e streak")
public record ConquistaResponse(
    @Schema(description = "Código estável. O app ramifica por ele — título e descrição são copy.")
        String codigo,
    String titulo,
    String descricao,
    boolean conquistada,
    @Schema(description = "Já saturado na meta: nunca vem maior que ela") long progresso,
    long meta) {}
