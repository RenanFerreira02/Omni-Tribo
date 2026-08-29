package com.omnitribo.logistica.treino;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Diagrama de confiabilidade: probabilidade PREVISTA contra frequência OBSERVADA, na partição de
 * teste.
 *
 * <p><b>Responde a uma pergunta que a acurácia não responde.</b> A matriz de confusão só enxerga o
 * lado de lá do limiar — ela diz se o modelo ORDENA bem, não se o número que ele imprime na tela
 * significa alguma coisa. O produto exibe "72% de risco" ao usuário e deriva as faixas dessa
 * probabilidade; se entre as entregas que o modelo chama de 20% falharem 45%, o ranking pode até
 * estar certo e o número exibido é mentira. Calibração é a medição desse número.
 *
 * <p><b>Faixas por QUINTIL, e não por largura fixa.</b> Cortar [0,1] em cinco pedaços iguais
 * deixaria as duas últimas faixas vazias: com taxa-base de 23% e limiar em 0,19, o modelo quase
 * nunca emite probabilidade acima de 0,5 — e uma faixa com n=0 não tem frequência observada, logo
 * não é ponto de diagrama nenhum. Quintil garante n igual em toda faixa, que é também o que dá
 * barra de erro comparável entre elas. O intervalo previsto de cada faixa vai na tabela justamente
 * para que o leitor veja onde os cortes caíram.
 *
 * <p><b>Só a partição de TESTE.</b> O limiar continua vindo da validação, e nada aqui o toca — esta
 * classe não escolhe parâmetro nenhum, só descreve. Se ela influenciasse a escolha do limiar, o
 * teste voltaria a ser conjunto de seleção, que é o defeito que a terceira partição existe para
 * evitar.
 */
final class AvaliadorCalibracao {

  /** Cinco faixas: o suficiente para ver a forma da curva com n=200 por ponto em 1.000 amostras. */
  static final int FAIXAS = 5;

  private AvaliadorCalibracao() {}

  /**
   * Uma faixa do diagrama.
   *
   * @param previstoMedio média das probabilidades que o modelo emitiu nesta faixa — o eixo X
   * @param observado fração que de fato falhou — o eixo Y. Calibração perfeita é observado ==
   *     previstoMedio
   */
  record Faixa(
      int indice,
      double previstoMin,
      double previstoMax,
      double previstoMedio,
      double observado,
      int n,
      int falhas) {

    /** Positivo = o modelo SUBESTIMOU o risco desta faixa. É o erro que custa caro aqui. */
    double desvio() {
      return observado - previstoMedio;
    }
  }

  private record Ponto(int indice, double previsto, int rotulo) {}

  static List<Faixa> porQuintil(
      List<AmostraEntrega> particao,
      TreinadorRegressaoLogistica.ModeloTreinado modelo,
      Padronizador padronizador) {

    double[][] z = padronizador.transformar(particao);
    List<Ponto> pontos = new ArrayList<>(particao.size());
    for (int i = 0; i < particao.size(); i++) {
      pontos.add(new Ponto(i, modelo.probabilidade(z[i]), particao.get(i).rotulo()));
    }

    // Desempate EXPLÍCITO por índice, e não confiança na estabilidade do sort: duas amostras com a
    // mesma probabilidade prevista caem uma de cada lado quando o corte do quintil passa entre
    // elas, e é exatamente na fronteira que o empate acontece. Sem o desempate, a faixa de uma
    // delas dependeria da ordem que o algoritmo de ordenação deixasse.
    pontos.sort(Comparator.comparingDouble(Ponto::previsto).thenComparingInt(Ponto::indice));

    int n = pontos.size();
    List<Faixa> faixas = new ArrayList<>(FAIXAS);
    for (int f = 0; f < FAIXAS; f++) {
      // Cortes por multiplicação e não por (n / FAIXAS) repetido: com n não divisível por 5, o
      // segundo deixaria amostras de fora da última faixa em silêncio.
      int inicio = (int) ((long) n * f / FAIXAS);
      int fim = (int) ((long) n * (f + 1) / FAIXAS);

      double soma = 0.0;
      int falhas = 0;
      for (int i = inicio; i < fim; i++) {
        soma += pontos.get(i).previsto();
        falhas += pontos.get(i).rotulo();
      }
      int tamanho = fim - inicio;
      faixas.add(
          new Faixa(
              f + 1,
              pontos.get(inicio).previsto(),
              pontos.get(fim - 1).previsto(),
              soma / tamanho,
              (double) falhas / tamanho,
              tamanho,
              falhas));
    }
    return faixas;
  }

  /**
   * Brier score: erro quadrático médio da PROBABILIDADE, não da classe.
   *
   * <p>É a única métrica aqui que não depende do limiar. Menor é melhor; 0 é perfeito.
   */
  static double brier(
      List<AmostraEntrega> particao,
      TreinadorRegressaoLogistica.ModeloTreinado modelo,
      Padronizador padronizador) {

    double[][] z = padronizador.transformar(particao);
    double soma = 0.0;
    for (int i = 0; i < particao.size(); i++) {
      double erro = modelo.probabilidade(z[i]) - particao.get(i).rotulo();
      soma += erro * erro;
    }
    return soma / particao.size();
  }

  /**
   * Brier do CHUTE: prever sempre a mesma probabilidade constante, para todo mundo.
   *
   * <p>É o "melhor que um chute?" escrito como número. A constante usada é a taxa-base do TREINO —
   * o chute que alguém poderia de fato publicar, porque não olha o conjunto de avaliação. Usar a
   * taxa-base do próprio teste daria ao chute uma informação que ele não teria em operação.
   */
  static double brierDoChute(List<AmostraEntrega> particao, double constante) {
    double soma = 0.0;
    for (AmostraEntrega a : particao) {
      double erro = constante - a.rotulo();
      soma += erro * erro;
    }
    return soma / particao.size();
  }

  /** Fração do erro do chute que o modelo elimina. Positivo = o modelo informa; 0 = empata. */
  static double ganhoSobreChute(double brierModelo, double brierChute) {
    return brierChute == 0.0 ? 0.0 : 1.0 - brierModelo / brierChute;
  }

  /** Taxa de falha observada numa partição. */
  static double taxaBase(List<AmostraEntrega> particao) {
    int falhas = 0;
    for (AmostraEntrega a : particao) {
      falhas += a.rotulo();
    }
    return (double) falhas / particao.size();
  }

  /** Linhas de tabela Markdown, para o documento de métricas. */
  static String tabelaMarkdown(List<Faixa> faixas) {
    StringBuilder sb = new StringBuilder(1024);
    for (Faixa f : faixas) {
      sb.append(
          String.format(
              Locale.ROOT,
              "| %d | %.3f – %.3f | %.4f | %.4f | %+.4f | %d / %d |%n",
              f.indice(),
              f.previstoMin(),
              f.previstoMax(),
              f.previstoMedio(),
              f.observado(),
              f.desvio(),
              f.falhas(),
              f.n()));
    }
    return sb.toString();
  }

  /**
   * Erro de calibração médio, ponderado por faixa (ECE).
   *
   * <p>Com faixas de tamanho igual a ponderação é uniforme, mas a soma fica escrita como ponderada
   * de propósito: mudar {@link #FAIXAS} ou o critério de corte não passa a produzir número errado
   * em silêncio.
   */
  static double erroDeCalibracao(List<Faixa> faixas) {
    int total = 0;
    double soma = 0.0;
    for (Faixa f : faixas) {
      soma += f.n() * StrictMath.abs(f.desvio());
      total += f.n();
    }
    return total == 0 ? 0.0 : soma / total;
  }
}
