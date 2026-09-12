package com.omnitribo.compartilhado.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Paginação da carta-morta. Mesmo molde de {@code BeneficioFiltroRequest}: os padrões moram no
 * próprio record, e o controller monta o {@code PageRequest}.
 *
 * <p>Não há filtro por tipo de evento de propósito. O conjunto de esgotados é, num sistema
 * saudável, vazio — filtrar um conjunto que cabe numa página é superfície de API para nada.
 */
public record OutboxFiltroRequest(@Min(0) Integer pagina, @Min(1) @Max(100) Integer tamanho) {

  public int paginaOuPadrao() {
    return pagina == null ? 0 : pagina;
  }

  public int tamanhoOuPadrao() {
    return tamanho == null ? 20 : tamanho;
  }
}
