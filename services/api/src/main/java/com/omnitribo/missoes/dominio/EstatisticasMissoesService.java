package com.omnitribo.missoes.dominio;

import com.omnitribo.identidade.api.UsuarioSistema;
import com.omnitribo.missoes.api.EstatisticasMissoes;
import com.omnitribo.missoes.api.ResumoMissoesDoSistema;
import com.omnitribo.missoes.infra.MissaoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementação de {@link EstatisticasMissoes} (ADR 0029).
 *
 * <p>Separada de {@code MissaoService} de propósito: aquele é o serviço da máquina de estados, com
 * locks, eventos e crédito, e o painel não tem nada a ver com nenhum dos três. Injeta só o
 * repositório — nenhuma chamada a outro serviço, logo nenhum ciclo de bean possível.
 */
@Service
public class EstatisticasMissoesService implements EstatisticasMissoes {

  private final MissaoRepository missaoRepository;

  public EstatisticasMissoesService(MissaoRepository missaoRepository) {
    this.missaoRepository = missaoRepository;
  }

  /** {@code MANDATORY} pelo mesmo motivo das outras portas do painel: snapshot compartilhado. */
  @Override
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public ResumoMissoesDoSistema resumoDoSistema() {
    MissaoRepository.ResumoSistemaProjecao r = missaoRepository.contarDoSistema(UsuarioSistema.ID);
    return new ResumoMissoesDoSistema(r.getCriadas(), r.getConcluidas());
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public long tokensEmPotes() {
    return missaoRepository.somarPotes();
  }
}
