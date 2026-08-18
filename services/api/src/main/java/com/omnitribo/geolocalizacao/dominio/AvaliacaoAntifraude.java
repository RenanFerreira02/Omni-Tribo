package com.omnitribo.geolocalizacao.dominio;

import com.omnitribo.geolocalizacao.api.LimitesCheckin;
import com.omnitribo.geolocalizacao.api.MotivoRejeicaoCheckin;
import com.omnitribo.geolocalizacao.api.ResultadoCheckin.Veredito;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * Regras antifraude do check-in, como funções puras.
 *
 * <p>Sem Spring e sem banco, pelo mesmo motivo de MissaoStateMachine: a matriz de regras roda em
 * milissegundos num teste unitário, e a decisão fica separada da persistência. O que entra já foi
 * medido no servidor — a distância vem do PostGIS, nunca do cliente.
 *
 * <p>O que estas regras NÃO conseguem pegar está documentado em
 * docs/seguranca/antifraude-geolocalizacao.md, e não é pouco.
 */
public final class AvaliacaoAntifraude {

  // O teto de acurácia mora em LimitesCheckin, em api/, porque o controller de `missoes` precisa
  // dele para explicar a recusa ao usuário e não pode importar `geolocalizacao.dominio`. Referido
  // direto de lá: um alias aqui daria dois nomes ao mesmo número, e o dia em que um fosse alterado
  // sem o outro a mensagem de erro passaria a citar um limite diferente do aplicado.

  /** Acima disto a cinemática é implausível para deslocamento urbano entre dois check-ins. */
  public static final BigDecimal VELOCIDADE_SUSPEITA_KMH = new BigDecimal("120");

  /**
   * Teto do que cabe em {@code velocidade_implicita_kmh}, que é {@code NUMERIC(10,2)} — oito
   * dígitos inteiros.
   *
   * <p>Existe porque o caso extremo é alcançável de verdade: dois check-ins em continentes
   * diferentes com poucos milissegundos entre eles dão dezenas de bilhões de km/h e estouravam a
   * coluna, derrubando o check-in com 500 em vez de sinalizá-lo. A ironia é que era justamente a
   * tentativa de fraude mais gritante que quebrava.
   *
   * <p>Saturar em vez de alargar a coluna porque este número é SINAL, não medida: qualquer valor
   * acima de {@link #VELOCIDADE_SUSPEITA_KMH} leva à mesma decisão, e distinguir 100 milhões de 32
   * bilhões de km/h não muda nada para quem for revisar a suspeita.
   */
  static final BigDecimal VELOCIDADE_MAXIMA_REGISTRAVEL_KMH = new BigDecimal("99999999.99");

  static final String MOTIVO_MOCK = "Localização simulada detectada pelo dispositivo.";
  static final String MOTIVO_ACURACIA =
      "Precisão do GPS insuficiente (%s m). O máximo aceito é %s m — tente novamente a céu aberto.";
  static final String MOTIVO_DISTANCIA =
      "Você está a %s m da origem da missão; o raio permitido é %s m.";

  private AvaliacaoAntifraude() {}

  /**
   * Veredito de um check-in.
   *
   * <p>A causa da rejeição viaja DUAS vezes, de propósito: {@code codigoRejeicao} é a forma estável
   * que escolhe o {@code type} da resposta e vai para a coluna homônima; {@code motivoRejeicao} é o
   * texto para humano, que muda com a copy. Ver ADR 0010. Os dois são nulos quando aceito.
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
   * Aplica as regras na ordem: mock → acurácia → distância → cinemática.
   *
   * <p>A ordem é fixa e faz parte do contrato, porque define qual motivo o usuário vê quando mais
   * de uma regra falha ao mesmo tempo. Vai da causa mais específica para a mais genérica: quem
   * envia mock location precisa saber que foi isso, e não que "está longe" — a segunda mensagem o
   * levaria a caminhar até o local com o mock ainda ligado e falhar de novo.
   *
   * <p>A cinemática é a única que não rejeita. Velocidade implausível MARCA e deixa passar, porque
   * o falso positivo é real e frequente (rodovia, trem, voo) e porque a defesa efetiva contra o
   * caso verdadeiro é a confirmação humana do criador — que existe e é a transição {@code
   * CONFIRMAR} de {@code AGUARDANDO_CONFIRMACAO}.
   *
   * @param distanciaM distância medida pelo PostGIS, em metros — nunca informada pelo cliente
   * @param pontoAnterior coordenada do check-in imediatamente anterior deste usuário, ou null
   */
  public static Avaliacao avaliar(
      BigDecimal distanciaM,
      BigDecimal acuraciaM,
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

    if (acuraciaM.compareTo(LimitesCheckin.ACURACIA_MAXIMA_M) > 0) {
      return new Avaliacao(
          Veredito.REJEITADO,
          MotivoRejeicaoCheckin.ACURACIA_INSUFICIENTE,
          MOTIVO_ACURACIA.formatted(
              emMetros(acuraciaM), LimitesCheckin.ACURACIA_MAXIMA_M.toPlainString()),
          velocidade);
    }

    // Estrito, sem tolerância pela acurácia informada. Somar a acurácia ao raio daria ao atacante
    // um parâmetro para alargar o próprio alvo: basta declarar um fix ruim. O corte em 50 m acima
    // já protege o usuário legítimo de baixa qualidade de sinal.
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
   * Velocidade média implícita entre o check-in anterior e este.
   *
   * <p>Devolve null quando não há anterior — no primeiro check-in do usuário não há contra o que
   * comparar, e essa é uma cegueira estrutural: a primeira falsificação de uma conta nova nunca
   * dispara esta regra.
   *
   * <p>Também devolve null quando o intervalo é zero ou negativo, em vez de dividir por zero. Dois
   * check-ins no mesmo instante não são deslocamento mensurável; a idempotência já cobre o caso de
   * requisição duplicada.
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

    // Milissegundos, NÃO Duration.toSeconds(). toSeconds() trunca, e o truncamento tinha o efeito
    // exatamente invertido do que esta regra existe para produzir: dois check-ins separados por
    // menos de um segundo davam zero, caíam no guard abaixo e saíam sem velocidade nenhuma. Ou
    // seja, o deslocamento MAIS implausível que existe — teleporte sub-segundo, típico de script —
    // era o único que não gerava suspeita, enquanto o usuário lento de verdade gerava.
    long millis = Duration.between(instanteAnterior, agora).toMillis();
    if (millis <= 0) {
      // Intervalo zero ou negativo não é deslocamento mensurável. Não vale tratar como "velocidade
      // infinita": timestamptz tem precisão de microssegundo, então zero exato só acontece em
      // requisição duplicada — e para essa a chave de idempotência responde antes de chegar aqui.
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
