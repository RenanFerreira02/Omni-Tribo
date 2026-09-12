package com.omnitribo.carteira.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Relatório de conciliação do ledger, mais o número que ele NÃO mede.
 *
 * <p><b>{@code integro} e {@code potesImobilizados} respondem perguntas diferentes, e é por isso
 * que são campos separados.</b> {@code integro=true} com {@code potesImobilizados.missoes() > 0} é
 * um estado coerente: nenhuma carteira diverge do seu ledger E existe token preso em missão parada.
 * Ver {@link PotesImobilizadosResumoResponse} e o ADR 0032.
 *
 * @param integro {@code true} quando NENHUMA carteira diverge. É a asserção que todo teste de
 *     concorrência faz ao final: se o ledger e as projeções batem depois de 100 threads brigando
 *     pela mesma linha, a atomicidade não é teórica. <b>Não é prova de que nada se perdeu</b> —
 *     essa é outra invariante, e é a que o campo ao lado mede.
 * @param potesImobilizados token que saiu de uma carteira e não chegou em nenhuma outra, porque a
 *     missão que o segura parou. Apurado no MESMO snapshot transacional de {@code integro}, pela
 *     porta {@code missoes/api/DiagnosticoPotes}; {@code GET /admin/missoes/potes-imobilizados} diz
 *     QUAIS missões são.
 */
@Schema(description = "Resultado da conciliação entre ledger e projeções de saldo")
public record ReconciliacaoResponse(
    long carteirasVerificadas,
    boolean integro,
    List<DivergenciaResponse> divergencias,
    PotesImobilizadosResumoResponse potesImobilizados) {

  /** List.copyOf: a lista guardada é imutável e o acessor não tem o que expor. */
  public ReconciliacaoResponse {
    divergencias = List.copyOf(divergencias);
  }
}
