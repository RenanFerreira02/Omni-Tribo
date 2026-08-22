package com.omnitribo.carteira.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Corpo de {@code POST /api/v1/resgates}.
 *
 * <p>Só o benefício. O CUSTO não vem do cliente — é lido do catálogo no servidor e congelado na
 * linha de resgate. Aceitar um preço do corpo deixaria quem chama escolher quanto pagar, que é
 * exatamente o que o ADR 0009 tirou da criação de missão.
 *
 * <p>A identidade de quem resgata vem do JWT, nunca do corpo.
 */
public record ResgatarRequest(@NotNull(message = "Benefício é obrigatório") UUID beneficioId) {}
