package com.omnitribo.missoes.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omnitribo.missoes.dominio.CalculadoraDeRecompensa.Insumos;
import com.omnitribo.missoes.dominio.CalculadoraDeRecompensa.Recompensa;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** Sem Spring: a fórmula de recompensa é função pura. */
class CalculadoraDeRecompensaTest {

  /**
   * Espelha o bloco {@code app.missoes.recompensa} do application.yml.
   *
   * <p>Duplicado de propósito, e não lido do YAML: o teste dourado abaixo tem de falhar quando a
   * calibração de produção mudar. Se lesse o mesmo arquivo, concordaria com qualquer mudança —
   * exatamente o defeito que {@code MissaoStateMachineTest} evita ao escrever a tabela de
   * transições à mão em vez de derivá-la do enum.
   */
  private static final ParametrosRecompensa V1 =
      new ParametrosRecompensa(
          1,
          Map.of(
              CategoriaMissao.ENTREGA, 20L,
              CategoriaMissao.COLETA, 20L,
              CategoriaMissao.TRIBO, 25L,
              CategoriaMissao.AJUDA, 20L),
          Map.of(
              ComplexidadeMissao.LEVE, new BigDecimal("1.0"),
              ComplexidadeMissao.MEDIA, new BigDecimal("1.5"),
              ComplexidadeMissao.PESADA, new BigDecimal("2.0")),
          new BigDecimal("2"),
          new BigDecimal("0.5"),
          new BigDecimal("5"),
          1000L,
          3,
          5000,
          new BigDecimal("5"),
          new BigDecimal("20"),
          new BigDecimal("25"),
          new BigDecimal("80"));

  private static Insumos insumos(
      CategoriaMissao categoria, String peso, String volume, Double distanciaM) {
    return new Insumos(
        categoria,
        null,
        peso == null ? null : new BigDecimal(peso),
        volume == null ? null : new BigDecimal(volume),
        distanciaM);
  }

  // ─── Dourado ────────────────────────────────────────────────────────────────────────────────

  /**
   * Fixa a saída da calibração v1 para um caso conhecido.
   *
   * <p><b>Este teste existe para FALHAR quando alguém mexer nos parâmetros.</b> Se ele quebrou e a
   * mudança foi intencional, o conserto não é ajustar o número aqui: é subir {@code versao} no
   * application.yml e nesta constante, junto. Sem isso, {@code missao.versao_formula} vira
   * decoração — missões antigas passariam a ser explicadas por uma fórmula que não as produziu.
   */
  @Test
  void douradoV1() {
    // ENTREGA · 10 kg · 40 L · 3 km  →  MEDIA (10>5 ou 40>20; ambos ≤ 25/80)
    //   base 20 × 1.5 = 30 · +3 km × 2 = 6 · +10 kg × 0.5 = 5 · +0.4 × 5 = 2   → 43
    Recompensa r =
        CalculadoraDeRecompensa.calcular(insumos(CategoriaMissao.ENTREGA, "10", "40", 3000.0), V1);

    assertThat(r.complexidade()).isEqualTo(ComplexidadeMissao.MEDIA);
    assertThat(r.tokens()).isEqualTo(43L);
    assertThat(r.xp()).isEqualTo(129); // 43 × 3
    assertThat(r.versaoFormula()).isEqualTo(1);
  }

  // ─── Determinismo ───────────────────────────────────────────────────────────────────────────

  @Test
  void mesmaEntradaProduzSempreAMesmaSaida() {
    Insumos i = insumos(CategoriaMissao.COLETA, "12.5", "33.3", 1234.5);

    Recompensa a = CalculadoraDeRecompensa.calcular(i, V1);
    Recompensa b = CalculadoraDeRecompensa.calcular(i, V1);

    // Igualdade de record: cobre tokens, xp, complexidade e versão de uma vez.
    assertThat(a).isEqualTo(b);
  }

  // ─── Faixa ──────────────────────────────────────────────────────────────────────────────────

  @Test
  void recompensaSempreDentroDaFaixaEmMilharesDeCombinacoes() {
    for (CategoriaMissao cat : CategoriaMissao.values()) {
      for (int peso = 0; peso <= 200; peso += 7) {
        for (int volume = 0; volume <= 500; volume += 23) {
          for (int metros = 0; metros <= 60_000; metros += 7_000) {
            Recompensa r =
                CalculadoraDeRecompensa.calcular(
                    insumos(cat, String.valueOf(peso), String.valueOf(volume), (double) metros),
                    V1);

            assertThat(r.tokens())
                .as("tokens em %s peso=%d volume=%d m=%d", cat, peso, volume, metros)
                .isBetween(1L, V1.tetoTokens());
            assertThat(r.xp())
                .as("xp em %s peso=%d volume=%d m=%d", cat, peso, volume, metros)
                .isBetween(1, V1.tetoXp());
          }
        }
      }
    }
  }

  // ─── Monotonicidade ─────────────────────────────────────────────────────────────────────────

  @Test
  void aumentarPesoNuncaReduzARecompensa() {
    long anterior = 0;
    for (int peso = 0; peso <= 300; peso++) {
      long tokens =
          CalculadoraDeRecompensa.calcular(
                  insumos(CategoriaMissao.ENTREGA, String.valueOf(peso), "10", 1000.0), V1)
              .tokens();
      assertThat(tokens)
          .as("peso=%d não pode valer menos que peso=%d", peso, peso - 1)
          .isGreaterThanOrEqualTo(anterior);
      anterior = tokens;
    }
  }

  @Test
  void aumentarVolumeNuncaReduzARecompensa() {
    long anterior = 0;
    for (int volume = 0; volume <= 600; volume += 2) {
      long tokens =
          CalculadoraDeRecompensa.calcular(
                  insumos(CategoriaMissao.ENTREGA, "1", String.valueOf(volume), 1000.0), V1)
              .tokens();
      assertThat(tokens).as("volume=%d", volume).isGreaterThanOrEqualTo(anterior);
      anterior = tokens;
    }
  }

  @Test
  void aumentarDistanciaNuncaReduzARecompensa() {
    long anterior = 0;
    for (int metros = 0; metros <= 50_000; metros += 250) {
      long tokens =
          CalculadoraDeRecompensa.calcular(
                  insumos(CategoriaMissao.ENTREGA, "5", "10", (double) metros), V1)
              .tokens();
      assertThat(tokens).as("metros=%d", metros).isGreaterThanOrEqualTo(anterior);
      anterior = tokens;
    }
  }

  @Test
  void complexidadeMaiorNuncaReduzARecompensa() {
    // Sem peso e volume, a complexidade declarada é o único fator variável.
    long leve = declarada(ComplexidadeMissao.LEVE);
    long media = declarada(ComplexidadeMissao.MEDIA);
    long pesada = declarada(ComplexidadeMissao.PESADA);

    assertThat(leve).isLessThanOrEqualTo(media);
    assertThat(media).isLessThanOrEqualTo(pesada);
  }

  private static long declarada(ComplexidadeMissao complexidade) {
    return CalculadoraDeRecompensa.calcular(
            new Insumos(CategoriaMissao.TRIBO, complexidade, null, null, null), V1)
        .tokens();
  }

  // ─── Teto ───────────────────────────────────────────────────────────────────────────────────

  @Test
  void insumosExtremosSaturamNoTetoSemEstourar() {
    Recompensa r =
        CalculadoraDeRecompensa.calcular(
            insumos(CategoriaMissao.TRIBO, "999999", "999999", 40_000_000.0), V1);

    assertThat(r.tokens()).isEqualTo(V1.tetoTokens());
    // 1000 tokens × 3 = 3000 XP — abaixo do teto de 5000. Quem satura aqui é o teto de TOKENS, e o
    // de XP é consequência dele. Afirmar 5000 mascararia o fato de os dois tetos não se tocarem
    // nesta calibração.
    assertThat(r.xp()).isEqualTo(3000);
  }

  @Test
  void naCalibracaoV1QuemLimitaEhOTetoDeTokensENaoODeXp() {
    // Os dois tetos não se tocam hoje: no máximo de tokens, o XP fica em 3000 contra um teto de
    // 5000. Se `xp-por-token` subisse para 6, o teto de XP passaria a cortar antes, e a recompensa
    // deixaria de ser proporcional aos tokens no topo da faixa — este teste é o que avisaria.
    Recompensa r =
        CalculadoraDeRecompensa.calcular(
            insumos(CategoriaMissao.TRIBO, "999999", "999999", 40_000_000.0), V1);
    assertThat((long) r.tokens() * V1.xpPorToken()).isLessThanOrEqualTo(V1.tetoXp());
  }

  // ─── Derivação de complexidade ──────────────────────────────────────────────────────────────

  @ParameterizedTest(name = "peso={0} volume={1} → {2}")
  @CsvSource({
    "0,     0,   LEVE", // missão sem carga declarada como zero
    "5,    20,   LEVE", // fronteira exata: ambos NO limite ainda é leve
    "5.01, 20,   MEDIA", // um grama acima já sobe
    "5,    20.1, MEDIA", // o volume sozinho decide
    "25,   80,   MEDIA", // fronteira exata da faixa média
    "25.1, 80,   PESADA",
    "25,   80.1, PESADA",
    "1,   500,   PESADA", // isopor: pesa nada, não cabe em moto — o MAIOR dos dois decide
    "300,   1,   PESADA"
  })
  void derivacaoRespeitaAsFronteiras(String peso, String volume, ComplexidadeMissao esperada) {
    assertThat(
            CalculadoraDeRecompensa.derivarComplexidade(
                new BigDecimal(peso), new BigDecimal(volume), V1))
        .isEqualTo(esperada);
  }

  @ParameterizedTest
  @EnumSource(ComplexidadeMissao.class)
  void comPesoEVolumeADerivacaoVenceADeclaracao(ComplexidadeMissao declarada) {
    // Carga leve declarada como PESADA continua LEVE: dado objetivo ganha de declaração. É o que
    // impede a complexidade de virar o mesmo arbítrio que a recompensa livre era, com três degraus.
    Insumos i =
        new Insumos(
            CategoriaMissao.ENTREGA, declarada, new BigDecimal("1"), new BigDecimal("2"), null);

    assertThat(CalculadoraDeRecompensa.complexidadeEfetiva(i, V1))
        .isEqualTo(ComplexidadeMissao.LEVE);
  }

  @Test
  void semPesoEVolumeADeclaracaoEhRespeitada() {
    Insumos i = new Insumos(CategoriaMissao.TRIBO, ComplexidadeMissao.PESADA, null, null, null);

    assertThat(CalculadoraDeRecompensa.complexidadeEfetiva(i, V1))
        .isEqualTo(ComplexidadeMissao.PESADA);
  }

  // ─── Ausência de insumos ────────────────────────────────────────────────────────────────────

  @Test
  void semDistanciaOAdicionalDeDeslocamentoEhZero() {
    // TRIBO nunca tem destino — a fórmula precisa funcionar sem esse insumo, não falhar.
    long semDistancia =
        CalculadoraDeRecompensa.calcular(
                new Insumos(CategoriaMissao.TRIBO, ComplexidadeMissao.LEVE, null, null, null), V1)
            .tokens();

    assertThat(semDistancia).isEqualTo(25L); // base TRIBO 25 × 1.0, sem adicional algum
  }

  @Test
  void categoriaSemCalibracaoFalhaAltoEmVezDeProduzirZero() {
    ParametrosRecompensa incompleta =
        new ParametrosRecompensa(
            1,
            Map.of(CategoriaMissao.ENTREGA, 20L), // faltam as outras três
            V1.multiplicadorComplexidade(),
            V1.tokensPorKm(),
            V1.tokensPorKg(),
            V1.tokensPorCemLitros(),
            V1.tetoTokens(),
            V1.xpPorToken(),
            V1.tetoXp(),
            V1.pesoLeveAteKg(),
            V1.volumeLeveAteL(),
            V1.pesoMediaAteKg(),
            V1.volumeMediaAteL());

    assertThatThrownBy(
            () ->
                CalculadoraDeRecompensa.calcular(
                    insumos(CategoriaMissao.TRIBO, "1", "1", null), incompleta))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TRIBO");
  }
}
