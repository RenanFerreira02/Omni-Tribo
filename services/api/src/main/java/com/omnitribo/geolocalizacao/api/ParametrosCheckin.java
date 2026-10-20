package com.omnitribo.geolocalizacao.api;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Calibração do check-in (ADR 0040).
 *
 * <p>O padrão é {@link LimitesCheckin#ACURACIA_MAXIMA_M} e não muda em nenhum profile de produto:
 * acima de 50 m o fix não sustenta afirmação de presença. A propriedade existe para UM caso, o
 * profile {@code feira}: dentro de pavilhão o GPS do aparelho costuma reportar precisão pior que
 * isso, e a demonstração não pode depender de achar uma janela. Vive em {@code api/} porque {@code
 * missoes} precisa do valor para publicá-lo no ProblemDetail da recusa.
 *
 * @param acuraciaMaximaM pior precisão aceita, em metros
 */
@ConfigurationProperties(prefix = "app.geolocalizacao")
public record ParametrosCheckin(BigDecimal acuraciaMaximaM) {

  public ParametrosCheckin {
    if (acuraciaMaximaM == null) {
      acuraciaMaximaM = LimitesCheckin.ACURACIA_MAXIMA_M;
    }
    if (acuraciaMaximaM.signum() <= 0) {
      throw new IllegalArgumentException(
          "app.geolocalizacao.acuracia-maxima-m precisa ser positivo; recebeu " + acuraciaMaximaM);
    }
  }
}
