package com.omnitribo.missoes.dominio;

import com.omnitribo.missoes.api.DiagnosticoPotes;
import com.omnitribo.missoes.api.PoteImobilizado;
import com.omnitribo.missoes.api.ResumoPotesImobilizados;
import com.omnitribo.missoes.infra.MissaoRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementação de {@link DiagnosticoPotes} — o instrumento que a CONSERVAÇÃO não tinha (ADR 0032).
 *
 * <p>Separada de {@code MissaoService} pela mesma razão de {@code EstatisticasMissoesService}:
 * aquele é o serviço da máquina de estados, com locks, eventos e crédito, e diagnóstico não tem
 * nada a ver com nenhum dos três. Injeta só o repositório e o relógio — nenhuma chamada a outro
 * serviço, logo nenhum ciclo de bean possível, o que importa porque {@code carteira} passa a
 * injetar esta porta e {@code missoes} já injeta portas de {@code carteira}.
 *
 * <h2>Este serviço não corrige nada</h2>
 *
 * <p>Ele é DETECTIVO. Quem tira a missão do limbo continua sendo a varredura por prazo ({@code
 * ExpiracaoMissoesService}) e a porta de ADMIN ({@code POST /missoes/{id}/destravar}) do ADR 0015 —
 * e as duas juntas alcançam apenas ABERTA, EM_ANDAMENTO e AGUARDANDO_CONFIRMACAO. RASCUNHO, ACEITA
 * e EM_DISPUTA podem reter pote e NÃO têm varredura; RASCUNHO e ACEITA não têm nem porta de ADMIN.
 * Esta lista os mostra assim mesmo, com {@code varreduraCobre = false}: um diagnóstico que
 * escondesse o que ainda não se sabe consertar seria a repetição do erro que o ADR 0015 cometeu ao
 * declarar uma visibilidade inexistente.
 */
@Service
public class DiagnosticoPotesService implements DiagnosticoPotes {

  /**
   * Estados de onde nenhuma transição parte — derivados do enum, nunca listados à mão.
   *
   * <p>{@code ehTerminal()} lê o mapa de transições, então acrescentar um estado terminal novo em
   * {@code StatusMissao} o exclui daqui sozinho. Uma lista literal precisaria ser lembrada, e é
   * exatamente o tipo de lembrança que este projeto já viu falhar.
   */
  private static final Set<StatusMissao> TERMINAIS =
      Arrays.stream(StatusMissao.values())
          .filter(StatusMissao::ehTerminal)
          .collect(Collectors.toUnmodifiableSet());

  private final MissaoRepository missaoRepository;
  private final Clock clock;
  private final Duration limiar;
  private final Set<StatusMissao> comVarredura;

  public DiagnosticoPotesService(
      MissaoRepository missaoRepository,
      Clock clock,
      @Value("${app.missoes.diagnostico.pote-imobilizado-apos:PT96H}") Duration limiar,
      @Value("${app.missoes.expiracao.prazo-execucao:PT48H}") Duration prazoExecucao,
      @Value("${app.missoes.expiracao.prazo-confirmacao:PT72H}") Duration prazoConfirmacao) {
    this.missaoRepository = missaoRepository;
    this.clock = clock;
    this.limiar = limiar;
    // Quais estados a varredura alcança sai da MESMA lista que o job varre, montada com os MESMOS
    // prazos que ele lê. Uma regra nova em RegraExpiracao.padrao passa a ser refletida aqui sem que
    // ninguém edite esta classe — e, o que importa mais, sem que o diagnóstico continue afirmando
    // "não há varredura para este estado" depois que passou a haver.
    this.comVarredura =
        RegraExpiracao.padrao(prazoExecucao, prazoConfirmacao).stream()
            .map(RegraExpiracao::origem)
            .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * {@code MANDATORY} de propósito: o chamador é {@code ReconciliacaoService}, e o número precisa
   * vir do MESMO snapshot que produziu {@code integro}. Numa transação própria, um estorno
   * commitado entre as duas leituras publicaria um par que nunca existiu junto.
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public ResumoPotesImobilizados resumir() {
    ContagemPotes contagem =
        missaoRepository.contarPotesImobilizados(
            TERMINAIS, RegraExpiracao.medidosPorJanelaFim(), corte());
    return new ResumoPotesImobilizados(contagem.missoes(), contagem.tokens(), limiar.toString());
  }

  @Override
  @Transactional(readOnly = true)
  public Page<PoteImobilizado> listar(Pageable paginacao) {
    return missaoRepository
        .buscarPotesImobilizados(
            TERMINAIS, RegraExpiracao.medidosPorJanelaFim(), corte(), paginacao)
        .map(
            missao ->
                new PoteImobilizado(
                    missao.getId(),
                    missao.getStatus(),
                    missao.getCategoria(),
                    missao.getFontePote(),
                    missao.getPoteTokens(),
                    missao.getTokensRecompensa(),
                    marcoDe(missao),
                    comVarredura.contains(missao.getStatus())));
  }

  private Instant corte() {
    return clock.instant().minus(limiar);
  }

  /** A mesma escolha de coluna que a consulta faz no banco — e ela precisa concordar com aquela. */
  private static Instant marcoDe(Missao missao) {
    return RegraExpiracao.medidosPorJanelaFim().contains(missao.getStatus())
        ? missao.getJanelaFim()
        : missao.getEstadoDesde();
  }

  /**
   * Contagem e soma dos potes imobilizados, numa statement. Projeção de construtor, no molde de
   * {@code ExpiracaoMissoesService.Candidata} — sem entidade no caminho.
   */
  public record ContagemPotes(long missoes, long tokens) {}
}
