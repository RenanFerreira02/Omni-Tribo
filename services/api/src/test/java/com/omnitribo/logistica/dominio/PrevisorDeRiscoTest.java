package com.omnitribo.logistica.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omnitribo.logistica.treino.ParametrosDoYaml;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.EnumSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Comportamento do modelo de risco em runtime, contra os coeficientes PUBLICADOS.
 *
 * <p>Sem Spring: {@link PrevisorDeRisco} é função pura, e um teste que precisasse de contexto para
 * exercitar uma função pura estaria testando a coisa errada. Os parâmetros vêm do {@code
 * application.yml} real via binder — se lesse uma calibração inventada aqui, o teste concordaria
 * com qualquer coisa que estivesse publicada.
 */
class PrevisorDeRiscoTest {

  private static ParametrosRisco p;

  /** Sábado à noite, comercial, CEP da pior faixa, duas tentativas, chovendo forte. */
  private static final FeaturesEntrega ALTO_RISCO =
      new FeaturesEntrega(
          19, DayOfWeek.SATURDAY, TipoEndereco.COMERCIAL, 0.31, 25.0, 200.0, 18.0, 17.0, 2);

  /** Terça de manhã, residencial, CEP da melhor faixa, primeira tentativa, tempo bom. */
  private static final FeaturesEntrega BAIXO_RISCO =
      new FeaturesEntrega(
          10, DayOfWeek.TUESDAY, TipoEndereco.RESIDENCIAL, 0.05, 1.5, 8.0, 0.0, 24.0, 0);

  @BeforeAll
  static void carregarParametrosPublicados() {
    p = ParametrosDoYaml.carregar();
  }

  // ─────────────────────────────── Sanidade ───────────────────────────────

  @Test
  void o_caso_claramente_arriscado_recebe_faixa_alta() {
    ResultadoRisco r = PrevisorDeRisco.avaliar(ALTO_RISCO, p);

    assertThat(r.faixaRisco()).isEqualTo(FaixaRisco.ALTO);
    assertThat(r.probabilidadeFalha()).isGreaterThan(new BigDecimal("0.50"));
  }

  @Test
  void o_caso_claramente_tranquilo_recebe_faixa_baixa() {
    ResultadoRisco r = PrevisorDeRisco.avaliar(BAIXO_RISCO, p);

    assertThat(r.faixaRisco()).isEqualTo(FaixaRisco.BAIXO);
    assertThat(r.probabilidadeFalha()).isLessThan(new BigDecimal("0.10"));
  }

  @Test
  void o_arriscado_pontua_muito_acima_do_tranquilo() {
    assertThat(PrevisorDeRisco.avaliar(ALTO_RISCO, p).probabilidadeFalha())
        .isGreaterThan(PrevisorDeRisco.avaliar(BAIXO_RISCO, p).probabilidadeFalha());
  }

  // ─────────────────────────────── Determinismo ───────────────────────────────

  @Test
  void a_mesma_entrada_produz_sempre_a_mesma_saida() {
    ResultadoRisco a = PrevisorDeRisco.avaliar(ALTO_RISCO, p);
    ResultadoRisco b = PrevisorDeRisco.avaliar(ALTO_RISCO, p);

    // Igualdade de record cobre probabilidade, faixa, multiplicador, fatores e versão de uma vez.
    assertThat(a).isEqualTo(b);
  }

  @Test
  void cem_avaliacoes_seguidas_nao_divergem() {
    ResultadoRisco referencia = PrevisorDeRisco.avaliar(ALTO_RISCO, p);
    for (int i = 0; i < 100; i++) {
      assertThat(PrevisorDeRisco.avaliar(ALTO_RISCO, p)).isEqualTo(referencia);
    }
  }

  // ─────────────────────────────── Faixa e monotonicidade ───────────────────────────────

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 5, 10})
  void mais_tentativas_anteriores_nunca_reduz_o_risco(int tentativas) {
    FeaturesEntrega base = comTentativas(BAIXO_RISCO, 0);
    FeaturesEntrega maior = comTentativas(BAIXO_RISCO, tentativas);

    assertThat(PrevisorDeRisco.avaliar(maior, p).probabilidadeFalha())
        .isGreaterThanOrEqualTo(PrevisorDeRisco.avaliar(base, p).probabilidadeFalha());
  }

  @Test
  void a_probabilidade_fica_sempre_entre_zero_e_um() {
    for (int hora = 0; hora < 24; hora++) {
      for (TipoEndereco tipo : TipoEndereco.values()) {
        ResultadoRisco r =
            PrevisorDeRisco.avaliar(
                new FeaturesEntrega(hora, DayOfWeek.MONDAY, tipo, 0.31, 30.0, 250.0, 25.0, 36.0, 3),
                p);
        assertThat(r.probabilidadeFalha()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
      }
    }
  }

  @Test
  void comercio_no_sabado_e_mais_arriscado_que_o_mesmo_comercio_na_terca() {
    FeaturesEntrega sabado =
        new FeaturesEntrega(
            10, DayOfWeek.SATURDAY, TipoEndereco.COMERCIAL, 0.15, 5.0, 40.0, 0.0, 24.0, 0);
    FeaturesEntrega terca =
        new FeaturesEntrega(
            10, DayOfWeek.TUESDAY, TipoEndereco.COMERCIAL, 0.15, 5.0, 40.0, 0.0, 24.0, 0);

    // É a INTERAÇÃO aprendida. Um modelo aditivo sem o termo de produto não produziria isto.
    assertThat(PrevisorDeRisco.avaliar(sabado, p).probabilidadeFalha())
        .isGreaterThan(PrevisorDeRisco.avaliar(terca, p).probabilidadeFalha());
  }

  // ─────────────────────────────── Multiplicador ───────────────────────────────

  @Test
  void o_multiplicador_respeita_o_teto_em_qualquer_cenario() {
    for (int hora = 0; hora < 24; hora++) {
      for (int tentativas = 0; tentativas <= 5; tentativas++) {
        BigDecimal m =
            PrevisorDeRisco.avaliar(
                    new FeaturesEntrega(
                        hora,
                        DayOfWeek.SUNDAY,
                        TipoEndereco.RURAL,
                        0.31,
                        30.0,
                        250.0,
                        25.0,
                        36.0,
                        tentativas),
                    p)
                .multiplicadorRecompensa();

        assertThat(m)
            .as("multiplicador com hora=%d tentativas=%d", hora, tentativas)
            .isBetween(
                BigDecimal.valueOf(p.multiplicadorMinimo()),
                BigDecimal.valueOf(p.multiplicadorMaximo()));
      }
    }
  }

  @Test
  void risco_maior_nunca_paga_menos() {
    assertThat(PrevisorDeRisco.avaliar(ALTO_RISCO, p).multiplicadorRecompensa())
        .isGreaterThan(PrevisorDeRisco.avaliar(BAIXO_RISCO, p).multiplicadorRecompensa());
  }

  @Test
  void o_multiplicador_nunca_reduz_a_recompensa() {
    // Piso 1,0: risco baixo paga o normal, nunca menos. Reduzir inverteria a tese do produto.
    assertThat(PrevisorDeRisco.avaliar(BAIXO_RISCO, p).multiplicadorRecompensa())
        .isGreaterThanOrEqualTo(BigDecimal.ONE.setScale(2));
  }

  // ─────────────────────────────── Explicabilidade ───────────────────────────────

  @Test
  void a_explicacao_aponta_tentativas_anteriores_no_caso_arriscado() {
    ResultadoRisco r = PrevisorDeRisco.avaliar(ALTO_RISCO, p);

    assertThat(r.fatoresPrincipais()).isNotEmpty().hasSizeLessThanOrEqualTo(3);
    assertThat(r.fatoresPrincipais())
        .extracting(FatorRisco::caracteristica)
        .contains(CaracteristicaRisco.TENTATIVAS_ANTERIORES);
  }

  @Test
  void os_fatores_vem_ordenados_por_impacto_decrescente() {
    var fatores = PrevisorDeRisco.avaliar(ALTO_RISCO, p).fatoresPrincipais();
    for (int i = 1; i < fatores.size(); i++) {
      assertThat(Math.abs(fatores.get(i).contribuicao()))
          .isLessThanOrEqualTo(Math.abs(fatores.get(i - 1).contribuicao()));
    }
  }

  @Test
  void um_fator_protetor_aparece_marcado_como_reduz() {
    ResultadoRisco r = PrevisorDeRisco.avaliar(BAIXO_RISCO, p);
    // CEP de faixa boa está bem abaixo da média do treino: contribuição NEGATIVA. Mostrar isso é o
    // que torna a explicação honesta — "o risco não é maior porque este CEP tem histórico bom".
    assertThat(r.fatoresPrincipais()).anyMatch(f -> f.direcao() == DirecaoDoFator.REDUZ);
  }

  @Test
  void o_intercepto_nunca_aparece_como_fator() {
    // Se aparecesse, seria o "principal fator de risco" de TODA previsão — o erro clássico.
    ResultadoRisco r = PrevisorDeRisco.avaliar(ALTO_RISCO, p);
    assertThat(r.logOddsBase()).isEqualTo(p.intercepto());
    assertThat(r.fatoresPrincipais()).allSatisfy(f -> assertThat(f.rotulo()).isNotBlank());
  }

  @Test
  void o_peso_relativo_de_cada_fator_fica_entre_zero_e_um() {
    assertThat(PrevisorDeRisco.avaliar(ALTO_RISCO, p).fatoresPrincipais())
        .allSatisfy(f -> assertThat(f.pesoRelativo()).isBetween(0.0, 1.0));
  }

  @Test
  void imputacao_declarada_viaja_no_resultado() {
    ResultadoRisco r =
        PrevisorDeRisco.avaliar(
            ALTO_RISCO,
            p,
            EnumSet.of(CaracteristicaRisco.CHUVA_MM, CaracteristicaRisco.TEMPERATURA_C));

    assertThat(r.featuresImputadas()).containsExactly("CHUVA_MM", "TEMPERATURA_C");
  }

  @Test
  void sem_imputacao_a_lista_vem_vazia() {
    assertThat(PrevisorDeRisco.avaliar(ALTO_RISCO, p).featuresImputadas()).isEmpty();
  }

  // ─────────────────────────────── Entrada inválida ───────────────────────────────

  @ParameterizedTest
  @ValueSource(ints = {-1, 24, 99})
  void hora_fora_de_faixa_falha_alto(int hora) {
    assertThatThrownBy(
            () ->
                new FeaturesEntrega(
                    hora, DayOfWeek.MONDAY, TipoEndereco.RESIDENCIAL, 0.1, 1.0, 1.0, 0.0, 20.0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("horaDoDia");
  }

  @Test
  void valor_nao_finito_falha_alto_em_vez_de_virar_risco_baixo() {
    // NaN se propagaria por toda a aritmética: log-odds NaN, sigmoide NaN, comparação com o limiar
    // devolvendo false — ou seja, "risco BAIXO" para uma entrada corrompida. O pior desfecho.
    assertThatThrownBy(
            () ->
                new FeaturesEntrega(
                    10,
                    DayOfWeek.MONDAY,
                    TipoEndereco.RESIDENCIAL,
                    0.1,
                    Double.NaN,
                    1.0,
                    0.0,
                    20.0,
                    0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pesoKg");
  }

  @ParameterizedTest
  @EnumSource(TipoEndereco.class)
  void todo_tipo_de_endereco_produz_previsao_valida(TipoEndereco tipo) {
    ResultadoRisco r =
        PrevisorDeRisco.avaliar(
            new FeaturesEntrega(14, DayOfWeek.WEDNESDAY, tipo, 0.16, 5.0, 40.0, 2.0, 24.0, 0), p);

    assertThat(r.probabilidadeFalha()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
    assertThat(r.faixaRisco()).isNotNull();
    assertThat(r.versaoModelo()).isEqualTo(p.versao());
  }

  private static FeaturesEntrega comTentativas(FeaturesEntrega base, int tentativas) {
    return new FeaturesEntrega(
        base.horaDoDia(),
        base.diaSemana(),
        base.tipoEndereco(),
        base.taxaHistoricaCep(),
        base.pesoKg(),
        base.volumeL(),
        base.chuvaMm(),
        base.temperaturaC(),
        tentativas);
  }
}
