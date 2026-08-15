package com.omnitribo.logistica.treino;

import java.util.ArrayList;
import java.util.List;

/**
 * Escolhe o limiar de decisão varrendo uma grade fixa na partição de VALIDAÇÃO.
 *
 * <p><b>O custo do erro é assimétrico, então o limiar é assimétrico.</b> Um falso negativo — prever
 * sucesso numa entrega que vai falhar — devolve ao ciclo um caso que ninguém preparou: a missão
 * nasce com recompensa subvalorizada, sem prioridade no fan-out e sem aviso para quem vai executar.
 * Um falso positivo custa um pouco mais de token e uma notificação a mais. Como errar para um lado
 * dói mais que para o outro, o limiar fica bem abaixo de 0,50.
 *
 * <p><b>Por que piso de precisão e não "maximize F2 direto".</b> O piso é uma afirmação de produto
 * que se defende em uma frase: abaixo dele, mais da metade dos alertas é falsa e o operador para de
 * olhar — momento em que o modelo inteiro deixa de ter valor, por melhor que seja o recall. O β=2
 * do F2 é um número sem história equivalente.
 *
 * <p><b>Nunca varra no conjunto de teste.</b> O limiar é um parâmetro ajustado a partir dos dados,
 * como qualquer coeficiente; escolhê-lo olhando o teste e depois reportar as métricas desse mesmo
 * teste é seleção sobre o conjunto de avaliação, e o recall publicado sairia otimista.
 */
final class SeletorDeLimiar {

  /** Grade fixa de 0,01 a 0,99. Fixa para que a escolha não dependa de busca adaptativa. */
  private static final int PASSOS = 99;

  /**
   * Piso de precisão que o limiar escolhido precisa sustentar.
   *
   * <p>0,35 e não 0,50, e o número sai da AÇÃO que o score dispara. Se marcar uma entrega como
   * arriscada acionasse uma visita técnica, meia dúzia de falsos positivos por acerto seria caro
   * demais. Aqui, marcar dispara três coisas baratas: um prêmio limitado em token (teto de 1,5×),
   * uma posição melhor na fila de notificação, e um aviso na tela de quem vai executar. Nenhuma
   * delas causa dano quando errada.
   *
   * <p>Com taxa-base de ~23%, precisão de 0,35 ainda é 1,5× melhor que sortear — o modelo continua
   * carregando informação real. Abaixo disso o alerta viraria ruído e o executor pararia de ler,
   * que é o ponto em que o modelo inteiro perde valor por mais alto que fosse o recall.
   */
  static final double PISO_PRECISAO = 0.35;

  private SeletorDeLimiar() {}

  record Candidato(double limiar, MatrizConfusao matriz) {}

  /**
   * Varre a grade e devolve todos os candidatos, para o documento poder publicar a tabela.
   *
   * <p>O limiar é arredondado a duas casas de propósito: publicar {@code 0,28} num YAML é legível e
   * estável, enquanto {@code 0,2800000000000001} convidaria a divergência de arredondamento entre a
   * varredura e o valor publicado.
   */
  static List<Candidato> varrer(
      List<AmostraEntrega> validacao,
      TreinadorRegressaoLogistica.ModeloTreinado modelo,
      Padronizador padronizador) {

    List<Candidato> candidatos = new ArrayList<>(PASSOS);
    for (int i = 1; i <= PASSOS; i++) {
      double limiar = StrictMath.round(i) / 100.0;
      candidatos.add(
          new Candidato(limiar, AvaliadorModelo.avaliar(validacao, modelo, padronizador, limiar)));
    }
    return List.copyOf(candidatos);
  }

  /**
   * Maior recall entre os candidatos que sustentam o piso de precisão.
   *
   * <p>Desempate em duas etapas, ambas determinísticas: primeiro maior precisão, depois MAIOR
   * limiar. A segunda regra existe para remover qualquer ambiguidade residual — sem ela, dois
   * limiares com métricas idênticas dependeriam da ordem de iteração da lista.
   *
   * <p>Se NENHUM candidato atinge o piso, este método lança. Um modelo que não sustenta 50% de
   * precisão em limiar nenhum não deveria ser publicado em silêncio.
   */
  static double escolher(
      List<AmostraEntrega> validacao,
      TreinadorRegressaoLogistica.ModeloTreinado modelo,
      Padronizador padronizador) {

    Candidato melhor = null;
    for (Candidato c : varrer(validacao, modelo, padronizador)) {
      if (c.matriz().precisao() < PISO_PRECISAO) {
        continue;
      }
      if (melhor == null || vence(c, melhor)) {
        melhor = c;
      }
    }
    if (melhor == null) {
      throw new IllegalStateException(
          "Nenhum limiar sustenta precisão >= "
              + PISO_PRECISAO
              + " na validação. O modelo não deve"
              + " ser publicado assim — reveja o gerador ou o conjunto de características.");
    }
    return melhor.limiar();
  }

  private static boolean vence(Candidato candidato, Candidato atual) {
    int porRecall = Double.compare(candidato.matriz().recall(), atual.matriz().recall());
    if (porRecall != 0) {
      return porRecall > 0;
    }
    int porPrecisao = Double.compare(candidato.matriz().precisao(), atual.matriz().precisao());
    if (porPrecisao != 0) {
      return porPrecisao > 0;
    }
    return candidato.limiar() > atual.limiar();
  }
}
