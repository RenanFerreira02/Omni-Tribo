package com.omnitribo.carteira.dominio;

import com.omnitribo.carteira.api.DivergenciaResponse;
import com.omnitribo.carteira.api.PotesImobilizadosResumoResponse;
import com.omnitribo.carteira.api.ReconciliacaoResponse;
import com.omnitribo.carteira.infra.ReconciliacaoRepository;
import com.omnitribo.missoes.api.DiagnosticoPotes;
import com.omnitribo.missoes.api.ResumoPotesImobilizados;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Confere que a projeção {@code carteira.saldo_*} bate com a soma do ledger, em toda carteira.
 *
 * <p>É a verificação que transforma "o ledger é a verdade" de afirmação em fato observável. Se
 * alguma operação escrever saldo sem lançamento — ou lançamento sem saldo — esta consulta acusa, e
 * é por isso que ela roda ao final de todo teste de concorrência: 100 threads disputando a mesma
 * linha e as somas ainda fechando é a evidência de que a atomicidade não é só teórica.
 *
 * <h2>E o que ela NÃO prova, agora publicado ao lado</h2>
 *
 * <p>Reconciliar não é conservar. Um pote imobilizado — token que saiu de uma carteira, entrou numa
 * missão e nunca chegou em carteira nenhuma porque a missão parou — mantém esta consulta
 * perfeitamente verde, porque ledger e projeção continuam batendo em toda carteira envolvida. O
 * projeto tropeçou nessa distinção três vezes (o estorno na expiração, a cunhagem de ENTREGA, a
 * queima do resgate) e por dois anos a resposta a ela foi um parágrafo de documentação.
 *
 * <p>Desde o ADR 0032 é um campo. {@code potesImobilizados} vem da porta {@link DiagnosticoPotes},
 * apurado DENTRO desta mesma transação read-only — {@code resumir()} é {@code MANDATORY} e recusa
 * abrir uma própria, justamente para que o par publicado tenha existido junto em algum instante.
 *
 * <p>A travessia {@code carteira → missoes.api} é legítima e não fecha ciclo de bean: a
 * implementação da porta injeta só o repositório de {@code missoes}, e nada em {@code missoes}
 * injeta este serviço. Mesmo arranjo de {@code ImpactoService}.
 */
@Service
public class ReconciliacaoService {

  private final ReconciliacaoRepository reconciliacaoRepository;
  private final DiagnosticoPotes diagnosticoPotes;

  public ReconciliacaoService(
      ReconciliacaoRepository reconciliacaoRepository, DiagnosticoPotes diagnosticoPotes) {
    this.reconciliacaoRepository = reconciliacaoRepository;
    this.diagnosticoPotes = diagnosticoPotes;
  }

  @Transactional(readOnly = true)
  public ReconciliacaoResponse conciliar() {
    List<DivergenciaResponse> divergencias =
        reconciliacaoRepository.buscarDivergencias().stream()
            .map(
                d ->
                    new DivergenciaResponse(
                        d.getCarteiraId(),
                        d.getUsuarioId(),
                        d.getSaldoBrlRegistrado(),
                        d.getSaldoBrlLedger(),
                        d.getSaldoTokensRegistrado(),
                        d.getSaldoTokensLedger()))
            .toList();

    ResumoPotesImobilizados potes = diagnosticoPotes.resumir();

    return new ReconciliacaoResponse(
        reconciliacaoRepository.contarCarteiras(),
        // `integro` continua sendo SÓ sobre divergência ledger × projeção. Um pote imobilizado não
        // o derruba, e fazê-lo derrubar trocaria a única pergunta que este endpoint responde com
        // precisão por uma resposta ruim a duas.
        divergencias.isEmpty(),
        divergencias,
        new PotesImobilizadosResumoResponse(potes.missoes(), potes.tokens(), potes.limiar()));
  }
}
