package com.omnitribo.carteira.dominio;

import com.omnitribo.carteira.api.DivergenciaResponse;
import com.omnitribo.carteira.api.ReconciliacaoResponse;
import com.omnitribo.carteira.infra.ReconciliacaoRepository;
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
 */
@Service
public class ReconciliacaoService {

  private final ReconciliacaoRepository reconciliacaoRepository;

  public ReconciliacaoService(ReconciliacaoRepository reconciliacaoRepository) {
    this.reconciliacaoRepository = reconciliacaoRepository;
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

    return new ReconciliacaoResponse(
        reconciliacaoRepository.contarCarteiras(), divergencias.isEmpty(), divergencias);
  }
}
