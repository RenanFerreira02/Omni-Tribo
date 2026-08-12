package com.omnitribo.identidade.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnitribo.identidade.dominio.CalculadoraDeConquistas.Conquista;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Conquistas derivadas: catálogo completo, saturação e o teste dourado da calibração. */
class CalculadoraDeConquistasTest {

  /** Espelho de application.yml. Ver {@link #douradoV1()}. */
  private static final ParametrosConquistas V1 = new ParametrosConquistas(1, 500, 2000, 10, 7);

  @Test
  void usuario_novo_recebe_o_catalogo_inteiro_sem_nenhuma_conquistada() {
    List<Conquista> conquistas = CalculadoraDeConquistas.avaliar(0, 1, 0, V1);

    // O catálogo volta INTEIRO mesmo sem nada conquistado: uma lista vazia não diria ao usuário
    // qual é o próximo objetivo.
    assertThat(conquistas).hasSize(5);
    assertThat(conquistas).allMatch(c -> !c.conquistada());
    assertThat(conquistas).allMatch(c -> c.progresso() == 0 || c.codigo().equals("VETERANO"));
  }

  @Test
  void xp_alcancando_o_limiar_concede_a_conquista() {
    Map<String, Conquista> porCodigo = indexar(CalculadoraDeConquistas.avaliar(500, 3, 0, V1));

    assertThat(porCodigo.get("INICIANTE").conquistada()).isTrue();
    assertThat(porCodigo.get("VIZINHO_PRESENTE").conquistada()).isTrue();
    assertThat(porCodigo.get("PILAR_DA_TRIBO").conquistada()).isFalse();
  }

  @Test
  void limiar_e_inclusivo_no_valor_exato() {
    // Exatamente na meta CONTA. O contrário faria o usuário ver 500/500 sem a medalha.
    assertThat(indexar(CalculadoraDeConquistas.avaliar(500, 1, 0, V1)).get("VIZINHO_PRESENTE"))
        .extracting(Conquista::conquistada)
        .isEqualTo(true);
    assertThat(indexar(CalculadoraDeConquistas.avaliar(499, 1, 0, V1)).get("VIZINHO_PRESENTE"))
        .extracting(Conquista::conquistada)
        .isEqualTo(false);
  }

  /**
   * Sem saturar, uma barra de progresso ingênua com 4000 de 2000 renderizaria o dobro da largura do
   * componente e a tela leria "4000/2000" — que parece erro de cálculo, não conquista superada.
   */
  @Test
  void progresso_nunca_passa_da_meta() {
    List<Conquista> conquistas = CalculadoraDeConquistas.avaliar(999_999, 99, 999, V1);

    assertThat(conquistas).allMatch(c -> c.progresso() <= c.meta());
    assertThat(conquistas).allMatch(Conquista::conquistada);
  }

  @Test
  void nivel_e_streak_alimentam_as_proprias_conquistas() {
    Map<String, Conquista> porCodigo = indexar(CalculadoraDeConquistas.avaliar(0, 10, 7, V1));

    assertThat(porCodigo.get("VETERANO").conquistada()).isTrue();
    assertThat(porCodigo.get("CONSTANTE").conquistada()).isTrue();
    // E XP zero não concede nenhuma das que dependem de XP.
    assertThat(porCodigo.get("INICIANTE").conquistada()).isFalse();
  }

  @Test
  void codigos_sao_estaveis_e_sem_repeticao() {
    // O app ramifica pelo código; título e descrição são copy e mudam sem aviso.
    assertThat(CalculadoraDeConquistas.avaliar(0, 1, 0, V1))
        .extracting(Conquista::codigo)
        .containsExactly("INICIANTE", "VIZINHO_PRESENTE", "PILAR_DA_TRIBO", "VETERANO", "CONSTANTE")
        .doesNotHaveDuplicates();
  }

  /**
   * TESTE DOURADO — falha DE PROPÓSITO quando alguém muda {@code app.identidade.conquistas.*}.
   *
   * <p>Existe porque conquista é derivada e nada é gravado: baixar um limiar concede medalhas
   * retroativamente e subir um limiar <b>REVOGA</b> conquistas que o usuário já exibia, sem nenhum
   * registro de que a régua mudou. Diferente de {@code CalculadoraDeRecompensaTest.douradoV1}, aqui
   * não há {@code versao} a subir — não existe coluna onde congelá-la, então a única proteção é
   * esta falha obrigando a decisão consciente. Se você chegou aqui: confirme que quer mexer no
   * histórico de todo mundo, e então atualize os números nos dois lugares.
   */
  @Test
  void douradoV1() {
    assertThat(V1)
        .isEqualTo(new ParametrosConquistas(1L, 500L, 2000L, 10, 7))
        .describedAs("calibração congelada — mudá-la concede ou revoga medalhas retroativamente");

    Map<String, Conquista> perfilTipico = indexar(CalculadoraDeConquistas.avaliar(1200, 4, 3, V1));

    assertThat(perfilTipico.get("INICIANTE").conquistada()).isTrue();
    assertThat(perfilTipico.get("VIZINHO_PRESENTE").conquistada()).isTrue();
    assertThat(perfilTipico.get("PILAR_DA_TRIBO").conquistada()).isFalse();
    assertThat(perfilTipico.get("PILAR_DA_TRIBO").progresso()).isEqualTo(1200);
    assertThat(perfilTipico.get("PILAR_DA_TRIBO").meta()).isEqualTo(2000);
    assertThat(perfilTipico.get("VETERANO").conquistada()).isFalse();
    assertThat(perfilTipico.get("CONSTANTE").progresso()).isEqualTo(3);
  }

  private static Map<String, Conquista> indexar(List<Conquista> conquistas) {
    return conquistas.stream()
        .collect(java.util.stream.Collectors.toMap(Conquista::codigo, Function.identity()));
  }
}
