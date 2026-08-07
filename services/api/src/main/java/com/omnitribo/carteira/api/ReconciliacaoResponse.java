package com.omnitribo.carteira.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Relatório de conciliação do ledger.
 *
 * @param integro {@code true} quando NENHUMA carteira diverge. É a asserção que todo teste de
 *     concorrência faz ao final: se o ledger e as projeções batem depois de 100 threads brigando
 *     pela mesma linha, a atomicidade não é teórica.
 */
@Schema(description = "Resultado da conciliação entre ledger e projeções de saldo")
public record ReconciliacaoResponse(
    long carteirasVerificadas, boolean integro, List<DivergenciaResponse> divergencias) {

  /** List.copyOf: a lista guardada é imutável e o acessor não tem o que expor. */
  public ReconciliacaoResponse {
    divergencias = List.copyOf(divergencias);
  }
}
