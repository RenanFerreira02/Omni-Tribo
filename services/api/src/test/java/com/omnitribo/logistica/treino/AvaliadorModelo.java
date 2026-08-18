package com.omnitribo.logistica.treino;

import java.util.List;

/** Aplica o modelo a uma partição e conta os quatro quadrantes. */
final class AvaliadorModelo {

  private AvaliadorModelo() {}

  static MatrizConfusao avaliar(
      List<AmostraEntrega> particao,
      TreinadorRegressaoLogistica.ModeloTreinado modelo,
      Padronizador padronizador,
      double limiar) {

    double[][] x = padronizador.transformar(particao);
    int vp = 0;
    int fp = 0;
    int vn = 0;
    int fn = 0;

    for (int i = 0; i < particao.size(); i++) {
      boolean previuFalha = modelo.probabilidade(x[i]) >= limiar;
      boolean falhou = particao.get(i).falhou();
      if (previuFalha && falhou) {
        vp++;
      } else if (previuFalha) {
        fp++;
      } else if (falhou) {
        fn++;
      } else {
        vn++;
      }
    }
    return new MatrizConfusao(vp, fp, vn, fn);
  }

  /**
   * Erro de Bayes empírico: a fração de rótulos que o modelo VERDADEIRO erraria nesta partição.
   *
   * <p>É o teto que nenhum modelo pode ultrapassar, e existe porque o rótulo foi SORTEADO de {@code
   * Bernoulli(p)} em vez de decidido por regra. Publicar este número ao lado da acurácia é o que
   * responde honestamente a "por que não 95%?": porque 95% seria impossível neste dado.
   */
  static double acuraciaMaximaTeorica(List<AmostraEntrega> particao) {
    double acertosEsperados = 0.0;
    for (AmostraEntrega a : particao) {
      double p = a.probabilidadeVerdadeira();
      acertosEsperados += StrictMath.max(p, 1.0 - p);
    }
    return acertosEsperados / particao.size();
  }
}
