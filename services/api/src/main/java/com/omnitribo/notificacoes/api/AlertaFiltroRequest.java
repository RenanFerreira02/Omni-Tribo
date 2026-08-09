package com.omnitribo.notificacoes.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Filtro e paginação da caixa de entrada.
 *
 * <p>Não há filtro por usuário, e a ausência é a regra: o dono vem do JWT. Ver {@code
 * AlertaRepository}.
 */
public record AlertaFiltroRequest(
    @Min(value = 0, message = "Página não pode ser negativa") Integer pagina,
    @Min(value = 1, message = "Tamanho mínimo é 1")
        @Max(value = 100, message = "Tamanho máximo é 100")
        Integer tamanho,
    @Schema(description = "Quando true, devolve só o que ainda não foi lido")
        Boolean apenasNaoLidos) {

  public int paginaOuPrimeira() {
    return pagina == null ? 0 : pagina;
  }

  public int tamanhoOuPadrao() {
    return tamanho == null ? 20 : tamanho;
  }

  public boolean apenasNaoLidosOuFalso() {
    return Boolean.TRUE.equals(apenasNaoLidos);
  }
}
