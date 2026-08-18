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
 * Move missões ABERTA com janela vencida para EXPIRADA — <b>uma transação por missão</b>.
 *
 * <p><b>Por que não um lote por transação, que era o desenho anterior.</b> Dois defeitos com a
 * mesma origem:
 *
 * <ul>
 *   <li><b>Deadlock.</b> O estorno trava as carteiras dos financiadores de CADA missão em ordem
 *       crescente de id, o que é a regra global do projeto. Mas com N missões na mesma transação, a
 *       ordenação é local por missão e não global pela transação: duas missões com financiadores
 *       cruzados faziam a transação adquirir carteiras em ordem DECRESCENTE entre si, e uma
 *       transferência P2P concorrente — que ordena certo — fechava o ciclo. PostgreSQL matava uma
 *       das duas com 40P01, e o lote inteiro se perdia. O comentário antigo dizia que "a ordem
 *       global é respeitada porque a missão já está travada": isso cobre {@code missao → carteira},
 *       não cobre {@code carteira(id crescente)}.
 *   <li><b>Item envenenado.</b> Sem {@code try/catch} por item, uma única missão cujo pote diverge
 *       da soma dos financiamentos ({@code IllegalStateException} em {@code
 *       EstornoFinanciamentoService}) derrubava a transação inteira. Todas as outras perdiam a
 *       expiração, e o job reencontrava a mesma missão a cada 5 minutos, indefinidamente.
 * </ul>
 *
 * <p><b>Isto não viola a proibição de {@code REQUIRES_NEW}.</b> A proibição existe porque a
 * transação externa segura {@code FOR UPDATE} e a interna pediria uma segunda conexão — com N ≥
 * tamanho do pool, deadlock de pool. Aqui não há transação externa: quem orquestra o laço é o
 * {@code ExpiracaoMissoesJob}, que não é transacional. Cada missão abre e fecha uma transação
 * {@code REQUIRED}, uma conexão por vez. É o mesmo formato de {@code MissaoService.aplicar} chamado
 * pelo controller.
 *
 * <p>O que se perde: um lote deixa de ser atômico. Não há regra que exija isso — a atomicidade que
 * importa é por missão (status + trilha + estorno), e ela está preservada. O que se ganha em
 * contenção: hoje um lote de 200 missões financiadas segurava centenas de linhas de {@code
 * carteira} do início ao fim.
 *
 * <p>Usa a MESMA máquina de estados do fluxo HTTP, com ator SISTEMA — não existe caminho paralelo
 * capaz de divergir das regras de transição ou de deixar de gravar a trilha.
 */
@Service
public class ExpiracaoMissoesService {

  /**
   * Candidata à expiração: o mínimo que o cursor precisa.
   *
   * <p>Não é a entidade, de propósito — a seleção roda FORA de transação e materializar {@code
   * Missao} aqui não teria persistence context onde viver.
   */
  public record Candidata(UUID id, Instant marco) {

    /**
     * Cursor do PRIMEIRO lote: menor que qualquer chave real.
     *
     * <p>Existe para que a consulta não precise de {@code :aposMarco is null}. Um parâmetro nulo
     * sem tipo declarado faz o PostgreSQL recusar a consulta com {@code could not determine data
     * type of parameter} — a mesma armadilha dos {@code CAST(:param AS ...)} das queries nativas.
     * Um sentinela resolve com um caminho só no predicado, em vez de um cast e dois ramos.
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
   * Próximas candidatas depois do cursor, sem lock e sem transação de escrita.
   *
   * <p><b>Cursor keyset, e não {@code PageRequest.of(0, limite)}.</b> Com uma transação por missão,
   * uma missão que FALHA continua {@code ABERTA} e vencida — e, como a ordem é {@code janelaFim
   * asc}, ela reapareceria na primeira posição do próximo lote. Com offset zero o job repetiria a
   * mesma falha até bater o teto por execução, e o defeito do item envenenado voltaria em outra
   * forma. O cursor garante progresso monotônico: cada candidata é tentada UMA vez por execução.
   */
  public List<Candidata> candidatas(
      RegraExpiracao regra, Instant agora, Candidata apos, int limite) {
    Candidata cursor = apos == null ? Candidata.INICIO : apos;
    // O corte é o instante ANTES do qual o marco temporal precisa estar para a missão ter vencido.
    // Para ABERTA o prazo é ZERO e o corte é o próprio `agora` — a janela de oferta já é o prazo.
    Instant corte = agora.minus(regra.prazo());
    return regra.marco() == RegraExpiracao.Marco.JANELA_FIM
        ? missaoRepository.candidatasPorJanela(
            regra.origem(), corte, cursor.marco(), cursor.id(), PageRequest.of(0, limite))
        : missaoRepository.candidatasPorEstadoDesde(
            regra.origem(), corte, cursor.marco(), cursor.id(), PageRequest.of(0, limite));
  }

  /**
   * Expira UMA missão, na própria transação.
   *
   * <p>Só para as regras que TERMINAM sem crédito (ABERTA e EM_ANDAMENTO, ambas para EXPIRADA com
   * estorno). A regra de {@code AGUARDANDO_CONFIRMACAO} conclui PAGANDO o executor e por isso passa
   * por {@code MissaoService.concluirPorOmissaoDoCriador}, que é o único caminho de crédito — o job
   * escolhe qual chamar. Duplicar o crédito aqui seria criar um segundo caminho de valor, que é
   * exatamente como a conservação da moeda se perde sem ninguém perceber.
   *
   * @return {@code false} quando a missão já não é elegível — transicionada entre a seleção e o
   *     lock, ou travada por uma operação em curso (SKIP LOCKED). Não é erro: é a corrida sendo
   *     perdida, e quem ganhou tem prioridade legítima sobre um job de manutenção.
   */
  @Transactional
  public boolean expirarUma(RegraExpiracao regra, UUID missaoId, Instant agora) {
    // PRIMEIRA leitura da transação, e é ela que fecha o deadlock: dentro desta transação a única
    // missão travada é esta, então as carteiras estornadas são só as DELA — e estornarFinanciadores
    // já as ordena por id crescente. A ordem global missao → carteira(crescente) volta a valer, o
    // que era impossível com o lote inteiro numa transação só.
    Optional<Missao> alvo = missaoRepository.travarSeAindaNoStatus(missaoId, regra.origem());
    if (alvo.isEmpty()) {
      return false;
    }

    Missao missao = alvo.get();
    MissaoEvento trilha =
        MissaoStateMachine.transicionar(missao, regra.evento(), AtorMissao.sistema(), null, agora);

    // Estorno obrigatoriamente AQUI, e não só em MissaoService.aplicar. Este é o ÚNICO caminho que
    // leva uma missão a EXPIRADA — o job dispara EXPIRAR sem passar por aplicar(). Sem esta chamada
    // os tokens de quem financiou ficariam presos numa missão morta, e o pior: a reconciliação
    // continuaria respondendo integro=true, porque ledger e projeção seguem batendo. A perda seria
    // invisível justamente para o endpoint que existe para achá-la.
    if (missao.getPoteTokens() > 0) {
      estornoFinanciamentoService.estornarPote(missao, agora);
    }

    missaoRepository.save(missao);
    // Mesma transação: status e trilha não têm como divergir.
    missaoEventoRepository.save(trilha);

    // Este serviço NÃO passa por MissaoService.aplicar. Sem este gancho, missões ABERTA→EXPIRADA
    // continuariam aparecendo no radar por até 30 s. Chamado uma vez por missão agora; o
    // invalidateAll é idempotente e o cache tem TTL de 30 s e maximumSize, então repetir não custa.
    cacheMissoesProximas.invalidarAposCommit();
    return true;
  }
}
