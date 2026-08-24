package com.omnitribo.identidade.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;

/**
 * Corpo de {@code POST /api/v1/admin/patrocinadores/{id}/aportes}.
 *
 * @param tokens quantidade a EMITIR. O {@code @Max} não é burocracia: este é o único endpoint do
 *     sistema que cria moeda, e um zero a mais digitado por engano infla a oferta de forma que
 *     nenhum estorno desfaz — {@code lancamento} é append-only e a correção seria um débito de
 *     compensação, que precisa ser decidido por gente. O teto transforma o erro de digitação em
 *     400.
 */
public record AportarRequest(
    @Positive(message = "Aporte precisa ser de pelo menos 1 token")
        @Max(value = 1_000_000, message = "Aporte máximo por operação é 1.000.000 de tokens")
        long tokens) {}
