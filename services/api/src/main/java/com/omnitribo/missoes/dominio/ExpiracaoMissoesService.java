package com.omnitribo.missoes.dominio;

import com.omnitribo.missoes.infra.CacheMissoesProximas;
import com.omnitribo.missoes.infra.MissaoEventoRepository;
import com.omnitribo.missoes.infra.MissaoRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expira missões vencidas — <b>uma transação por missão</b> (ADR 0014).
 *
 * <p>Um lote numa transação só tinha dois defeitos: deadlock (financiadores cruzados faziam as
 * carteiras serem travadas fora da ordem crescente, e uma transferência concorrente fechava o
 * ciclo) e item envenenado (uma missão com pote incoerente derrubava o lote inteiro, a cada
 * execução).
 *
 * <p>Não viola a proibição de {@code REQUIRES_NEW}: não há transação externa — quem orquestra é o
 * job, que não é transacional, e cada missão abre uma {@code REQUIRED}. Usa a mesma máquina de
 * estados do fluxo HTTP, com ator SISTEMA.
 */
@Service
public class ExpiracaoMissoesService {

  /**
   * Candidata à expiração: o mínimo para o cursor. Não é a entidade — a seleção roda fora de
   * transação.
   */
  public record Candidata(UUID id, Instant marco) {

    /**
     * Cursor do primeiro lote, menor que qualquer chave real: evita {@code :x is null} sem tipo.
     */
    static final Candidata INICIO = new Candidata(new UUID(0L, 0L), Instant.EPOCH);
  }

  private final MissaoRepository missaoRepository;
  private final MissaoEventoRepository missaoEventoRepository;
  private final CacheMissoesProximas cacheMissoesProximas;
  private final EstornoFinanciamentoService estornoFinanciamentoService;

  public ExpiracaoMissoesService(
      MissaoRepository missaoRepository,
      MissaoEventoRepository missaoEventoRepository,
      CacheMissoesProximas cacheMissoesProximas,
      EstornoFinanciamentoService estornoFinanciamentoService) {
    this.missaoRepository = missaoRepository;
    this.missaoEventoRepository = missaoEventoRepository;
    this.cacheMissoesProximas = cacheMissoesProximas;
    this.estornoFinanciamentoService = estornoFinanciamentoService;
  }

  /**
   * Próximas candidatas depois do cursor, sem lock. Keyset e não offset: uma missão que falha
   * continua vencida e, com offset, voltaria ao topo de todo lote. Cada candidata é tentada uma vez
   * por execução.
   */
  public List<Candidata> candidatas(
      RegraExpiracao regra, Instant agora, Candidata apos, int limite) {
    Candidata cursor = apos == null ? Candidata.INICIO : apos;
    // Corte: o marco precisa estar antes dele. Para ABERTA o prazo é zero — a janela já é o prazo.
    Instant corte = agora.minus(regra.prazo());
    return regra.marco() == RegraExpiracao.Marco.JANELA_FIM
        ? missaoRepository.candidatasPorJanela(
            regra.origem(), corte, cursor.marco(), cursor.id(), PageRequest.of(0, limite))
        : missaoRepository.candidatasPorEstadoDesde(
            regra.origem(), corte, cursor.marco(), cursor.id(), PageRequest.of(0, limite));
  }

  /**
   * Expira UMA missão, na própria transação — só as regras que terminam SEM crédito. A de
   * AGUARDANDO_CONFIRMACAO paga o executor e vai por {@code
   * MissaoService.concluirPorOmissaoDoCriador}, o único caminho de crédito.
   *
   * @return {@code false} quando a missão já não é elegível (mudou, ou SKIP LOCKED): é a corrida
   *     perdida para uma operação legítima, não erro.
   */
  @Transactional
  public boolean expirarUma(RegraExpiracao regra, UUID missaoId, Instant agora) {
    // Primeira leitura e única missão travada: as carteiras estornadas são só as dela, em ordem
    // crescente — a ordem global volta a valer.
    Optional<Missao> alvo = missaoRepository.travarSeAindaNoStatus(missaoId, regra.origem());
    if (alvo.isEmpty()) {
      return false;
    }

    Missao missao = alvo.get();
    MissaoEvento trilha =
        MissaoStateMachine.transicionar(missao, regra.evento(), AtorMissao.sistema(), null, agora);

    // Estorno OBRIGATÓRIO aqui: este é o único caminho até EXPIRADA, e não passa por aplicar(). Sem
    // ele o token ficaria preso e a reconciliação continuaria dizendo integro=true.
    if (missao.getPoteTokens() > 0) {
      estornoFinanciamentoService.estornarPote(missao, agora);
    }

    missaoRepository.save(missao);
    // Mesma transação: status e trilha não têm como divergir.
    missaoEventoRepository.save(trilha);

    // Não passa por aplicar(): sem isto a missão expirada seguiria no radar até o TTL do cache.
    cacheMissoesProximas.invalidarAposCommit();
    return true;
  }
}
