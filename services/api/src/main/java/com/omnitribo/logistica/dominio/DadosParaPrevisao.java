package com.omnitribo.logistica.dominio;

import java.time.DayOfWeek;

/**
 * Insumos de uma previsão como o CHAMADOR os tem, antes de qualquer resolução ou imputação.
 *
 * <p>Diferente de {@link FeaturesEntrega} em dois pontos, e os dois são deliberados: aqui o CEP
 * ainda é CEP (a taxa histórica é resolvida da configuração por {@link PrevisaoRiscoService}), e o
 * clima é opcional. Manter {@code FeaturesEntrega} sem nulos é o que permite {@link
 * PrevisorDeRisco} ser função pura sem nenhum ramo de ausência no meio da conta.
 *
 * @param chuvaMm nulo quando o provedor externo de clima não respondeu. Vira a média do treino.
 * @param temperaturaC idem.
 */
public record DadosParaPrevisao(
    int horaDoDia,
    DayOfWeek diaSemana,
    TipoEndereco tipoEndereco,
    String cep,
    double pesoKg,
    double volumeL,
    Double chuvaMm,
    Double temperaturaC,
    int tentativasAnteriores) {}
