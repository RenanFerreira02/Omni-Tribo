package com.omnitribo.missoes.dominio;

/**
 * Esforço estimado de uma missão. Entra na fórmula de recompensa como multiplicador.
 *
 * <p>Três níveis, e não uma escala contínua, de propósito: o criador precisa conseguir declarar
 * isto quando não há peso nem volume para derivar, e "quão pesado é um mutirão de pintura numa
 * escala de 1 a 10" não tem resposta defensável. Três níveis são disputáveis por quem executa.
 *
 * <p>Quando peso e volume existem, quem escolhe é o servidor — ver {@code
 * CalculadoraDeRecompensa.derivarComplexidade}.
 */
public enum ComplexidadeMissao {
  LEVE,
  MEDIA,
  PESADA
}
