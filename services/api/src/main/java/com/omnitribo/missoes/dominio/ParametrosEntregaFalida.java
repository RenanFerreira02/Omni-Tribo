package com.omnitribo.missoes.dominio;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Calibração da missão gerada automaticamente a partir de uma entrega falida.
 *
 * <p><b>Separado de {@link ParametrosRecompensa} de propósito, e a separação é importante.</b> Lá,
 * mudar um número reescreve o significado de missões já criadas, e por isso existe {@code versao} e
 * um teste dourado que falha para forçar a decisão. Aqui não: estes valores só afetam missões
 * futuras, e nenhuma linha em banco depende deles para ser explicada depois. Misturar os dois faria
 * ajustar um prazo de retirada exigir subir a versão da fórmula.
 */
@ConfigurationProperties(prefix = "app.missoes.entrega-falida")
public record ParametrosEntregaFalida(
    /** Nível mínimo para aceitar. Ver {@code Missao.nivelMinimo}. */
    int nivelMinimo,

    /** Raio de check-in da entrega, em metros. */
    int raioCheckinM,

    /** Janela entre a publicação e o fim do prazo de retirada. */
    Duration prazoRetirada) {

  public ParametrosEntregaFalida {
    if (nivelMinimo < 1) {
      throw new IllegalArgumentException(
          "app.missoes.entrega-falida.nivel-minimo deve ser ao menos 1");
    }
    if (raioCheckinM < 10) {
      throw new IllegalArgumentException(
          "app.missoes.entrega-falida.raio-checkin-m deve ser ao menos 10");
    }
    if (prazoRetirada == null || prazoRetirada.isNegative() || prazoRetirada.isZero()) {
      throw new IllegalArgumentException(
          "app.missoes.entrega-falida.prazo-retirada deve ser positivo");
    }
  }
}
