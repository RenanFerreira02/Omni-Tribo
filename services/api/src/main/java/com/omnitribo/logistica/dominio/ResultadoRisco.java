package com.omnitribo.logistica.dominio;

import java.math.BigDecimal;
import java.util.List;

/**
 * Veredito do modelo para uma entrega: probabilidade, faixa, e POR QUÊ.
 *
 * <p><b>Aqui é a fronteira onde a medida vira economia.</b> O modelo calcula em {@code double}
 * porque nada nele é dinheiro; {@code probabilidadeFalha} e {@code multiplicadorRecompensa} saem em
 * {@code BigDecimal} nas escalas exatas das colunas que os recebem ({@code NUMERIC(5,4)} em {@code
 * entrega_falida.risco_probabilidade} e {@code NUMERIC(4,2)} em {@code
 * missao.multiplicador_risco}), para que a conversão aconteça UMA vez, num lugar identificável, e
 * não espalhada por chamadores.
 *
 * @param logOdds soma do intercepto com todas as contribuições. Exposto para que a identidade
 *     {@code logOddsBase + Σ contribuições = logOdds} seja verificável por teste — é o que separa
 *     uma explicação auditável de uma decorativa.
 * @param logOddsBase o intercepto, isolado. <b>Não é um fator desta entrega</b> e por isso fica
 *     fora de {@code fatoresPrincipais}: é o log-odds de uma entrega média, num endereço
 *     residencial, numa manhã de dia útil. Incluí-lo no ranking faria o intercepto — tipicamente o
 *     maior valor absoluto — aparecer como "principal fator de risco" em toda previsão.
 * @param featuresImputadas características cujo valor real não estava disponível e foi substituído
 *     pela média do treino. Vazia no caminho feliz; contém {@code CHUVA_MM}/{@code TEMPERATURA_C}
 *     quando o provedor de clima não respondeu. Sai na resposta de propósito: um score que se
 *     apoiou em imputação é menos confiável, e esconder isso seria desonesto com quem lê.
 */
public record ResultadoRisco(
    BigDecimal probabilidadeFalha,
    FaixaRisco faixaRisco,
    BigDecimal multiplicadorRecompensa,
    List<FatorRisco> fatoresPrincipais,
    double logOdds,
    double logOddsBase,
    int versaoModelo,
    List<String> featuresImputadas) {

  /**
   * Cópias defensivas das listas.
   *
   * <p>Sem elas o SpotBugs acusa {@code EI_EXPOSE_REP} e o build quebra — mas a razão de fundo é
   * que este record é congelado em banco: um chamador mutando a lista depois da construção mudaria
   * o que foi gravado.
   */
  public ResultadoRisco {
    fatoresPrincipais = List.copyOf(fatoresPrincipais);
    featuresImputadas = List.copyOf(featuresImputadas);
  }
}
