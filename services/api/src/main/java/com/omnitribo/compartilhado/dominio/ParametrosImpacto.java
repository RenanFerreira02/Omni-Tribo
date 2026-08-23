package com.omnitribo.compartilhado.dominio;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A PREMISSA econômica do painel de impacto — e o adjetivo é a parte importante.
 *
 * <p>{@code custoReentregaBrl} é quanto se assume que custa uma re-entrega. <b>Este projeto não
 * mediu esse número e não tem como medi-lo</b>: não há operação real, logo não há série de custo
 * para observar. Ele existe em configuração, e não em código, exatamente para que essa natureza
 * fique visível — um literal no meio de um cálculo em Java pareceria resultado, e é assim que
 * premissa vira "dado" no slide de alguém.
 *
 * <p>É por isso também que o endpoint devolve o resultado com este valor variando ±50%: a conclusão
 * que interessa ("o custo evitado é da ordem de X") precisa sobreviver ao número estar errado pela
 * metade ou pela metade a mais. Ver ADR 0029 §5.
 *
 * <p>Não tem {@code versao} como {@code ParametrosRecompensa} e {@code ParametrosRisco} têm — e a
 * assimetria é deliberada. Aqueles CONGELAM o resultado numa coluna, então a versão é o que explica
 * um crédito antigo. Este só alimenta um relatório recalculado a cada chamada: nada foi gravado sob
 * a premissa velha, e não há passado para reinterpretar.
 */
@ConfigurationProperties(prefix = "app.impacto")
public record ParametrosImpacto(BigDecimal custoReentregaBrl) {

  public ParametrosImpacto {
    // Falha no BOOT, não na primeira chamada do painel. Premissa ausente ou negativa é erro de
    // configuração, e descobri-lo numa demonstração — pela tela em branco — é tarde demais.
    if (custoReentregaBrl == null || custoReentregaBrl.signum() < 0) {
      throw new IllegalArgumentException(
          "app.impacto.custo-reentrega-brl é obrigatório e não pode ser negativo");
    }
  }
}
