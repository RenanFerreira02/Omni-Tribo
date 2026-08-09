package com.omnitribo.notificacoes.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Contador do badge da barra superior.
 *
 * <p>Endpoint próprio, e não um campo da listagem, porque o app precisa do número em telas que não
 * mostram a lista — pedir a primeira página só para ler {@code totalElementos} traria 20 corpos de
 * notificação a cada verificação.
 */
@Schema(description = "Quantidade de notificações não lidas")
public record ContagemNaoLidosResponse(long naoLidos) {}
