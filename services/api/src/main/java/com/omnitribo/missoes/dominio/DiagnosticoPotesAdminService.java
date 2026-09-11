package com.omnitribo.missoes.dominio;

import com.omnitribo.compartilhado.api.PaginaResponse;
import com.omnitribo.missoes.api.DiagnosticoPotes;
import com.omnitribo.missoes.api.PoteImobilizadoResponse;
import com.omnitribo.missoes.api.PotesImobilizadosResponse;
import com.omnitribo.missoes.api.ResumoPotesImobilizados;
import java.time.Clock;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compõe resumo e página do diagnóstico numa transação só, para o endpoint de ADMIN.
 *
 * <h2>Por que existe, em vez de o controller chamar a porta duas vezes</h2>
 *
 * <p>Porque a soma e a lista precisam vir do MESMO snapshot. Em duas transações separadas, um
 * estorno commitado entre elas publicaria um corpo que nunca existiu: "3 missões, 450 tokens" ao
 * lado de uma lista com duas linhas. O relatório acusaria a si mesmo de inconsistência, e quem
 * lesse iria procurar o defeito no lugar errado. É a mesma razão pela qual {@code
 * ReconciliacaoRepository} espreme divergências e saldos numa statement só.
 *
 * <p>Isso também é o que faz {@code DiagnosticoPotesService.resumir()} ser {@code MANDATORY}: ele
 * recusa rodar fora de uma transação de quem o chama, aqui e em {@code ReconciliacaoService}.
 *
 * <p>O {@code Clock} é lido UMA vez por requisição e serve às duas metades: o corte da consulta e a
 * idade de cada linha. Duas leituras do relógio dariam uma missão listada com {@code horasParada}
 * abaixo do limiar que a selecionou — diferença de milissegundos, e ainda assim um número que se
 * contradiz na mesma resposta.
 */
@Service
public class DiagnosticoPotesAdminService {

  private final DiagnosticoPotes diagnosticoPotes;
  private final Clock clock;

  public DiagnosticoPotesAdminService(DiagnosticoPotes diagnosticoPotes, Clock clock) {
    this.diagnosticoPotes = diagnosticoPotes;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PotesImobilizadosResponse apurar(Pageable paginacao) {
    Instant agora = clock.instant();
    ResumoPotesImobilizados resumo = diagnosticoPotes.resumir();
    return new PotesImobilizadosResponse(
        resumo,
        PaginaResponse.de(
            diagnosticoPotes
                .listar(paginacao)
                .map(pote -> PoteImobilizadoResponse.de(pote, agora))));
  }
}
