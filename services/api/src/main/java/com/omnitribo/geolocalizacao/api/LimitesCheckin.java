package com.omnitribo.geolocalizacao.api;

import java.math.BigDecimal;

/**
 * Limites do check-in que o CLIENTE precisa conhecer para explicar a recusa.
 *
 * <p>Vive em {@code api/} e não junto das regras em {@code dominio/AvaliacaoAntifraude} por uma
 * razão de fronteira, não de organização: quem monta a resposta HTTP é {@code missoes}, e a regra
 * do ArchUnit proíbe qualquer módulo de alcançar {@code geolocalizacao.dominio}. Sem esta classe, o
 * controller só poderia informar o teto de acurácia repetindo o número — duas fontes de verdade que
 * divergem no primeiro ajuste.
 *
 * <p>É constante pública de propósito, e não configuração: mudar o teto muda o que "presença"
 * significa, e isso é decisão de produto que merece revisão de código, não troca de YAML.
 */
public final class LimitesCheckin {

  /**
   * Acima disto o fix não sustenta afirmação de presença: um raio de erro de 50 m sobre um alvo de
   * 50 m torna "dentro" e "fora" indistinguíveis.
   */
  public static final BigDecimal ACURACIA_MAXIMA_M = new BigDecimal("50");

  private LimitesCheckin() {}
}
