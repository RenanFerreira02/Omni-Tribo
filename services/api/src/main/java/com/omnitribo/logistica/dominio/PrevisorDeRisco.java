package com.omnitribo.logistica.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Regressão logística treinada, aplicada a uma entrega. Função pura.
 *
 * <p>Mesma forma de {@code CalculadoraDeRecompensa}: classe final, construtor privado, sem Spring,
 * sem repositório, sem relógio. Mesmas entradas, sempre a mesma saída — o determinismo é requisito,
 * não consequência, porque o multiplicador derivado daqui é CONGELADO na missão.
 *
 * <pre>
 *   logOdds = intercepto + Σ  β_j · z_j
 *   p       = sigmoide(logOdds)
 * </pre>
 *
 * <p><b>{@code StrictMath}, nunca {@code Math}.</b> {@code Math.exp} só garante erro ≤ 1 ulp e pode
 * usar intrínsecos diferentes por arquitetura de CPU e versão de JVM; {@code StrictMath} é
 * especificado bit a bit (fdlibm). Como o treinador acumula milhões de chamadas a {@code exp} em
 * somas, 1 ulp de divergência na primeira época se amplifica — e o teste que confere os
 * coeficientes publicados passaria numa máquina e falharia noutra, sem nada ter mudado. É o que
 * permite afirmar "reprodutível" sem ressalva. Trocar por {@code Math} aqui é regressão silenciosa.
 *
 * <p><b>Honestidade:</b> os coeficientes vieram de um dataset SINTÉTICO, com correlações que nós
 * mesmos injetamos e documentamos em {@code docs/qualidade/modelo-previsao.md}. Isto é um modelo
 * demonstrando um mecanismo, não um modelo validado contra a operação real — validar com dados
 * reais é o próximo passo declarado no ADR 0022.
 */
public final class PrevisorDeRisco {

  /** Quantos fatores a explicação devolve. Três cabe numa tela e é o que se defende em voz alta. */
  private static final int TOP_FATORES = 3;

  /**
   * Contribuição abaixo disto é ruído de ponto flutuante e não entra no ranking.
   *
   * <p>Comparar com {@code == 0.0} seria {@code FE_FLOATING_POINT_EQUALITY} e quebraria o build no
   * SpotBugs — mas o motivo de fundo é que uma dummy desligada pode produzir {@code -0.0} ou um
   * resíduo de multiplicação, e listá-la como "fator" seria mentira.
   */
  private static final double EPSILON_CONTRIBUICAO = 1e-12;

  private PrevisorDeRisco() {}

  /**
   * Sigmoide na forma numericamente estável.
   *
   * <p>A forma ingênua {@code 1/(1+exp(-z))} estoura para {@code z} muito negativo: {@code exp(-z)}
   * vira infinito e o resultado vira 0 por overflow, em vez de aproximar 0 por baixo. Dividir o
   * caso em dois ramos mantém o argumento de {@code exp} sempre ≤ 0.
   */
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
   * @param imputadas características cujo valor real não estava disponível. Não altera a conta — o
   *     chamador já colocou a média do treino em {@code f}, o que produz z-score 0 e contribuição
   *     nula —, mas viaja no resultado para que quem lê saiba que o score se apoiou em suposição.
   */
  public static ResultadoRisco avaliar(
      FeaturesEntrega f, ParametrosRisco p, Set<CaracteristicaRisco> imputadas) {

    double[] bruto = CodificadorEntrega.codificar(f);
    double[] z = CodificadorEntrega.padronizar(bruto, p);

    double logOdds = p.intercepto();
    List<FatorRisco> candidatos = new ArrayList<>(CaracteristicaRisco.TOTAL);
    double somaAbsoluta = 0.0;

    // Ordem do ENUM, nunca iteração de mapa: a ordem de iteração de Map.of/copyOf muda entre
    // execuções da JVM e decidiria tanto esta soma em ponto flutuante quanto o desempate do
    // ranking.
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

  /**
   * Faixa a partir dos limiares publicados.
   *
   * <p>{@code ALTO} começa exatamente no limiar de decisão do modelo — ver {@link FaixaRisco}.
   */
  private static FaixaRisco faixaDe(double probabilidade, ParametrosRisco p) {
    if (probabilidade >= p.limiarAlto()) {
      return FaixaRisco.ALTO;
    }
    return probabilidade >= p.limiarMedio() ? FaixaRisco.MEDIO : FaixaRisco.BAIXO;
  }

  /**
   * Multiplicador da recompensa em TOKEN, linear na probabilidade e SEMPRE dentro do teto.
   *
   * <p>Linear e não uma curva: "o dobro do risco paga o dobro do adicional" é a única forma que se
   * explica numa frase, e qualquer curva exigiria defender o formato dela.
   *
   * <p><b>O teto é estreito de propósito, e a razão é econômica.</b> Missões de ENTREGA hoje CUNHAM
   * token — não pagam de pote — porque o financiador correto delas é o patrocinador, que ainda não
   * existe (Pendência #1). Um multiplicador sem teto multiplicaria essa cunhagem pelo risco. Com o
   * teto, a ampliação é limitada, conhecida e documentada, em vez de decidida por acidente. Piso ≥
   * 1,0 garantido por {@link ParametrosRisco}: risco nunca REDUZ recompensa, o que inverteria a
   * tese do produto.
   */
  private static BigDecimal multiplicadorDe(double probabilidade, ParametrosRisco p) {
    double amplitude = p.multiplicadorMaximo() - p.multiplicadorMinimo();
    double bruto = p.multiplicadorMinimo() + amplitude * probabilidade;
    double limitado =
        StrictMath.min(StrictMath.max(bruto, p.multiplicadorMinimo()), p.multiplicadorMaximo());
    return BigDecimal.valueOf(limitado).setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * Os três fatores de maior peso, com o peso relativo já normalizado.
   *
   * <p>O intercepto NÃO participa: não é um fator desta entrega, é a linha-base. Ver {@link
   * ResultadoRisco#logOddsBase()}.
   */
  private static List<FatorRisco> principais(List<FatorRisco> candidatos, double somaAbsoluta) {
    Comparator<FatorRisco> porImpacto =
        Comparator.comparingDouble((FatorRisco fr) -> StrictMath.abs(fr.contribuicao()))
            .reversed()
            // Desempate explícito: List.sort é estável, mas deixar o critério implícito convidaria
            // alguém a trocar a estrutura de origem e reintroduzir não-determinismo sem perceber.
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

  /**
   * Valor BRUTO em texto, para a explicação virar português.
   *
   * <p>{@code Locale.ROOT} obrigatório: em pt_BR o separador decimal vira vírgula, e um número
   * formatado com vírgula dentro de um JSON quebra o parse do cliente.
   */
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
