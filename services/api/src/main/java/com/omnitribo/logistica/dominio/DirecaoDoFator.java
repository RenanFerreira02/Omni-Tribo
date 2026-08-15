package com.omnitribo.logistica.dominio;

/**
 * Sentido em que um fator empurra o risco.
 *
 * <p>{@code REDUZ} não é decoração: uma característica numérica ABAIXO da média do treino produz
 * contribuição negativa, e mostrá-la é o que torna a explicação honesta. "O risco não é maior
 * porque este CEP tem histórico bom" é informação acionável, não ruído.
 */
public enum DirecaoDoFator {
  AUMENTA,
  REDUZ
}
