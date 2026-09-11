package com.omnitribo.missoes.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Paginação do diagnóstico de potes imobilizados. Mesmo molde de {@code OutboxFiltroRequest}: os
 * padrões moram no próprio record e o controller monta o {@code PageRequest}.
 *
 * <p>Não há filtro por status de propósito. Num sistema saudável o conjunto é vazio, e quando não
 * é, o que o ADMIN precisa ver primeiro é o mais antigo — que é como a consulta já ordena. Filtrar
 * um conjunto que cabe numa página é superfície de API para nada.
 */
public record PotesImobilizadosFiltroRequest(
    @Min(0) Integer pagina, @Min(1) @Max(100) Integer tamanho) {

  public int paginaOuPadrao() {
    return pagina == null ? 0 : pagina;
  }

  public int tamanhoOuPadrao() {
    return tamanho == null ? 20 : tamanho;
  }
}
