package com.omnitribo.logistica.treino;

import com.omnitribo.logistica.dominio.FeaturesEntrega;

/**
 * Uma linha do dataset sintético: as características e o desfecho.
 *
 * @param probabilidadeVerdadeira o {@code p} de onde o rótulo foi sorteado. <b>Não é oferecida ao
 *     modelo</b> — existe só para o documento de métricas poder mostrar o erro de Bayes
 *     irredutível, ou seja, o teto que nenhum modelo poderia ultrapassar neste dataset.
 * @param motoristaExperiente a VARIÁVEL OMITIDA. Entra no log-odds verdadeiro e não é oferecida ao
 *     modelo, simulando o que sempre acontece na operação real: parte do que explica a falha não
 *     está registrada no sistema.
 */
record AmostraEntrega(
    FeaturesEntrega features,
    boolean falhou,
    double probabilidadeVerdadeira,
    boolean motoristaExperiente,
    boolean rotuloInvertido) {

  int rotulo() {
    return falhou ? 1 : 0;
  }
}
