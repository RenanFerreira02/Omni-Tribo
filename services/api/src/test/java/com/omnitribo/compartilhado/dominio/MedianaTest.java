package com.omnitribo.compartilhado.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Função pura: sem Spring, sem banco, sem contexto. */
@DisplayName("Mediana")
class MedianaTest {

  @Test
  @DisplayName("amostra vazia é AUSENTE, não zero")
  void vaziaEhAusente() {
    // A distinção é o ponto: "ninguém apareceu" e "apareceram em zero segundos" são afirmações
    // opostas, e colapsá-las faria o painel anunciar resposta instantânea onde não houve resposta.
    assertThat(Mediana.de(List.of())).isEmpty();
  }

  @Test
  @DisplayName("um único valor é ele mesmo")
  void umValor() {
    assertThat(Mediana.de(List.of(42L))).hasValue(42L);
  }

  @Test
  @DisplayName("amostra ímpar devolve o central")
  void impar() {
    assertThat(Mediana.de(List.of(10L, 20L, 30L))).hasValue(20L);
  }

  @Test
  @DisplayName("amostra par devolve a média dos dois centrais")
  void par() {
    assertThat(Mediana.de(List.of(10L, 20L, 30L, 40L))).hasValue(25L);
  }

  @Test
  @DisplayName("ordena antes de escolher — a entrada não precisa vir ordenada")
  void foraDeOrdem() {
    assertThat(Mediana.de(List.of(30L, 10L, 20L))).hasValue(20L);
  }

  @Test
  @DisplayName("o extremo desloca a média, não a mediana — é por isso que é mediana")
  void resisteAoExtremo() {
    // Uma missão aceita três dias depois. A média destes cinco valores é 51.808 s (14 h); a
    // mediana é 600 s. A segunda descreve a operação que existe.
    List<Long> comExtremo = List.of(300L, 450L, 600L, 750L, 258_940L);

    assertThat(Mediana.de(comExtremo)).hasValue(600L);
  }

  @Test
  @DisplayName("não reordena a lista de quem chamou")
  void naoMutaEntrada() {
    // Uma função "pura" que reordena o argumento é a pior espécie de armadilha: o efeito é
    // invisível na assinatura e só aparece no chamador seguinte.
    List<Long> original = new ArrayList<>(List.of(30L, 10L, 20L));

    Mediana.de(original);

    assertThat(original).containsExactly(30L, 10L, 20L);
  }
}
