package com.omnitribo.missoes.dominio;

import com.omnitribo.missoes.infra.CacheMissoesProximas;
import com.omnitribo.missoes.infra.MissaoEventoRepository;
import com.omnitribo.missoes.infra.MissaoRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Move missões ABERTA com janela vencida para EXPIRADA. */
@Service
public class ExpiracaoMissoesService {

  private final MissaoRepository missaoRepository;
  private final MissaoEventoRepository missaoEventoRepository;
  private final CacheMissoesProximas cacheMissoesProximas;

  public ExpiracaoMissoesService(
      MissaoRepository missaoRepository,
      MissaoEventoRepository missaoEventoRepository,
      CacheMissoesProximas cacheMissoesProximas) {
    this.missaoRepository = missaoRepository;
    this.missaoEventoRepository = missaoEventoRepository;
    this.cacheMissoesProximas = cacheMissoesProximas;
  }

  /**
   * Expira um lote e devolve quantas missões foram afetadas.
   *
   * <p>Em lotes, cada um em sua própria transação: uma transação única sobre "todas as vencidas"
   * cresceria sem limite, seguraria locks de linha por minutos e perderia o trabalho inteiro num
   * único erro.
   *
   * <p>Usa a MESMA máquina de estados do fluxo HTTP, com ator SISTEMA — não existe caminho paralelo
   * capaz de divergir das regras de transição ou de deixar de gravar a trilha.
   *
   * @return quantidade de missões expiradas neste lote
   */
  @Transactional
  public int expirarLote(int limite) {
    Instant agora = Instant.now();
    List<Missao> vencidas =
        missaoRepository.buscarAbertasVencidas(agora, PageRequest.of(0, limite));

    if (vencidas.isEmpty()) {
      return 0;
    }

    List<MissaoEvento> trilha = new ArrayList<>(vencidas.size());
    for (Missao missao : vencidas) {
      trilha.add(
          MissaoStateMachine.transicionar(
              missao, EventoMissao.EXPIRAR, AtorMissao.sistema(), null, agora));
    }

    missaoRepository.saveAll(vencidas);
    // Mesma transação do lote: status e trilha não têm como divergir.
    missaoEventoRepository.saveAll(trilha);

    // Este serviço NÃO passa por MissaoService.aplicar — chama a máquina de estados direto. Sem
    // este gancho, missões ABERTA→EXPIRADA continuariam aparecendo no radar por até 30 s.
    cacheMissoesProximas.invalidarAposCommit();

    return vencidas.size();
  }
}
