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

  /**
   * Defaults no construtor compacto, como em {@code MissaoFiltroRequest} e {@code
   * LancamentoFiltroRequest}.
   *
   * <p>Antes os defaults viviam em acessores auxiliares e os componentes do record continuavam
   * podendo devolver null — quem chamasse {@code pagina()}, que é o nome óbvio, levava NPE. Aqui os
   * três nunca são nulos depois da construção, e não há dois jeitos de ler o mesmo campo.
   *
   * <p>{@code if} e não ternário: {@code x == null ? CONSTANTE_INT : x} desempacota o Integer e o
   * reempacota em seguida, e o SpotBugs reprova o build com {@code
   * BX_UNBOXING_IMMEDIATELY_REBOXED}.
   */
  public AlertaFiltroRequest {
    if (pagina == null) {
      pagina = 0;
    }
    if (tamanho == null) {
      tamanho = 20;
    }
    if (apenasNaoLidos == null) {
      apenasNaoLidos = false;
    }
  }
}
