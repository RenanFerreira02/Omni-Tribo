package com.omnitribo.logistica.treino;

import java.util.Random;

/**
 * Fonte de aleatoriedade do gerador de dataset, determinística por construção.
 *
 * <p><b>Box–Muller próprio sobre {@code nextDouble()}, e nunca {@code Random.nextGaussian()}.</b>
 * {@code nextDouble()} é especificado bit a bit pela JLS (LCG de 48 bits, algoritmo publicado);
 * {@code nextGaussian()} é implementação, não contrato. Para um projeto cuja tese é
 * reprodutibilidade, depender de um algoritmo interno que a JVM pode trocar é aceitar risco sem
 * ganho nenhum.
 *
 * <p><b>O segundo desvio de Box–Muller é DESCARTADO em vez de cacheado</b>, e isso é deliberado.
 * Guardá-lo faria o resultado depender da PARIDADE do número de chamadas anteriores: inserir uma
 * característica nova no meio do gerador deslocaria todos os sorteios seguintes de forma invisível,
 * e o dataset mudaria sem que ninguém tivesse mexido na distribuição. Desperdiçar metade dos
 * números é barato; um dataset que muda sozinho não é.
 */
final class RuidoDeterministico {

  private final Random rng;

  RuidoDeterministico(long semente) {
    this.rng = new Random(semente);
  }

  /** Uniforme em [0,1). */
  double uniforme() {
    return rng.nextDouble();
  }

  /** Uniforme em [minimo, maximo). */
  double uniforme(double minimo, double maximo) {
    return minimo + (maximo - minimo) * rng.nextDouble();
  }

  /** Normal padrão, por Box–Muller. */
  double normal() {
    // max(u1, 1e-12): log(0) é -infinito, e um u1 exatamente zero produziria NaN que se propagaria
    // por todo o dataset sem erro visível.
    double u1 = StrictMath.max(rng.nextDouble(), 1e-12);
    double u2 = rng.nextDouble();
    return StrictMath.sqrt(-2.0 * StrictMath.log(u1)) * StrictMath.cos(2.0 * StrictMath.PI * u2);
  }

  /** Verdadeiro com probabilidade {@code p}. É como o rótulo de falha é sorteado. */
  boolean bernoulli(double p) {
    return rng.nextDouble() < p;
  }

  /** Índice sorteado segundo os pesos informados. Os pesos não precisam somar 1. */
  int categoria(double... pesos) {
    double total = 0.0;
    for (double peso : pesos) {
      total += peso;
    }
    double alvo = rng.nextDouble() * total;
    double acumulado = 0.0;
    for (int i = 0; i < pesos.length; i++) {
      acumulado += pesos[i];
      if (alvo < acumulado) {
        return i;
      }
    }
    return pesos.length - 1;
  }

  /** Inteiro em [0, limite). */
  int inteiro(int limite) {
    return rng.nextInt(limite);
  }
}
