package com.omnitribo.identidade.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Sem Spring: a curva de nível é função pura. */
class RegraNivelTest {

  @ParameterizedTest(name = "xp={0} → nível {1}")
  @CsvSource({
    "0, 1", // usuário novo nasce no nível 1, nunca no 0
    "99, 1", // um XP antes do degrau
    "100, 2", // exatamente no degrau
    "101, 2", "399, 2", "400, 3", "899, 3", "900, 4", "1600, 5", "2500, 6"
  })
  void fronteirasDaCurva(long xp, int nivelEsperado) {
    assertThat(RegraNivel.nivelPara(xp)).isEqualTo(nivelEsperado);
  }

  @Test
  void nivelNuncaDiminuiConformeOXpCresce() {
    // XP é monotônico (ADR 0004: sem ledger, sem estorno), então o nível também tem de ser. Uma
    // curva não-monotônica faria um usuário PERDER nível ao ganhar XP — o pior bug de gamificação
    // possível, e invisível em teste de valor único.
    int anterior = RegraNivel.nivelPara(0);
    for (long xp = 1; xp <= 10_000; xp += 7) {
      int atual = RegraNivel.nivelPara(xp);
      assertThat(atual).as("nível em xp=%d", xp).isGreaterThanOrEqualTo(anterior);
      anterior = atual;
    }
  }

  @ParameterizedTest(name = "nível {0} começa em {1} de XP")
  @CsvSource({"1, 0", "2, 100", "3, 400", "4, 900", "5, 1600"})
  void xpParaNivelEhInversoDeNivelPara(int nivel, long xpEsperado) {
    assertThat(RegraNivel.xpParaNivel(nivel)).isEqualTo(xpEsperado);
    assertThat(RegraNivel.nivelPara(xpEsperado)).isEqualTo(nivel);
  }

  @Test
  void xpNegativoEhRecusado() {
    assertThatThrownBy(() -> RegraNivel.nivelPara(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("negativo");
  }

  @Test
  void nivelAbaixoDeUmEhRecusado() {
    assertThatThrownBy(() -> RegraNivel.xpParaNivel(0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
