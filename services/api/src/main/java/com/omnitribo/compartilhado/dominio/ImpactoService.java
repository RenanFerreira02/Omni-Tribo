package com.omnitribo.compartilhado.dominio;

import com.omnitribo.carteira.api.EstatisticasToken;
import com.omnitribo.carteira.api.ResumoToken;
import com.omnitribo.compartilhado.api.ImpactoResponse;
import com.omnitribo.geolocalizacao.api.ConsultaPrimeiroCheckin;
import com.omnitribo.logistica.api.EstatisticasEntregasFalidas;
import com.omnitribo.logistica.api.ResumoEntregasFalidas;
import com.omnitribo.missoes.api.EstatisticasMissoes;
import com.omnitribo.missoes.api.ResumoMissoesDoSistema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * O painel que responde "quanto a tese economizou" — a única pergunta de VALOR que o sistema
 * responde, ao lado de todas as de integridade.
 *
 * <h2>Por que mora em {@code compartilhado}</h2>
 *
 * <p>Os números vêm de quatro módulos: {@code entrega_falida} ({@code logistica}), {@code missao}
 * ({@code missoes}), {@code checkin} ({@code geolocalizacao}) e {@code lancamento}/{@code carteira}
 * ({@code carteira}). Um serviço central lendo as quatro tabelas teria de alcançar {@code dominio}
 * e {@code infra} alheios, que o ArchUnit proíbe; e hospedá-lo em qualquer um dos quatro faria esse
 * módulo depender dos outros três por causa de um relatório. Aqui o acoplamento aponta todo para
 * {@code compartilhado}, que já é dependência de todos por desenho — mesmo arranjo de {@code
 * DrenadorOutboxService}, que injeta {@code notificacoes/api} pela porta. Ver ADR 0029.
 *
 * <h2>A honestidade é parte do requisito, não um comentário</h2>
 *
 * <p><b>"Re-entrega evitada" é a missão concluída, renomeada.</b> Não são duas evidências: são a
 * mesma contagem sob a interpretação de que a encomenda teria sido re-entregue se ninguém a tivesse
 * retirado. Apresentá-las como confirmação mútua seria a fraude estatística mais fácil de cometer
 * neste painel, e é por isso que o nome do campo e o javadoc dizem o contrário.
 *
 * <p><b>O custo evitado depende de uma premissa que este projeto não mediu</b> ({@link
 * ParametrosImpacto}). Por isso a resposta traz a mesma conta com a premissa em ±50%: a afirmação
 * defensável não é o número, é a ORDEM DE GRANDEZA que sobrevive à faixa.
 */
@Service
public class ImpactoService {

  /** Fatores da análise de sensibilidade — a premissa pela metade e uma vez e meia. */
  private static final BigDecimal PESSIMISTA = new BigDecimal("0.5");

  private static final BigDecimal OTIMISTA = new BigDecimal("1.5");

  /**
   * Casas na fração das taxas. Quatro porque a tela exibe uma casa em porcento (57,8%), e
   * arredondar já aqui para duas jogaria fora o dígito que a formatação vai usar.
   */
  private static final int CASAS_TAXA = 4;

  private final EstatisticasEntregasFalidas estatisticasEntregasFalidas;
  private final EstatisticasMissoes estatisticasMissoes;
  private final ConsultaPrimeiroCheckin consultaPrimeiroCheckin;
  private final EstatisticasToken estatisticasToken;
  private final ParametrosImpacto parametros;
  private final Clock clock;

  public ImpactoService(
      EstatisticasEntregasFalidas estatisticasEntregasFalidas,
      EstatisticasMissoes estatisticasMissoes,
      ConsultaPrimeiroCheckin consultaPrimeiroCheckin,
      EstatisticasToken estatisticasToken,
      ParametrosImpacto parametros,
      Clock clock) {
    this.estatisticasEntregasFalidas = estatisticasEntregasFalidas;
    this.estatisticasMissoes = estatisticasMissoes;
    this.consultaPrimeiroCheckin = consultaPrimeiroCheckin;
    this.estatisticasToken = estatisticasToken;
    this.parametros = parametros;
    this.clock = clock;
  }

  /**
   * {@code REPEATABLE_READ}, e é a decisão menos óbvia desta classe.
   *
   * <p>São seis agregações em quatro módulos, e sob READ COMMITTED <b>cada statement enxerga um
   * snapshot próprio</b>. Um resgate acontecendo entre a leitura do ledger e a dos saldos
   * apareceria queimado no total e ainda presente na carteira; uma conclusão entre duas contagens
   * daria {@code concluidas > criadas}. Seriam incoerências aritméticas na tela causadas só pelo
   * instante da leitura, e um painel que se contradiz é pior que um painel ausente — ele destrói a
   * confiança nos números que ESTÃO certos.
   *
   * <p>{@code ReconciliacaoRepository} resolve o mesmo problema espremendo tudo numa statement só.
   * Aqui não dá: as consultas moram em módulos diferentes e juntá-las seria o join cruzando a
   * fronteira. Então o snapshot vem do nível de isolamento, e as portas rodam {@code MANDATORY}
   * para garantir que leiam DENTRO desta transação.
   *
   * <p>Custo: nenhum bloqueio. No PostgreSQL uma transação read-only em REPEATABLE READ é MVCC puro
   * — não trava escritor nenhum, e não pode sofrer erro de serialização porque não escreve.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ImpactoResponse apurar() {
    ResumoEntregasFalidas entregas = estatisticasEntregasFalidas.resumo();
    ResumoMissoesDoSistema missoes = estatisticasMissoes.resumoDoSistema();
    ResumoToken token = estatisticasToken.resumo();
    long emPotes = estatisticasMissoes.tokensEmPotes();

    List<Long> amostra = amostraDeResposta();
    OptionalLong mediana = Mediana.de(amostra);

    return new ImpactoResponse(
        clock.instant(),
        new ImpactoResponse.EntregasFalidas(
            entregas.recebidas(),
            entregas.convertidas(),
            entregas.pendentes(),
            entregas.recusadasPontoLotado(),
            entregas.recusadasSemPatrocinio(),
            taxa(entregas.convertidas(), entregas.recebidas())),
        new ImpactoResponse.MissoesDeRetirada(
            missoes.criadas(),
            missoes.concluidas(),
            taxa(missoes.concluidas(), missoes.criadas()),
            mediana.isPresent() ? mediana.getAsLong() : null,
            amostra.size()),
        custoEvitado(missoes.concluidas()),
        new ImpactoResponse.Tokens(
            token.aportados(),
            token.emCarteiras(),
            emPotes,
            token.emCarteiras() + emPotes,
            token.resgatados()));
  }

  /**
   * Segundos entre o webhook e o primeiro check-in válido, uma entrada por missão que teve os dois.
   *
   * <p>Missão sem check-in fica FORA da amostra em vez de entrar com valor alto: ninguém apareceu,
   * então não há tempo de resposta a medir — atribuir-lhe um número inventaria a medição que falta,
   * e é o oposto do que este painel existe para fazer.
   *
   * <p><b>Delta negativo também sai.</b> {@code recebido_em} é o relógio da TRANSPORTADORA, não o
   * nosso: um relógio adiantado produz check-in "antes" do webhook. Somar isso à amostra
   * introduziria tempo de resposta negativo, que é ruído de terceiro e não fato do bairro.
   */
  private List<Long> amostraDeResposta() {
    Map<UUID, Instant> recebimento = estatisticasEntregasFalidas.recebimentoPorMissao();
    Map<UUID, Instant> primeiroCheckin =
        consultaPrimeiroCheckin.primeiroCheckinValido(recebimento.keySet());

    List<Long> segundos = new ArrayList<>(primeiroCheckin.size());
    for (Map.Entry<UUID, Instant> entrada : primeiroCheckin.entrySet()) {
      Instant webhook = recebimento.get(entrada.getKey());
      if (webhook == null) {
        continue;
      }
      long delta = Duration.between(webhook, entrada.getValue()).toSeconds();
      if (delta >= 0) {
        segundos.add(delta);
      }
    }
    return segundos;
  }

  private ImpactoResponse.CustoEvitado custoEvitado(long reentregasEvitadas) {
    BigDecimal premissa = parametros.custoReentregaBrl().setScale(2, RoundingMode.HALF_UP);
    BigDecimal quantidade = BigDecimal.valueOf(reentregasEvitadas);

    return new ImpactoResponse.CustoEvitado(
        reentregasEvitadas,
        premissa,
        premissa.multiply(quantidade).setScale(2, RoundingMode.HALF_UP),
        premissa.multiply(PESSIMISTA).multiply(quantidade).setScale(2, RoundingMode.HALF_UP),
        premissa.multiply(OTIMISTA).multiply(quantidade).setScale(2, RoundingMode.HALF_UP));
  }

  /**
   * Fração, ou {@code null} quando não há denominador — nunca zero. Ver {@code EntregasFalidas}.
   */
  private static Double taxa(long numerador, long denominador) {
    if (denominador == 0) {
      return null;
    }
    return BigDecimal.valueOf(numerador)
        .divide(BigDecimal.valueOf(denominador), CASAS_TAXA, RoundingMode.HALF_UP)
        .doubleValue();
  }
}
