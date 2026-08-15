package com.omnitribo.logistica.treino;

import java.util.Locale;

/**
 * Matriz de confusão e as métricas derivadas dela.
 *
 * <p>Convenção: a classe POSITIVA é "a entrega vai falhar". Isso importa para ler recall
 * corretamente — recall alto significa "de todas as entregas que realmente falharam, o modelo
 * apontou a maioria", que é exatamente o que este produto precisa maximizar.
 *
 * @param verdadeirosPositivos previu falha, falhou
 * @param falsosPositivos previu falha, deu certo — custo BAIXO: pagamos um pouco mais de token e
 *     notificamos um pouco antes
 * @param verdadeirosNegativos previu sucesso, deu certo
 * @param falsosNegativos previu sucesso, FALHOU — custo ALTO: a missão nasce subvalorizada, sem
 *     prioridade no fan-out e sem aviso para quem vai executar. É este o erro que o limiar é
 *     escolhido para minimizar.
 */
record MatrizConfusao(
    int verdadeirosPositivos, int falsosPositivos, int verdadeirosNegativos, int falsosNegativos) {

  int total() {
    return verdadeirosPositivos + falsosPositivos + verdadeirosNegativos + falsosNegativos;
  }

  double acuracia() {
    int t = total();
    // Cast no NUMERADOR. Escrever (double) (a / b) faria divisão inteira antes da conversão e
    // devolveria sempre 0 ou 1 — é o achado ICAST_IDIV_CAST_TO_DOUBLE do SpotBugs, que quebra o
    // build, e o defeito silencioso que ele existe para pegar.
    return t == 0 ? 0.0 : (double) (verdadeirosPositivos + verdadeirosNegativos) / t;
  }

  double precisao() {
    int previstosPositivos = verdadeirosPositivos + falsosPositivos;
    return previstosPositivos == 0 ? 0.0 : (double) verdadeirosPositivos / previstosPositivos;
  }

  double recall() {
    int realmentePositivos = verdadeirosPositivos + falsosNegativos;
    return realmentePositivos == 0 ? 0.0 : (double) verdadeirosPositivos / realmentePositivos;
  }

  /**
   * F2 — média harmônica ponderada que pesa recall 4× mais que precisão.
   *
   * <p>Reportado como CORROBORAÇÃO, não como critério: o limiar é escolhido por uma regra de
   * negócio explicável em uma frase (piso de precisão), e não por β=2, que é um número sem
   * história. Se o limiar escolhido pelo piso também for o de maior F2, isso é evidência de que a
   * escolha não foi arbitrária.
   */
  double f2() {
    double p = precisao();
    double r = recall();
    return p + r == 0.0 ? 0.0 : 5.0 * p * r / (4.0 * p + r);
  }

  /** Linha de tabela Markdown, para o documento de métricas. */
  String linhaMarkdown(String rotulo) {
    return String.format(
        Locale.ROOT,
        "| %s | %.4f | %.4f | %.4f | %.4f | %d | %d | %d | %d |",
        rotulo,
        acuracia(),
        precisao(),
        recall(),
        f2(),
        verdadeirosPositivos,
        falsosPositivos,
        verdadeirosNegativos,
        falsosNegativos);
  }
}
