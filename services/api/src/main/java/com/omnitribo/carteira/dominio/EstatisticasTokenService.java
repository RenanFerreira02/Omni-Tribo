package com.omnitribo.carteira.dominio;

import com.omnitribo.carteira.api.EstatisticasToken;
import com.omnitribo.carteira.api.ResumoToken;
import com.omnitribo.carteira.infra.LancamentoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Implementação de {@link EstatisticasToken} (ADR 0029). */
@Service
public class EstatisticasTokenService implements EstatisticasToken {

  private final LancamentoRepository lancamentoRepository;

  public EstatisticasTokenService(LancamentoRepository lancamentoRepository) {
    this.lancamentoRepository = lancamentoRepository;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public ResumoToken resumo() {
    LancamentoRepository.ResumoTokenProjecao r = lancamentoRepository.resumirTokens();
    return new ResumoToken(r.getAportados(), r.getResgatados(), r.getEmCarteiras());
  }
}
