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
 * O painel de "quanto a tese economizou" (ADR 0029) — a única pergunta de VALOR que o sistema
 * responde.
 *
 * <p>Mora em {@code compartilhado} porque os números vêm de quatro módulos, cada um por uma porta;
 * em qualquer um dos quatro, aquele passaria a depender dos outros três.
 *
 * <p>"Re-entrega evitada" é a missão concluída RENOMEADA, não uma segunda evidência. O custo
 * evitado depende de uma premissa não medida, por isso a resposta traz a conta também em ±50%.
 */
@Service
public class ImpactoService {

  /** Fatores da análise de sensibilidade — a premissa pela metade e uma vez e meia. */
  private static final BigDecimal PESSIMISTA = new BigDecimal("0.5");

  private static final BigDecimal OTIMISTA = new BigDecimal("1.5");

  /** Quatro casas na fração: a tela mostra uma casa em porcento. */
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
   * {@code REPEATABLE_READ}: sob READ COMMITTED as seis agregações veriam snapshots diferentes e o
   * painel poderia se contradizer (ex.: concluídas > criadas). As portas rodam {@code MANDATORY}
   * para ler dentro desta transação. Read-only em REPEATABLE READ é MVCC puro — não bloqueia
   * ninguém.
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
   * Segundos do webhook ao primeiro check-in válido. Sem check-in fica FORA da amostra (não há
   * tempo a medir); delta negativo também sai — é relógio adiantado da transportadora.
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

  /** Fração, ou {@code null} sem denominador — nunca zero. */
  private static Double taxa(long numerador, long denominador) {
    if (denominador == 0) {
      return null;
    }
    return BigDecimal.valueOf(numerador)
        .divide(BigDecimal.valueOf(denominador), CASAS_TAXA, RoundingMode.HALF_UP)
        .doubleValue();
  }
}
