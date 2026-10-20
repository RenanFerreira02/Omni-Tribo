package com.omnitribo.geolocalizacao.dominio;

import com.omnitribo.geolocalizacao.api.MotivoRejeicaoCheckin;
import com.omnitribo.geolocalizacao.api.ResultadoCheckin.Veredito;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * Regras antifraude do check-in, como funções puras: rodam em milissegundos num teste e ficam
 * separadas da persistência. A distância já chega medida pelo PostGIS, nunca pelo cliente. O que
 * elas NÃO pegam está em docs/ARQUITETURA.md (Antifraude).
 */
public final class AvaliacaoAntifraude {

  // O teto de acurácia entra como PARÂMETRO (ADR 0040); a recusa cita o mesmo valor aplicado.

  /** Acima disto a cinemática é implausível para deslocamento urbano entre dois check-ins. */
  public static final BigDecimal VELOCIDADE_SUSPEITA_KMH = new BigDecimal("120");

  /**
   * Teto do que cabe em {@code velocidade_implicita_kmh} ({@code NUMERIC(10,2)}). Check-ins em
   * continentes diferentes com milissegundos de intervalo estouravam a coluna com 500. Satura em
   * vez de alargar: acima do limiar de suspeita, todo valor leva à mesma decisão.
   */
  static final BigDecimal VELOCIDADE_MAXIMA_REGISTRAVEL_KMH = new BigDecimal("99999999.99");

  static final String MOTIVO_MOCK = "Localização simulada detectada pelo dispositivo.";
  static final String MOTIVO_ACURACIA =
      "Precisão do GPS insuficiente (%s m). O máximo aceito é %s m — tente novamente a céu aberto.";
  static final String MOTIVO_DISTANCIA =
      "Você está a %s m da origem da missão; o raio permitido é %s m.";

  private AvaliacaoAntifraude() {}

  /**
   * Veredito. A causa viaja duas vezes: {@code codigoRejeicao} é estável e escolhe o {@code type}
   * (ADR 0010); {@code motivoRejeicao} é texto para humano. Ambos nulos quando aceito.
   */
  public record Avaliacao(
      Veredito veredito,
      MotivoRejeicaoCheckin codigoRejeicao,
      String motivoRejeicao,
      BigDecimal velocidadeImplicitaKmh) {

    public boolean aceito() {
      return veredito != Veredito.REJEITADO;
    }
  }

  /**
   * Aplica as regras na ordem mock → acurácia → distância → cinemática — da causa mais específica à
   * mais genérica, para o usuário ver o motivo que de fato resolve. A cinemática só MARCA (falso
   * positivo em rodovia, trem, voo); a defesa contra o caso real é a confirmação.
   *
   * @param distanciaM medida pelo PostGIS, em metros
   * @param acuraciaMaximaM teto vigente ({@code ParametrosCheckin}; padrão 50 m)
   */
  public static Avaliacao avaliar(
      BigDecimal distanciaM,
      BigDecimal acuraciaM,
      BigDecimal acuraciaMaximaM,
      boolean mocked,
      int raioCheckinM,
      BigDecimal latAnterior,
      BigDecimal lonAnterior,
      BigDecimal distanciaDoAnteriorM,
      Instant instanteAnterior,
      Instant agora) {

    BigDecimal velocidade =
        velocidadeImplicitaKmh(
            latAnterior, lonAnterior, distanciaDoAnteriorM, instanteAnterior, agora);

    if (mocked) {
      return new Avaliacao(
          Veredito.REJEITADO, MotivoRejeicaoCheckin.LOCALIZACAO_SIMULADA, MOTIVO_MOCK, velocidade);
    }

    if (acuraciaM.compareTo(acuraciaMaximaM) > 0) {
      return new Avaliacao(
          Veredito.REJEITADO,
          MotivoRejeicaoCheckin.ACURACIA_INSUFICIENTE,
          MOTIVO_ACURACIA.formatted(emMetros(acuraciaM), acuraciaMaximaM.toPlainString()),
          velocidade);
    }

    // Estrito: somar a acurácia ao raio daria ao atacante um jeito de alargar o alvo declarando um
    // fix ruim.
    if (distanciaM.compareTo(new BigDecimal(raioCheckinM)) > 0) {
      return new Avaliacao(
          Veredito.REJEITADO,
          MotivoRejeicaoCheckin.FORA_DO_RAIO,
          MOTIVO_DISTANCIA.formatted(emMetros(distanciaM), raioCheckinM),
          velocidade);
    }

    if (velocidade != null && velocidade.compareTo(VELOCIDADE_SUSPEITA_KMH) > 0) {
      return new Avaliacao(Veredito.ACEITO_SUSPEITO, null, null, velocidade);
    }

    return new Avaliacao(Veredito.ACEITO, null, null, velocidade);
  }

  /**
   * Velocidade média desde o check-in anterior. Null sem anterior (o primeiro check-in de uma conta
   * é ponto cego estrutural) e com intervalo zero ou negativo (requisição duplicada, que a
   * idempotência já cobre).
   */
  static BigDecimal velocidadeImplicitaKmh(
      BigDecimal latAnterior,
      BigDecimal lonAnterior,
      BigDecimal distanciaDoAnteriorM,
      Instant instanteAnterior,
      Instant agora) {

    if (latAnterior == null || lonAnterior == null || instanteAnterior == null) {
      return null;
    }
    if (distanciaDoAnteriorM == null) {
      return null;
    }

    // Milissegundos, não toSeconds(): truncar fazia o teleporte sub-segundo — o mais suspeito de
    // todos — sair sem velocidade nenhuma.
    long millis = Duration.between(instanteAnterior, agora).toMillis();
    if (millis <= 0) {
      // Intervalo zero só acontece em requisição duplicada (timestamptz tem microssegundos).
      return null;
    }

    // m/s → km/h num passo só: (distancia / (millis/1000)) * 3,6 = distancia * 3600 / millis
    BigDecimal velocidade =
        distanciaDoAnteriorM
            .multiply(new BigDecimal("3600"))
            .divide(new BigDecimal(millis), 2, RoundingMode.HALF_UP);

    // Satura no que a coluna comporta. Ver VELOCIDADE_MAXIMA_REGISTRAVEL_KMH.
    return velocidade.min(VELOCIDADE_MAXIMA_REGISTRAVEL_KMH);
  }

  private static String emMetros(BigDecimal valor) {
    return valor.setScale(0, RoundingMode.HALF_UP).toPlainString();
  }
}
