package com.omnitribo.logistica.treino;

import com.omnitribo.logistica.dominio.CaracteristicaRisco;
import com.omnitribo.logistica.dominio.PrevisorDeRisco;
import java.util.Arrays;

/**
 * Ajusta a regressão logística por gradiente descendente. Determinístico por construção.
 *
 * <p><b>Lote cheio (batch), não SGD.</b> O gradiente estocástico depende da ordem de embaralhamento
 * e do estado do gerador aleatório; o de lote cheio não depende de ordem nenhuma além da soma
 * sequencial por índice crescente. Com 3.000 linhas × 14 características, uma época custa
 * microssegundos — não há ganho de desempenho que justifique introduzir uma fonte de
 * não-determinismo num projeto cuja tese é reprodutibilidade.
 *
 * <p><b>Pesos iniciados em ZERO.</b> A log-loss de uma regressão logística é convexa: o mínimo é
 * único e não depende do ponto de partida. Inicialização aleatória seria uma segunda semente a
 * semear, documentar e defender, sem produzir nada diferente.
 *
 * <p><b>Sem parada antecipada, e isso é reprodutibilidade e não preguiça.</b> Parar quando {@code
 * |J_anterior − J| < ε} é um {@code if} sobre ponto flutuante: uma diferença de 1 ulp faz o laço
 * parar na época 1.842 numa máquina e 1.843 noutra, e os coeficientes divergem na sexta casa. A
 * contagem fixa de épocas elimina a classe inteira de problema; a convergência é <b>verificada</b>
 * pelo teste, comparando a norma do gradiente final com zero, e não <b>decidida</b> pelo laço.
 *
 * <p><b>L2 nos pesos, nunca no intercepto.</b> Regularizar o intercepto empurraria a taxa-base
 * predita na direção de 50%, um viés sem justificativa nenhuma. E a L2 aqui não combate sobreajuste
 * — 14 características para 3.000 amostras não sobreajusta —, ela combate INSTABILIDADE: peso e
 * volume são correlacionados por construção no gerador, assim como chuva e janela noturna, e sem
 * regularização os coeficientes desses pares oscilam bastante para variações pequenas do dado.
 */
final class TreinadorRegressaoLogistica {

  /** Viável porque as características numéricas estão padronizadas — a curvatura fica benigna. */
  static final double TAXA_APRENDIZADO = 0.3;

  static final int EPOCAS = 2000;

  /** Escalado por {@code 1/n} no gradiente, o que dá ~3e-4 por peso: fraco de propósito. */
  static final double LAMBDA_L2 = 1.0;

  private TreinadorRegressaoLogistica() {}

  /**
   * @param normaGradiente maior componente absoluta do gradiente na última época. É a EVIDÊNCIA de
   *     convergência — perto de zero significa que o laço chegou ao mínimo, e o teste assere isso
   *     em vez de confiar na contagem de épocas.
   */
  record ModeloTreinado(
      double intercepto, double[] pesos, double logLossFinal, double normaGradiente) {

    ModeloTreinado {
      pesos = pesos.clone();
    }

    double peso(CaracteristicaRisco c) {
      return pesos[c.indice()];
    }

    /** Log-odds de um vetor JÁ padronizado. */
    double logOdds(double[] z) {
      double soma = intercepto;
      for (CaracteristicaRisco c : CaracteristicaRisco.values()) {
        soma += pesos[c.indice()] * z[c.indice()];
      }
      return soma;
    }

    double probabilidade(double[] z) {
      return PrevisorDeRisco.sigmoide(logOdds(z));
    }
  }

  /**
   * @param x matriz n × {@code CaracteristicaRisco.TOTAL}, JÁ padronizada com μ/σ do treino
   * @param y rótulos 0/1, na mesma ordem
   */
  static ModeloTreinado treinar(double[][] x, int[] y) {
    if (x.length != y.length || x.length == 0) {
      throw new IllegalArgumentException("Matriz e rótulos precisam ter o mesmo tamanho não nulo");
    }
    int n = x.length;
    int d = CaracteristicaRisco.TOTAL;

    double[] w = new double[d];
    double b = 0.0;
    double[] gradW = new double[d];
    double logLoss = 0.0;
    double normaGradiente = 0.0;

    for (int epoca = 0; epoca < EPOCAS; epoca++) {
      Arrays.fill(gradW, 0.0);
      double gradB = 0.0;
      logLoss = 0.0;

      // Laço por índice CRESCENTE, sempre. Nunca stream, nunca parallel: a ordem da soma em ponto
      // flutuante faz parte do resultado, e paralelizar tornaria os coeficientes irreprodutíveis.
      for (int i = 0; i < n; i++) {
        double z = b;
        for (int j = 0; j < d; j++) {
          z += w[j] * x[i][j];
        }
        double p = PrevisorDeRisco.sigmoide(z);
        double erro = p - y[i];

        gradB += erro;
        for (int j = 0; j < d; j++) {
          gradW[j] += erro * x[i][j];
        }
        logLoss += y[i] == 1 ? -StrictMath.log(limitar(p)) : -StrictMath.log(limitar(1.0 - p));
      }

      logLoss /= n;
      gradB /= n;
      normaGradiente = StrictMath.abs(gradB);

      for (int j = 0; j < d; j++) {
        gradW[j] = gradW[j] / n + (LAMBDA_L2 / n) * w[j];
        logLoss += (LAMBDA_L2 / (2.0 * n)) * w[j] * w[j];
        normaGradiente = StrictMath.max(normaGradiente, StrictMath.abs(gradW[j]));
      }

      b -= TAXA_APRENDIZADO * gradB;
      for (int j = 0; j < d; j++) {
        w[j] -= TAXA_APRENDIZADO * gradW[j];
      }
    }
    return new ModeloTreinado(b, w, logLoss, normaGradiente);
  }

  /** Evita {@code log(0)}, que é −infinito e contaminaria a log-loss inteira com NaN. */
  private static double limitar(double p) {
    return StrictMath.min(StrictMath.max(p, 1e-15), 1.0 - 1e-15);
  }
}
