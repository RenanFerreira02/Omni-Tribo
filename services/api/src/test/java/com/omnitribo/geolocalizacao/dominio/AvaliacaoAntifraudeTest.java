package com.omnitribo.geolocalizacao.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnitribo.geolocalizacao.api.ResultadoCheckin.Veredito;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/** Matriz de regras antifraude. Sem Spring, sem container — como MissaoStateMachineTest. */
class AvaliacaoAntifraudeTest {

  private static final Instant AGORA = Instant.parse("2026-08-07T12:00:00Z");
  private static final int RAIO_50 = 50;
  private static final BigDecimal ACURACIA_BOA = new BigDecimal("10");

  private static final BigDecimal LAT_ANTERIOR = new BigDecimal("-23.5629");
  private static final BigDecimal LON_ANTERIOR = new BigDecimal("-46.6996");

  // ─── Limite exato do raio ──────────────────────────────────────────────────────────────────

  @Test
  void a_49_metros_de_um_raio_de_50_aceita() {
    assertThat(semAnterior(new BigDecimal("49"), ACURACIA_BOA, false).veredito())
        .isEqualTo(Veredito.ACEITO);
  }

  @Test
  void exatamente_no_raio_aceita() {
    // Fronteira inclusiva: 50 m num raio de 50 m está dentro. Uma comparação com >= aqui
    // rejeitaria quem está exatamente no ponto, que é o caso mais correto possível.
    assertThat(semAnterior(new BigDecimal("50"), ACURACIA_BOA, false).veredito())
        .isEqualTo(Veredito.ACEITO);
  }

  @Test
  void a_51_metros_de_um_raio_de_50_rejeita_informando_a_distancia() {
    AvaliacaoAntifraude.Avaliacao avaliacao =
        semAnterior(new BigDecimal("51"), ACURACIA_BOA, false);

    assertThat(avaliacao.veredito()).isEqualTo(Veredito.REJEITADO);
    // A distância medida vai na mensagem de propósito: o atacante já a conhece (escolheu a
    // coordenada), e sem ela o usuário legítimo não tem como saber se anda 5 m ou 500 m.
    assertThat(avaliacao.motivoRejeicao()).contains("51").contains("50");
  }

  // ─── Acurácia ──────────────────────────────────────────────────────────────────────────────

  @Test
  void acuracia_de_51_metros_rejeita_mesmo_dentro_do_raio() {
    AvaliacaoAntifraude.Avaliacao avaliacao =
        semAnterior(new BigDecimal("10"), new BigDecimal("51"), false);

    assertThat(avaliacao.veredito()).isEqualTo(Veredito.REJEITADO);
    assertThat(avaliacao.motivoRejeicao()).contains("Precisão");
  }

  @Test
  void acuracia_de_exatamente_50_metros_aceita() {
    assertThat(semAnterior(new BigDecimal("10"), new BigDecimal("50"), false).veredito())
        .isEqualTo(Veredito.ACEITO);
  }

  // ─── Mock location ─────────────────────────────────────────────────────────────────────────

  @Test
  void mocked_rejeita_mesmo_dentro_do_raio_e_com_boa_acuracia() {
    AvaliacaoAntifraude.Avaliacao avaliacao = semAnterior(new BigDecimal("5"), ACURACIA_BOA, true);

    assertThat(avaliacao.veredito()).isEqualTo(Veredito.REJEITADO);
    assertThat(avaliacao.motivoRejeicao()).isEqualTo(AvaliacaoAntifraude.MOTIVO_MOCK);
  }

  /**
   * Ordem determinística: quando mock e distância falham juntos, o motivo é o mock. Sem essa
   * garantia a mensagem dependeria da ordem dos ifs e mudaria numa refatoração inocente — e o
   * usuário que esqueceu o mock ligado seria mandado caminhar até o local para falhar de novo.
   */
  @Test
  void mocked_tem_precedencia_sobre_distancia_e_sobre_acuracia() {
    AvaliacaoAntifraude.Avaliacao avaliacao =
        semAnterior(new BigDecimal("9999"), new BigDecimal("999"), true);

    assertThat(avaliacao.motivoRejeicao()).isEqualTo(AvaliacaoAntifraude.MOTIVO_MOCK);
  }

  @Test
  void acuracia_tem_precedencia_sobre_distancia() {
    AvaliacaoAntifraude.Avaliacao avaliacao =
        semAnterior(new BigDecimal("9999"), new BigDecimal("999"), false);

    assertThat(avaliacao.motivoRejeicao()).contains("Precisão");
  }

  // ─── Cinemática ────────────────────────────────────────────────────────────────────────────

  @Test
  void velocidade_acima_de_120_marca_suspeito_e_NAO_rejeita() {
    // 10 km em 60 s = 600 km/h.
    AvaliacaoAntifraude.Avaliacao avaliacao =
        AvaliacaoAntifraude.avaliar(
            new BigDecimal("10"),
            ACURACIA_BOA,
            false,
            RAIO_50,
            LAT_ANTERIOR,
            LON_ANTERIOR,
            new BigDecimal("10000"),
            AGORA.minus(60, ChronoUnit.SECONDS),
            AGORA);

    assertThat(avaliacao.veredito()).isEqualTo(Veredito.ACEITO_SUSPEITO);
    assertThat(avaliacao.aceito()).isTrue();
    assertThat(avaliacao.velocidadeImplicitaKmh()).isEqualByComparingTo(new BigDecimal("600.00"));
    // Suspeito não é rejeitado: motivoRejeicao continua nulo, senão "motivo_rejeicao IS NOT NULL"
    // deixaria de significar "rejeitado" em toda consulta de fraude escrita depois.
    assertThat(avaliacao.motivoRejeicao()).isNull();
  }

  @Test
  void velocidade_plausivel_de_carro_urbano_nao_marca_suspeito() {
    // 1 km em 120 s = 30 km/h.
    AvaliacaoAntifraude.Avaliacao avaliacao =
        AvaliacaoAntifraude.avaliar(
            new BigDecimal("10"),
            ACURACIA_BOA,
            false,
            RAIO_50,
            LAT_ANTERIOR,
            LON_ANTERIOR,
            new BigDecimal("1000"),
            AGORA.minus(120, ChronoUnit.SECONDS),
            AGORA);

    assertThat(avaliacao.veredito()).isEqualTo(Veredito.ACEITO);
    assertThat(avaliacao.velocidadeImplicitaKmh()).isEqualByComparingTo(new BigDecimal("30.00"));
  }

  @Test
  void primeiro_checkin_do_usuario_nao_tem_velocidade_e_e_aceito() {
    AvaliacaoAntifraude.Avaliacao avaliacao =
        semAnterior(new BigDecimal("10"), ACURACIA_BOA, false);

    assertThat(avaliacao.veredito()).isEqualTo(Veredito.ACEITO);
    assertThat(avaliacao.velocidadeImplicitaKmh()).isNull();
  }

  /**
   * Regressão do pior ponto cego que a fase teve: com Duration.toSeconds(), que trunca, dois
   * check-ins a menos de um segundo davam zero e saíam SEM velocidade — o deslocamento mais
   * implausível que existe era justamente o único não sinalizado. Cálculo em milissegundos.
   */
  @Test
  void teleporte_em_menos_de_um_segundo_e_marcado_como_suspeito() {
    // 100 m em 200 ms = 1 800 km/h. Sub-segundo, mas longe de saturar a coluna: este teste é sobre
    // o truncamento, e o estouro numérico tem teste próprio abaixo.
    AvaliacaoAntifraude.Avaliacao avaliacao =
        AvaliacaoAntifraude.avaliar(
            new BigDecimal("10"),
            ACURACIA_BOA,
            false,
            RAIO_50,
            LAT_ANTERIOR,
            LON_ANTERIOR,
            new BigDecimal("100"),
            AGORA.minus(200, ChronoUnit.MILLIS),
            AGORA);

    assertThat(avaliacao.veredito()).isEqualTo(Veredito.ACEITO_SUSPEITO);
    assertThat(avaliacao.velocidadeImplicitaKmh()).isEqualByComparingTo(new BigDecimal("1800.00"));
  }

  /**
   * Meio segundo a pé continua plausível: 1 m em 500 ms = 7,2 km/h. Sub-segundo não é suspeito por
   * si.
   */
  @Test
  void deslocamento_curto_em_menos_de_um_segundo_nao_e_suspeito() {
    AvaliacaoAntifraude.Avaliacao avaliacao =
        AvaliacaoAntifraude.avaliar(
            new BigDecimal("10"),
            ACURACIA_BOA,
            false,
            RAIO_50,
            LAT_ANTERIOR,
            LON_ANTERIOR,
            new BigDecimal("1"),
            AGORA.minus(500, ChronoUnit.MILLIS),
            AGORA);

    assertThat(avaliacao.veredito()).isEqualTo(Veredito.ACEITO);
    assertThat(avaliacao.velocidadeImplicitaKmh()).isEqualByComparingTo(new BigDecimal("7.20"));
  }

  /**
   * velocidade_implicita_kmh é NUMERIC(10,2). Sem saturar, o teleporte intercontinental em
   * milissegundos estourava a coluna e derrubava o check-in com 500 — a tentativa mais gritante era
   * a única que quebrava em vez de ser sinalizada.
   */
  @Test
  void velocidade_absurda_satura_no_maximo_que_a_coluna_comporta() {
    // Manaus → São Paulo (2 700 km) em 50 ms = 194 400 000 km/h, acima dos 8 dígitos inteiros da
    // coluna. O intervalo não é hipotético: é a ordem de grandeza entre duas requisições HTTP
    // seguidas, e foi assim que o teste de integração derrubou o check-in com 500.
    AvaliacaoAntifraude.Avaliacao avaliacao =
        AvaliacaoAntifraude.avaliar(
            new BigDecimal("10"),
            ACURACIA_BOA,
            false,
            RAIO_50,
            LAT_ANTERIOR,
            LON_ANTERIOR,
            new BigDecimal("2700000"),
            AGORA.minus(50, ChronoUnit.MILLIS),
            AGORA);

    assertThat(avaliacao.veredito()).isEqualTo(Veredito.ACEITO_SUSPEITO);
    assertThat(avaliacao.velocidadeImplicitaKmh())
        .isEqualByComparingTo(AvaliacaoAntifraude.VELOCIDADE_MAXIMA_REGISTRAVEL_KMH);
    // Precisão da coluna: 8 dígitos inteiros + 2 decimais.
    assertThat(avaliacao.velocidadeImplicitaKmh().precision()).isLessThanOrEqualTo(10);
    assertThat(avaliacao.velocidadeImplicitaKmh().scale()).isLessThanOrEqualTo(2);
  }

  @Test
  void intervalo_zero_entre_checkins_nao_divide_por_zero() {
    AvaliacaoAntifraude.Avaliacao avaliacao =
        AvaliacaoAntifraude.avaliar(
            new BigDecimal("10"),
            ACURACIA_BOA,
            false,
            RAIO_50,
            LAT_ANTERIOR,
            LON_ANTERIOR,
            new BigDecimal("5000"),
            AGORA,
            AGORA);

    assertThat(avaliacao.velocidadeImplicitaKmh()).isNull();
    assertThat(avaliacao.veredito()).isEqualTo(Veredito.ACEITO);
  }

  /**
   * A velocidade é calculada ANTES de qualquer rejeição, e gravada mesmo quando o check-in é
   * recusado por outra regra. É o que preserva o sinal de teleporte na trilha de auditoria de
   * tentativas malsucedidas.
   */
  @Test
  void velocidade_e_calculada_mesmo_quando_o_checkin_e_rejeitado() {
    AvaliacaoAntifraude.Avaliacao avaliacao =
        AvaliacaoAntifraude.avaliar(
            new BigDecimal("9999"),
            ACURACIA_BOA,
            false,
            RAIO_50,
            LAT_ANTERIOR,
            LON_ANTERIOR,
            new BigDecimal("10000"),
            AGORA.minus(60, ChronoUnit.SECONDS),
            AGORA);

    assertThat(avaliacao.veredito()).isEqualTo(Veredito.REJEITADO);
    assertThat(avaliacao.velocidadeImplicitaKmh()).isEqualByComparingTo(new BigDecimal("600.00"));
  }

  // ─── Apoio ─────────────────────────────────────────────────────────────────────────────────

  private static AvaliacaoAntifraude.Avaliacao semAnterior(
      BigDecimal distanciaM, BigDecimal acuraciaM, boolean mocked) {
    return AvaliacaoAntifraude.avaliar(
        distanciaM, acuraciaM, mocked, RAIO_50, null, null, null, null, AGORA);
  }
}
