package com.omnitribo.logistica.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Regressão logística treinada, aplicada a uma entrega. Função pura e determinística — o
 * multiplicador derivado daqui é CONGELADO na missão (ADR 0022).
 *
 * <pre>
 *   logOdds = intercepto + Σ  β_j · z_j
 *   p       = sigmoide(logOdds)
 * </pre>
 *
 * <p><b>{@code StrictMath}, nunca {@code Math}</b>: {@code Math.exp} pode variar 1 ulp por CPU/JVM,
 * e o treino amplifica isso — o teste dos coeficientes passaria numa máquina e falharia noutra.
 *
 * <p>Coeficientes treinados em dados SINTÉTICOS ({@code docs/MODELO-RISCO.md}).
 */
public final class PrevisorDeRisco {

  /** Quantos fatores a explicação devolve. Três cabe numa tela e é o que se defende em voz alta. */
  private static final int TOP_FATORES = 3;

  /** Contribuição abaixo disto é ruído de ponto flutuante e não entra no ranking dos fatores. */
  private static final double EPSILON_CONTRIBUICAO = 1e-12;

  private PrevisorDeRisco() {}

  /** Sigmoide estável: os dois ramos mantêm o argumento de {@code exp} ≤ 0, sem overflow. */
  public static double sigmoide(double z) {
    if (z >= 0.0) {
      return 1.0 / (1.0 + StrictMath.exp(-z));
    }
    double e = StrictMath.exp(z);
    return e / (1.0 + e);
  }

  /** Avalia sem nenhuma característica imputada — o caminho com dado completo. */
  public static ResultadoRisco avaliar(FeaturesEntrega f, ParametrosRisco p) {
    return avaliar(f, p, Set.of());
  }

  /**
   * Avalia a entrega e explica o resultado.
   *
   * @param imputadas características sem valor real (o chamador pôs a média do treino, contribuição
   *     zero); viajam no resultado para dizer que o score se apoiou em suposição.
   */
  public static ResultadoRisco avaliar(
      FeaturesEntrega f, ParametrosRisco p, Set<CaracteristicaRisco> imputadas) {

    double[] bruto = CodificadorEntrega.codificar(f);
    double[] z = CodificadorEntrega.padronizar(bruto, p);

    double logOdds = p.intercepto();
    List<FatorRisco> candidatos = new ArrayList<>(CaracteristicaRisco.TOTAL);
    double somaAbsoluta = 0.0;

    // Ordem do ENUM, não de mapa: a ordem de Map muda entre execuções e mudaria soma e desempate.
    for (CaracteristicaRisco c : CaracteristicaRisco.values()) {
      double contribuicao = p.coeficiente(c) * z[c.indice()];
      logOdds += contribuicao;
      somaAbsoluta += StrictMath.abs(contribuicao);
      candidatos.add(
          new FatorRisco(
              c,
              c.rotulo(),
              contribuicao,
              contribuicao >= 0.0 ? DirecaoDoFator.AUMENTA : DirecaoDoFator.REDUZ,
              0.0, // peso relativo depende da soma total; preenchido abaixo
              descrever(c, bruto[c.indice()])));
    }

    double probabilidade = sigmoide(logOdds);

    return new ResultadoRisco(
        BigDecimal.valueOf(probabilidade).setScale(4, RoundingMode.HALF_UP),
        faixaDe(probabilidade, p),
        multiplicadorDe(probabilidade, p),
        principais(candidatos, somaAbsoluta),
        logOdds,
        p.intercepto(),
        p.versao(),
        imputadas.stream().map(Enum::name).sorted().toList());
  }

  /** Faixa pelos limiares publicados; ALTO começa no limiar de decisão ({@link FaixaRisco}). */
  private static FaixaRisco faixaDe(double probabilidade, ParametrosRisco p) {
    if (probabilidade >= p.limiarAlto()) {
      return FaixaRisco.ALTO;
    }
    return probabilidade >= p.limiarMedio() ? FaixaRisco.MEDIO : FaixaRisco.BAIXO;
  }

  /**
   * Multiplicador da recompensa em TOKEN: linear na probabilidade ("o dobro do risco paga o dobro
   * do adicional" se explica numa frase) e sempre em [1,00; 1,50]. O piso garante que risco nunca
   * reduz a recompensa; o teto limita quanto a transportadora paga por uma conversão. Mexer no
   * valor é recalibração de fórmula e exige subir {@code versao}.
   */
  private static BigDecimal multiplicadorDe(double probabilidade, ParametrosRisco p) {
    double amplitude = p.multiplicadorMaximo() - p.multiplicadorMinimo();
    double bruto = p.multiplicadorMinimo() + amplitude * probabilidade;
    double limitado =
        StrictMath.min(StrictMath.max(bruto, p.multiplicadorMinimo()), p.multiplicadorMaximo());
    return BigDecimal.valueOf(limitado).setScale(2, RoundingMode.HALF_UP);
  }

  /** Os três fatores de maior peso, normalizados. O intercepto é a linha-base, não um fator. */
  private static List<FatorRisco> principais(List<FatorRisco> candidatos, double somaAbsoluta) {
    Comparator<FatorRisco> porImpacto =
        Comparator.comparingDouble((FatorRisco fr) -> StrictMath.abs(fr.contribuicao()))
            .reversed()
            // Desempate explícito, para trocar a estrutura de origem não reintroduzir
            // não-determinismo.
            .thenComparing(fr -> fr.caracteristica().ordinal());

    return candidatos.stream()
        .filter(fr -> StrictMath.abs(fr.contribuicao()) >= EPSILON_CONTRIBUICAO)
        .sorted(porImpacto)
        .limit(TOP_FATORES)
        .map(fr -> comPesoRelativo(fr, somaAbsoluta))
        .toList();
  }

  private static FatorRisco comPesoRelativo(FatorRisco fr, double somaAbsoluta) {
    double peso =
        somaAbsoluta >= EPSILON_CONTRIBUICAO
            ? StrictMath.abs(fr.contribuicao()) / somaAbsoluta
            : 0.0;
    return new FatorRisco(
        fr.caracteristica(),
        fr.rotulo(),
        fr.contribuicao(),
        fr.direcao(),
        peso,
        fr.valorObservado());
  }

  /** Valor bruto em texto. {@code Locale.ROOT}: em pt_BR a vírgula decimal quebraria o JSON. */
  private static String descrever(CaracteristicaRisco c, double valorBruto) {
    return switch (c) {
      case JANELA_MADRUGADA,
          JANELA_TARDE,
          JANELA_NOITE,
          ENDERECO_COMERCIAL,
          ENDERECO_CONDOMINIO,
          ENDERECO_RURAL,
          FIM_DE_SEMANA,
          COMERCIAL_EM_FIM_DE_SEMANA ->
          valorBruto >= 0.5 ? "sim" : "não";
      case TAXA_HISTORICA_CEP ->
          String.format(Locale.ROOT, "%.1f%% de falha histórica", valorBruto * 100.0);
      case PESO_KG -> String.format(Locale.ROOT, "%.1f kg", valorBruto);
      case VOLUME_L -> String.format(Locale.ROOT, "%.0f L", valorBruto);
      case CHUVA_MM -> String.format(Locale.ROOT, "%.0f mm", valorBruto);
      case TEMPERATURA_C -> String.format(Locale.ROOT, "%.0f °C", valorBruto);
      case TENTATIVAS_ANTERIORES -> String.format(Locale.ROOT, "%.0f tentativa(s)", valorBruto);
    };
  }
}
