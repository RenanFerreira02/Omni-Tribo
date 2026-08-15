package com.omnitribo.logistica.dominio;

/**
 * Faixa de risco exibida ao usuário, derivada da probabilidade pelos limiares publicados.
 *
 * <p><b>As fronteiras NÃO são inventadas: derivam do limiar de decisão do modelo.</b> {@code ALTO}
 * começa exatamente no limiar que classifica a entrega como "vai falhar", e {@code MEDIO} na metade
 * dele. Esse alinhamento é o que impede a interface de dizer "risco ALTO" num caso que o próprio
 * modelo classificou como sucesso — uma contradição que o usuário veria e que destruiria a
 * confiança na explicação.
 */
public enum FaixaRisco {
  BAIXO,
  MEDIO,
  ALTO
}
