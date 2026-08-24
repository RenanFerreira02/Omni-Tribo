package com.omnitribo.compartilhado.dominio;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * Mediana de uma amostra de valores inteiros. Função pura, sem Spring e sem banco.
 *
 * <p><b>Mediana e não média, e a escolha é sobre o que se quer afirmar.</b> A métrica é "quanto
 * tempo o bairro leva para responder a uma entrega falida". Uma única missão aceita três dias
 * depois — porque ninguém viu o alerta no fim de semana — desloca a média o bastante para descrever
 * uma operação que não existe. A mediana absorve o caso extremo; e é justamente por isso que o
 * painel publica o TAMANHO DA AMOSTRA junto: mediana de três pontos não é um fato sobre a operação,
 * é uma anedota com aparência de estatística.
 *
 * <p>Amostra par devolve a média dos dois centrais, arredondada para baixo pela divisão inteira. A
 * perda máxima é de meio segundo numa métrica de minutos ou horas, e evitar tanto ponto flutuante
 * quanto {@code BigDecimal} onde a unidade já é discreta.
 */
public final class Mediana {

  private Mediana() {}

  /**
   * @param valores não é modificada — a cópia existe porque ordenar a lista do chamador é efeito
   *     colateral invisível, e uma função "pura" que reordena o argumento é a pior espécie de
   *     armadilha
   * @return vazio para amostra vazia. Nunca zero: "nenhuma medição" e "mediana de zero segundos"
   *     são afirmações diferentes, e colapsá-las faria o painel anunciar resposta instantânea onde
   *     ninguém apareceu
   */
  public static OptionalLong de(List<Long> valores) {
    if (valores.isEmpty()) {
      return OptionalLong.empty();
    }

    List<Long> ordenados = new ArrayList<>(valores);
    ordenados.sort(null);

    int meio = ordenados.size() / 2;
    if (ordenados.size() % 2 == 1) {
      return OptionalLong.of(ordenados.get(meio));
    }
    // Média dos dois centrais somando como long: dois Instant em segundos não chegam perto de
    // estourar, e a soma antes da divisão evita o erro de arredondamento de dividir cada um.
    return OptionalLong.of((ordenados.get(meio - 1) + ordenados.get(meio)) / 2);
  }
}
