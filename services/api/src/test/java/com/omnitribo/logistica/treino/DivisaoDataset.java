package com.omnitribo.logistica.treino;

import java.util.ArrayList;
import java.util.List;

/**
 * Partição 60/20/20 em treino, validação e teste — <b>estratificada e sem embaralhamento</b>.
 *
 * <p><b>São TRÊS partições e não duas, e a terceira é o ponto metodológico desta fase.</b> O limiar
 * de decisão é um parâmetro escolhido a partir dos dados, exatamente como os coeficientes. Varrer
 * limiares no conjunto de teste e depois reportar o recall DESSE MESMO conjunto é seleção sobre o
 * conjunto de avaliação: o número sai otimista e deixa de ser estimativa honesta de desempenho fora
 * da amostra. Então o treino ajusta coeficientes, a VALIDAÇÃO escolhe o limiar, e o TESTE é tocado
 * uma única vez, no fim, só para reportar.
 *
 * <p><b>Sem embaralhamento, de propósito.</b> As amostras já são independentes por construção — o
 * gerador sorteia cada linha sem olhar as anteriores. Separar os índices por rótulo preservando a
 * ordem de geração e fatiar dentro de cada lista já produz partições estratificadas, e elimina mais
 * um sorteio aleatório que precisaria ser semeado e defendido.
 */
record DivisaoDataset(
    List<AmostraEntrega> treino, List<AmostraEntrega> validacao, List<AmostraEntrega> teste) {

  private static final double FRACAO_TREINO = 0.60;
  private static final double FRACAO_VALIDACAO = 0.20;

  static DivisaoDataset estratificar(List<AmostraEntrega> amostras) {
    List<AmostraEntrega> falhas = new ArrayList<>();
    List<AmostraEntrega> sucessos = new ArrayList<>();
    for (AmostraEntrega a : amostras) {
      if (a.falhou()) {
        falhas.add(a);
      } else {
        sucessos.add(a);
      }
    }

    List<AmostraEntrega> treino = new ArrayList<>();
    List<AmostraEntrega> validacao = new ArrayList<>();
    List<AmostraEntrega> teste = new ArrayList<>();

    for (List<AmostraEntrega> estrato : List.of(falhas, sucessos)) {
      int n = estrato.size();
      int fimTreino = (int) StrictMath.round(n * FRACAO_TREINO);
      int fimValidacao = fimTreino + (int) StrictMath.round(n * FRACAO_VALIDACAO);
      treino.addAll(estrato.subList(0, fimTreino));
      validacao.addAll(estrato.subList(fimTreino, fimValidacao));
      teste.addAll(estrato.subList(fimValidacao, n));
    }
    return new DivisaoDataset(List.copyOf(treino), List.copyOf(validacao), List.copyOf(teste));
  }
}
