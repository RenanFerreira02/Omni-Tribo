package com.omnitribo.compartilhado.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnitribo.carteira.api.EstatisticasToken;
import com.omnitribo.carteira.api.ResumoToken;
import com.omnitribo.compartilhado.api.ImpactoResponse;
import com.omnitribo.geolocalizacao.api.ConsultaPrimeiroCheckin;
import com.omnitribo.logistica.api.EstatisticasEntregasFalidas;
import com.omnitribo.logistica.api.ResumoEntregasFalidas;
import com.omnitribo.missoes.api.EstatisticasMissoes;
import com.omnitribo.missoes.api.ResumoMissoesDoSistema;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A ARITMÉTICA do painel, com as quatro portas dubladas — sem Spring e sem banco.
 *
 * <p>Separado de {@code ImpactoTest} de propósito. Aquele prova que os números vêm do banco certo;
 * este prova o que o serviço FAZ com eles, incluindo os casos de borda que um banco de teste
 * dificilmente produz na hora certa: denominador zero, amostra vazia e relógio de transportadora
 * adiantado.
 */
@DisplayName("ImpactoService")
class ImpactoServiceTest {

  private static final Instant AGORA = Instant.parse("2026-08-23T12:00:00Z");
  private static final UUID MISSAO_A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
  private static final UUID MISSAO_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
  private static final UUID MISSAO_C = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

  @Test
  @DisplayName("taxa com denominador zero é NULA, nunca 0%")
  void taxaSemDenominadorEhNula() {
    // "0% de conversão" afirma desempenho ruim. Com zero entregas recebidas não há desempenho
    // nenhum a relatar, e o painel que exibisse 0% estaria mentindo sobre um sistema
    // recém-instalado.
    ImpactoResponse r = servico(vazio(), new ResumoMissoesDoSistema(0, 0), Map.of(), Map.of());

    assertThat(r.entregasFalidas().taxaConversao()).isNull();
    assertThat(r.missoesDeRetirada().taxaConclusao()).isNull();
  }

  @Test
  @DisplayName("as duas taxas do funil saem da mesma tabela do numerador")
  void taxasDoFunil() {
    ImpactoResponse r =
        servico(
            new ResumoEntregasFalidas(20, 15, 0, 3, 2),
            new ResumoMissoesDoSistema(15, 9),
            Map.of(),
            Map.of());

    assertThat(r.entregasFalidas().taxaConversao()).isEqualTo(0.75);
    // 9/15 — denominador é `criadas`, contado em missao junto do numerador. Usar `convertidas`,
    // contado em entrega_falida, deixaria a taxa passar de 1 se os dois módulos divergissem.
    assertThat(r.missoesDeRetirada().taxaConclusao()).isEqualTo(0.6);
  }

  @Test
  @DisplayName("sensibilidade: metade e uma vez e meia da premissa, em cima do MESMO total")
  void sensibilidadeDaPremissa() {
    ImpactoResponse r =
        servico(
            new ResumoEntregasFalidas(10, 8, 0, 1, 1),
            new ResumoMissoesDoSistema(8, 5),
            Map.of(),
            Map.of());

    ImpactoResponse.CustoEvitado custo = r.custoEvitado();
    assertThat(custo.reentregasEvitadas()).isEqualTo(5);
    // A premissa é ecoada: sem ela na resposta, quem lê supõe que o fator foi medido.
    assertThat(custo.premissaCustoReentregaBrl()).isEqualByComparingTo("25.00");
    assertThat(custo.baseBrl()).isEqualByComparingTo("125.00");
    assertThat(custo.menos50Brl()).isEqualByComparingTo("62.50");
    assertThat(custo.mais50Brl()).isEqualByComparingTo("187.50");
  }

  @Test
  @DisplayName("re-entrega evitada é a missão concluída — o mesmo número, não uma segunda medição")
  void reentregaEvitadaEhOMesmoNumero() {
    ImpactoResponse r =
        servico(
            new ResumoEntregasFalidas(10, 8, 0, 1, 1),
            new ResumoMissoesDoSistema(8, 5),
            Map.of(),
            Map.of());

    assertThat(r.custoEvitado().reentregasEvitadas())
        .as("se um dia divergirem, o painel passou a apresentar uma interpretação como evidência")
        .isEqualTo(r.missoesDeRetirada().concluidas());
  }

  @Test
  @DisplayName("mediana: missão sem check-in fica FORA da amostra, não entra com valor alto")
  void semCheckinNaoEntraNaAmostra() {
    Map<UUID, Instant> recebimento = ordenado(MISSAO_A, AGORA, MISSAO_B, AGORA, MISSAO_C, AGORA);
    // Só A e B tiveram check-in; C está convertida e parada.
    Map<UUID, Instant> checkins =
        ordenado(MISSAO_A, AGORA.plusSeconds(600), MISSAO_B, AGORA.plusSeconds(1800));

    ImpactoResponse r =
        servico(
            new ResumoEntregasFalidas(3, 3, 0, 0, 0),
            new ResumoMissoesDoSistema(3, 2),
            recebimento,
            checkins);

    assertThat(r.missoesDeRetirada().amostraMediana()).isEqualTo(2);
    assertThat(r.missoesDeRetirada().medianaAteCheckinSegundos()).isEqualTo(1200L);
  }

  @Test
  @DisplayName("check-in ANTES do webhook sai da amostra — o relógio é da transportadora")
  void deltaNegativoSaiDaAmostra() {
    // recebido_em vem do corpo do webhook, ou seja, do relógio de um terceiro. Adiantado, produz
    // check-in "antes" da falha. Somar isso introduziria tempo de resposta negativo na mediana.
    Map<UUID, Instant> recebimento = ordenado(MISSAO_A, AGORA, MISSAO_B, AGORA.plusSeconds(9_000));
    Map<UUID, Instant> checkins =
        ordenado(MISSAO_A, AGORA.plusSeconds(300), MISSAO_B, AGORA.plusSeconds(60));

    ImpactoResponse r =
        servico(
            new ResumoEntregasFalidas(2, 2, 0, 0, 0),
            new ResumoMissoesDoSistema(2, 2),
            recebimento,
            checkins);

    assertThat(r.missoesDeRetirada().amostraMediana()).isEqualTo(1);
    assertThat(r.missoesDeRetirada().medianaAteCheckinSegundos()).isEqualTo(300L);
  }

  @Test
  @DisplayName("amostra vazia deixa a mediana nula, com amostra 0")
  void medianaVazia() {
    ImpactoResponse r =
        servico(
            new ResumoEntregasFalidas(1, 0, 0, 1, 0),
            new ResumoMissoesDoSistema(0, 0),
            Map.of(),
            Map.of());

    assertThat(r.missoesDeRetirada().medianaAteCheckinSegundos()).isNull();
    assertThat(r.missoesDeRetirada().amostraMediana()).isZero();
  }

  @Test
  @DisplayName("circulação é carteiras + potes — o pote não sai da economia")
  void circulacaoSomaOsPotes() {
    ImpactoResponse r = servico(vazio(), new ResumoMissoesDoSistema(0, 0), Map.of(), Map.of());

    ImpactoResponse.Tokens t = r.tokens();
    assertThat(t.emCarteiras()).isEqualTo(38_200);
    assertThat(t.emPotes()).isEqualTo(1_200);
    assertThat(t.emCirculacao()).isEqualTo(39_400);
    assertThat(t.aportados()).isEqualTo(40_000);
    assertThat(t.resgatados()).isEqualTo(600);
  }

  // ─── Montagem ───────────────────────────────────────────────────────────────────────────────

  private static ResumoEntregasFalidas vazio() {
    return new ResumoEntregasFalidas(0, 0, 0, 0, 0);
  }

  /** {@code Map.of} randomiza a ordem a cada JVM; a mediana é sensível a conjunto, não a ordem — */
  /* mas um teste que varia de execução para execução é pior que um teste a menos. */
  private static Map<UUID, Instant> ordenado(Object... paresChaveValor) {
    Map<UUID, Instant> mapa = new LinkedHashMap<>();
    for (int i = 0; i < paresChaveValor.length; i += 2) {
      mapa.put((UUID) paresChaveValor[i], (Instant) paresChaveValor[i + 1]);
    }
    return mapa;
  }

  private static ImpactoResponse servico(
      ResumoEntregasFalidas entregas,
      ResumoMissoesDoSistema missoes,
      Map<UUID, Instant> recebimento,
      Map<UUID, Instant> checkins) {

    EstatisticasEntregasFalidas portaEntregas =
        new EstatisticasEntregasFalidas() {
          @Override
          public ResumoEntregasFalidas resumo() {
            return entregas;
          }

          @Override
          public Map<UUID, Instant> recebimentoPorMissao() {
            return recebimento;
          }
        };

    EstatisticasMissoes portaMissoes =
        new EstatisticasMissoes() {
          @Override
          public ResumoMissoesDoSistema resumoDoSistema() {
            return missoes;
          }

          @Override
          public long tokensEmPotes() {
            return 1_200;
          }
        };

    ConsultaPrimeiroCheckin portaCheckin =
        (Collection<UUID> ids) -> {
          Map<UUID, Instant> recorte = new LinkedHashMap<>();
          checkins.forEach(
              (missaoId, quando) -> {
                if (ids.contains(missaoId)) {
                  recorte.put(missaoId, quando);
                }
              });
          return recorte;
        };

    EstatisticasToken portaToken = () -> new ResumoToken(40_000, 600, 38_200);

    return new ImpactoService(
            portaEntregas,
            portaMissoes,
            portaCheckin,
            portaToken,
            new ParametrosImpacto(new BigDecimal("25.00")),
            Clock.fixed(AGORA, ZoneOffset.UTC))
        .apurar();
  }
}
