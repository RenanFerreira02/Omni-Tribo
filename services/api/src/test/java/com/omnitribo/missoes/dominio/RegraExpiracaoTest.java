package com.omnitribo.missoes.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Amarra {@link RegraExpiracao#medidosPorJanelaFim()} ao que {@link RegraExpiracao#padrao} produz.
 *
 * <p>São duas listas de statuses em pontos diferentes do código, e o diagnóstico de pote
 * imobilizado (ADR 0032) lê a primeira enquanto o job de expiração lê a segunda. Divergir é
 * silencioso: o diagnóstico passaria a medir uma missão pela coluna errada e a acusar — ou a
 * esconder — sem que nada falhasse. É a mesma classe de acoplamento que fez {@code
 * FinanciamentoService.validarEstado} quase ficar fora de sincronia com o construtor de {@code
 * Missao} quando AJUDA mudou de lado.
 */
class RegraExpiracaoTest {

  @Test
  @DisplayName("medidosPorJanelaFim acompanha exatamente o que padrao() marca como JANELA_FIM")
  void medidosPorJanelaFimAcompanhaOPadrao() {
    // Os prazos não influenciam o MARCO de nenhuma regra, então valores arbitrários servem: o que
    // está sob teste é qual coluna cada status usa, não quando ele vence.
    Set<StatusMissao> derivados =
        RegraExpiracao.padrao(Duration.ofHours(48), Duration.ofHours(72)).stream()
            .filter(regra -> regra.marco() == RegraExpiracao.Marco.JANELA_FIM)
            .map(RegraExpiracao::origem)
            .collect(Collectors.toSet());

    assertThat(RegraExpiracao.medidosPorJanelaFim())
        .as(
            "uma regra nova com Marco.JANELA_FIM precisa entrar em medidosPorJanelaFim() no mesmo "
                + "commit, senão o diagnóstico de pote imobilizado mede aquele status por "
                + "estado_desde e passa a acusar oferta saudável como imobilizada")
        .isEqualTo(derivados);
  }

  @Test
  @DisplayName("todo status varrido é não-terminal — varrer terminal seria transição impossível")
  void toda_regra_parte_de_status_nao_terminal() {
    assertThat(RegraExpiracao.padrao(Duration.ofHours(48), Duration.ofHours(72)))
        .allSatisfy(regra -> assertThat(regra.origem().ehTerminal()).isFalse());
  }
}
