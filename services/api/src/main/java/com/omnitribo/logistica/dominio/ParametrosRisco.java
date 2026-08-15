package com.omnitribo.logistica.dominio;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * O ARTEFATO TREINADO do modelo de risco: coeficientes, padronização e limiares.
 *
 * <p>Mesma divisão de {@code ParametrosRecompensa}: o MODELO é código ({@link PrevisorDeRisco},
 * {@link CodificadorEntrega}, {@link CaracteristicaRisco}), estes NÚMEROS são configuração. A
 * diferença é que aqui os números não foram escolhidos por calibração humana — saíram de {@code
 * TreinadorRegressaoLogistica} sobre o dataset sintético de semente fixa, e {@code
 * ModeloRiscoTreinoTest} re-treina no {@code verify} e confere cada um contra o que está publicado.
 * <b>Editar um coeficiente à mão quebra o build</b>, de propósito.
 *
 * <p><b>{@code versao} é obrigatória porque o score é CONGELADO.</b> O multiplicador derivado daqui
 * é gravado em {@code missao.multiplicador_risco} e a probabilidade em {@code
 * entrega_falida.risco_probabilidade}. Sem a versão, some a resposta para "este multiplicador
 * estava certo quando foi aplicado?" — exatamente o papel de {@code versao_formula} na recompensa.
 * Mudou qualquer número abaixo? Suba a versão junto.
 *
 * <p>Chave de enum nos mapas, e não {@code String}, é decisão de segurança de configuração: um nome
 * de característica digitado errado no YAML <b>derruba o boot</b>, em vez de ser lido como
 * coeficiente ausente e virar zero silencioso — que produziria previsões erradas para sempre sem
 * ninguém notar.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification =
        "Os acessores devolvem mapas JÁ imutáveis: o construtor compacto copia para EnumMap e"
            + " embrulha em Collections.unmodifiableMap, então não há representação interna a"
            + " expor. O SpotBugs não reconhece esse par como imutável — reconhece Map.copyOf —, e"
            + " trocar por Map.copyOf silenciaria o aviso ao custo de perder a ordem de iteração"
            + " determinística do EnumMap. Map.of/copyOf randomizam a ordem A CADA EXECUÇÃO DA JVM"
            + " (ImmutableCollections.SALT), e embora este módulo leia os mapas só por chave, a"
            + " ordem estável é a rede de proteção para quem iterar no futuro sem saber disso.")
@ConfigurationProperties(prefix = "app.logistica.risco")
public record ParametrosRisco(
    int versao,
    double intercepto,
    Map<CaracteristicaRisco, Double> coeficientes,
    Map<CaracteristicaRisco, Padronizacao> padronizacao,
    double limiarAlto,
    double limiarMedio,
    Map<String, Double> taxaPorFaixaCep,
    double taxaCepPadrao,
    double multiplicadorMinimo,
    double multiplicadorMaximo) {

  /**
   * Média e desvio-padrão de uma característica numérica, medidos <b>só na partição de treino</b>.
   *
   * <p>Calcular sobre o dataset inteiro seria vazamento do conjunto de teste para dentro do modelo,
   * e inflaria as métricas reportadas. A média também é o valor de IMPUTAÇÃO: uma característica
   * ausente entra com z-score exatamente 0, ou seja, contribuição zero — o modelo passa a não ter
   * opinião sobre ela, que é o comportamento correto quando o dado não existe.
   */
  public record Padronizacao(double media, double desvio) {}

  public ParametrosRisco {
    coeficientes = copiaImutavel(coeficientes);
    padronizacao = copiaImutavelPadronizacao(padronizacao);
    taxaPorFaixaCep = Map.copyOf(taxaPorFaixaCep);

    for (CaracteristicaRisco c : CaracteristicaRisco.values()) {
      if (!coeficientes.containsKey(c)) {
        throw new IllegalArgumentException(
            "app.logistica.risco.coeficientes sem entrada para "
                + c
                + ". Configuração incompleta"
                + " produziria previsão silenciosamente errada — o boot falha de propósito.");
      }
      if (c.numerica() && !padronizacao.containsKey(c)) {
        throw new IllegalArgumentException(
            "app.logistica.risco.padronizacao sem entrada para a característica numérica " + c);
      }
    }
    for (Map.Entry<CaracteristicaRisco, Padronizacao> e : padronizacao.entrySet()) {
      if (!(e.getValue().desvio() > 0.0)) {
        throw new IllegalArgumentException(
            "Desvio-padrão de " + e.getKey() + " deve ser positivo — divisão por zero no z-score.");
      }
    }
    if (!(limiarMedio > 0.0) || !(limiarMedio < limiarAlto) || !(limiarAlto < 1.0)) {
      throw new IllegalArgumentException(
          "Limiares devem satisfazer 0 < limiarMedio < limiarAlto < 1, vieram "
              + limiarMedio
              + " e "
              + limiarAlto);
    }
    if (!(multiplicadorMinimo >= 1.0) || !(multiplicadorMaximo >= multiplicadorMinimo)) {
      throw new IllegalArgumentException(
          "Exige 1,0 <= multiplicadorMinimo <= multiplicadorMaximo. Mínimo abaixo de 1 faria o risco"
              + " REDUZIR a recompensa, invertendo a tese do produto.");
    }
  }

  /**
   * {@code EnumMap} e não {@code Map.copyOf}, e a diferença é a razão de este método existir.
   *
   * <p>{@code Map.of}/{@code Map.copyOf} randomizam a ordem de iteração <b>a cada execução da
   * JVM</b> ({@code ImmutableCollections.SALT}, semeado do relógio). O código deste módulo nunca
   * itera estes mapas — lê sempre por chave, e o vetor é montado pela ordem de {@code
   * CaracteristicaRisco.values()} — mas garantir ordem estável aqui remove a classe inteira de
   * defeito, caso alguém no futuro itere sem saber disso. {@code EnumMap} itera pelo ordinal.
   */
  private static Map<CaracteristicaRisco, Double> copiaImutavel(
      Map<CaracteristicaRisco, Double> origem) {
    EnumMap<CaracteristicaRisco, Double> copia = new EnumMap<>(CaracteristicaRisco.class);
    copia.putAll(origem);
    return java.util.Collections.unmodifiableMap(copia);
  }

  private static Map<CaracteristicaRisco, Padronizacao> copiaImutavelPadronizacao(
      Map<CaracteristicaRisco, Padronizacao> origem) {
    EnumMap<CaracteristicaRisco, Padronizacao> copia = new EnumMap<>(CaracteristicaRisco.class);
    copia.putAll(origem);
    return java.util.Collections.unmodifiableMap(copia);
  }

  /** Coeficiente da característica. Ausência é defeito de configuração, não caso de uso. */
  public double coeficiente(CaracteristicaRisco c) {
    Double valor = coeficientes.get(c);
    if (valor == null) {
      throw new IllegalStateException("Sem coeficiente publicado para " + c);
    }
    return valor;
  }

  /**
   * Taxa histórica de falha da faixa de CEP, pelos 3 primeiros dígitos.
   *
   * <p>CEP desconhecido cai em {@code taxaCepPadrao}, que é a média do treino — ou seja, z-score 0
   * e contribuição nula. É o comportamento honesto: sem histórico daquela faixa, o modelo não deve
   * ter opinião sobre ela, nem para mais nem para menos.
   */
  public double taxaDaFaixaDeCep(String cep) {
    if (cep == null || cep.length() < 3) {
      return taxaCepPadrao;
    }
    return taxaPorFaixaCep.getOrDefault(cep.substring(0, 3), taxaCepPadrao);
  }
}
