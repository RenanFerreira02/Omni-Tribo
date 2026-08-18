package com.omnitribo.logistica.dominio;

import java.time.DayOfWeek;

/**
 * Insumos BRUTOS de uma tentativa de entrega, na forma que o modelo consome.
 *
 * <p>Já vem com {@code taxaHistoricaCep} RESOLVIDA, e não com o CEP: resolver prefixo → taxa exige
 * ler configuração, e {@link PrevisorDeRisco} é função pura. Quem resolve é {@code
 * PrevisaoRiscoService}, na borda. O mesmo vale para o clima, que chega aqui como número — imputado
 * quando o provedor externo não respondeu.
 *
 * <p>{@code double} e não {@code BigDecimal}, e a distinção não é descuido: a regra do projeto
 * ("nunca double") protege DINHEIRO e TOKEN, que participam de uma invariante de conservação
 * conferida pelo endpoint de reconciliação. Nada aqui é dinheiro — são medidas, na mesma categoria
 * de {@code ConsultasGeoespaciais.distanciaMetros}, que já é {@code double}. A conversão para
 * {@code BigDecimal} acontece exatamente onde o score vira economia, em {@code ResultadoRisco}.
 */
public record FeaturesEntrega(
    int horaDoDia,
    DayOfWeek diaSemana,
    TipoEndereco tipoEndereco,
    double taxaHistoricaCep,
    double pesoKg,
    double volumeL,
    double chuvaMm,
    double temperaturaC,
    int tentativasAnteriores) {

  public FeaturesEntrega {
    if (horaDoDia < 0 || horaDoDia > 23) {
      throw new IllegalArgumentException("horaDoDia deve estar entre 0 e 23, veio " + horaDoDia);
    }
    if (diaSemana == null || tipoEndereco == null) {
      throw new IllegalArgumentException("diaSemana e tipoEndereco são obrigatórios");
    }
    if (tentativasAnteriores < 0) {
      throw new IllegalArgumentException("tentativasAnteriores não pode ser negativo");
    }
    exigirFinitoNaoNegativo(taxaHistoricaCep, "taxaHistoricaCep");
    exigirFinitoNaoNegativo(pesoKg, "pesoKg");
    exigirFinitoNaoNegativo(volumeL, "volumeL");
    exigirFinitoNaoNegativo(chuvaMm, "chuvaMm");
    if (!Double.isFinite(temperaturaC)) {
      throw new IllegalArgumentException("temperaturaC deve ser finita");
    }
  }

  /**
   * NaN e infinito precisam ser barrados na entrada.
   *
   * <p>NaN se propaga silenciosamente por toda a aritmética do modelo: o log-odds vira NaN, a
   * sigmoide vira NaN, e a comparação com o limiar devolve {@code false} — produzindo "risco BAIXO"
   * para uma entrada corrompida, que é o pior desfecho possível. Falhar aqui é alto e visível.
   */
  private static void exigirFinitoNaoNegativo(double valor, String campo) {
    if (!Double.isFinite(valor) || valor < 0.0) {
      throw new IllegalArgumentException(campo + " deve ser finito e não negativo, veio " + valor);
    }
  }

  /** Sábado e domingo. É o recorte do dia da semana que carrega o sinal. */
  public boolean fimDeSemana() {
    return diaSemana == DayOfWeek.SATURDAY || diaSemana == DayOfWeek.SUNDAY;
  }
}
