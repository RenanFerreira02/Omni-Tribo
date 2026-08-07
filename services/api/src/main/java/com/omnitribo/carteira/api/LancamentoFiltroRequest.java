package com.omnitribo.carteira.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Paginação do extrato.
 *
 * <p>Sem whitelist de ordenação, ao contrário de {@code MissaoFiltroRequest}: o extrato tem UMA
 * ordem legítima, cronológica decrescente, e ela é fixa. Deixar o cliente reordenar um ledger
 * convidaria a ordenar por coluna sem índice — e, pior, um extrato fora de ordem cronológica
 * mostraria {@code saldoApos*} numa sequência que não faz sentido para quem lê.
 */
@Schema(description = "Paginação do extrato")
public record LancamentoFiltroRequest(
    // Integer, não int: query param ausente chega como null, e o binder não converte null para
    // primitivo — todo GET sem ?pagina= viraria 400.
    @Min(value = 0, message = "Página não pode ser negativa") Integer pagina,
    @Min(value = 1, message = "Tamanho mínimo de página é 1")
        @Max(value = 100, message = "Tamanho máximo de página é 100")
        Integer tamanho) {

  public LancamentoFiltroRequest {
    if (pagina == null) {
      pagina = 0;
    }
    if (tamanho == null) {
      tamanho = 20;
    }
  }
}
