package com.omnitribo.missoes.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Deriva a recompensa de uma missão a partir das suas características.
 *
 * <h2>Por que existe</h2>
 *
 * <p>Até o ADR 0009 a recompensa vinha do CLIENTE: {@code CriarMissaoRequest} tinha {@code
 * xpRecompensa} e {@code tokensRecompensa}, e o único controle era um {@code @Max}. Medido contra a
 * API: uma missão AJUDA sem peso, sem volume e sem destino foi criada com <b>5.000 XP e 1.000
 * tokens</b> — o teto. Um teto sem fórmula significa que toda missão pode valer o teto, e a única
 * variável é a vontade de quem cria. Com ENTREGA e AJUDA ainda cunhando token (lacuna do §4.4, até
 * o patrocinador da F8), isso era emissão sem contrapartida: 656 → 2.656 tokens em dois ciclos.
 *
 * <p>Isto não fecha a cunhagem — fecha o <b>arbítrio sobre o tamanho dela</b>.
 *
 * <h2>Função pura, e o que isso compra</h2>
 *
 * <p>Sem Spring, sem repositório, sem relógio: mesmas entradas, mesma saída, sempre. É o que
 * permite testar monotonicidade e faixa varrendo milhares de combinações em milissegundos, e é o
 * que torna a fórmula versionável — uma função que consulta o banco não pode ser reexecutada no
 * passado para auditar um crédito antigo. Mesmo molde de {@link RegraNivel} e de {@code
 * AvaliacaoAntifraude}.
 *
 * <p>A distância chega pronta, em metros, medida pelo PostGIS — nunca calculada aqui. É a mesma
 * convenção de {@code AvaliacaoAntifraude}, cujo javadoc registra "distância medida pelo PostGIS,
 * nunca informada pelo cliente". Quem mede é {@code ConsultasGeoespaciais.distanciaMetros}.
 *
 * <h2>A fórmula</h2>
 *
 * <pre>
 *   tokens = min(teto, base(categoria) × mult(complexidade)
 *                      + km × tokensPorKm
 *                      + kg × tokensPorKg
 *                      + (litros / 100) × tokensPorCemLitros)
 *   xp     = min(tetoXp, tokens × xpPorToken)
 * </pre>
 *
 * <p>Base multiplicada e adicionais somados, e não tudo multiplicado: multiplicar a distância pela
 * complexidade faria uma entrega pesada e longa explodir de forma não linear, e a soma mantém cada
 * fator legível para quem for contestar o valor.
 */
public final class CalculadoraDeRecompensa {

  private static final BigDecimal METROS_POR_KM = new BigDecimal("1000");
  private static final BigDecimal LITROS_POR_UNIDADE_DE_VOLUME = new BigDecimal("100");

  private CalculadoraDeRecompensa() {}

  /**
   * Insumos da fórmula.
   *
   * @param complexidadeDeclarada usada apenas quando peso e volume são ambos nulos; ignorada em
   *     favor da derivação quando há dado. O verificador de request recusa envio redundante, então
   *     um valor aqui com peso e volume presentes não deveria chegar — mas se chegar, a derivação
   *     vence, porque dado objetivo ganha de declaração.
   * @param distanciaM distância origem→destino em metros, medida pelo PostGIS. Nula quando a missão
   *     não tem destino, o que é sempre o caso em TRIBO.
   */
  public record Insumos(
      CategoriaMissao categoria,
      ComplexidadeMissao complexidadeDeclarada,
      BigDecimal pesoKg,
      BigDecimal volumeL,
      Double distanciaM) {}

  /**
   * Resultado, com a complexidade EFETIVA — derivada ou declarada.
   *
   * <p>Devolver a complexidade, e não só os números, é o que permite ao app explicar o valor ao
   * usuário e ao servidor persistir a mesma coisa que mostrou na prévia.
   */
  public record Recompensa(
      int xp, long tokens, ComplexidadeMissao complexidade, int versaoFormula) {}

  /** Calcula a recompensa. Determinística: mesmas entradas, mesma saída. */
  public static Recompensa calcular(Insumos insumos, ParametrosRecompensa p) {
    ComplexidadeMissao complexidade = complexidadeEfetiva(insumos, p);

    Long base = p.baseTokens().get(insumos.categoria());
    BigDecimal multiplicador = p.multiplicadorComplexidade().get(complexidade);
    if (base == null || multiplicador == null) {
      // Configuração incompleta é erro de operação, não entrada inválida: falhar alto aqui é melhor
      // que produzir zero e criar uma missão sem recompensa que ninguém aceitaria.
      throw new IllegalStateException(
          "Parâmetros de recompensa sem entrada para categoria "
              + insumos.categoria()
              + " ou complexidade "
              + complexidade
              + ".");
    }

    BigDecimal total = new BigDecimal(base).multiply(multiplicador);
    total = total.add(adicionalDistancia(insumos.distanciaM(), p));
    total = total.add(adicionalPeso(insumos.pesoKg(), p));
    total = total.add(adicionalVolume(insumos.volumeL(), p));

    // HALF_UP e não truncamento: truncar tornaria a fórmula não monotônica em passos pequenos —
    // dois pesos diferentes cairiam no mesmo inteiro e um aumento de insumo não aumentaria nada.
    long tokens = Math.min(total.setScale(0, RoundingMode.HALF_UP).longValue(), p.tetoTokens());
    // Piso de 1: missão que não recompensa nada não é missão. Só acontece com base zerada na
    // configuração, mas o piso evita que um erro de calibração produza trabalho de graça.
    tokens = Math.max(tokens, 1L);

    int xp = (int) Math.min((long) tokens * p.xpPorToken(), p.tetoXp());

    return new Recompensa(xp, tokens, complexidade, p.versao());
  }

  /**
   * Complexidade efetiva: derivada quando há peso E volume, declarada quando não há.
   *
   * <p>A assimetria é deliberada. Onde existe dado objetivo, o criador não opina — senão a
   * complexidade viraria o mesmo arbítrio que a recompensa livre era, só com três degraus. Onde não
   * existe (mutirão, ajuda sem carga), declarar é a única opção honesta, e o teto por missão limita
   * o dano.
   */
  public static ComplexidadeMissao complexidadeEfetiva(Insumos insumos, ParametrosRecompensa p) {
    if (insumos.pesoKg() != null && insumos.volumeL() != null) {
      return derivarComplexidade(insumos.pesoKg(), insumos.volumeL(), p);
    }
    return insumos.complexidadeDeclarada() != null
        ? insumos.complexidadeDeclarada()
        : ComplexidadeMissao.LEVE;
  }

  /**
   * Deriva a complexidade de peso e volume.
   *
   * <p>É o MAIOR dos dois que decide: uma caixa de isopor de 2 m³ pesa pouco e continua não cabendo
   * numa moto. Usar a média deixaria o volume ser diluído pelo peso baixo.
   */
  public static ComplexidadeMissao derivarComplexidade(
      BigDecimal pesoKg, BigDecimal volumeL, ParametrosRecompensa p) {
    boolean leve =
        pesoKg.compareTo(p.pesoLeveAteKg()) <= 0 && volumeL.compareTo(p.volumeLeveAteL()) <= 0;
    if (leve) {
      return ComplexidadeMissao.LEVE;
    }
    boolean media =
        pesoKg.compareTo(p.pesoMediaAteKg()) <= 0 && volumeL.compareTo(p.volumeMediaAteL()) <= 0;
    return media ? ComplexidadeMissao.MEDIA : ComplexidadeMissao.PESADA;
  }

  private static BigDecimal adicionalDistancia(Double distanciaM, ParametrosRecompensa p) {
    if (distanciaM == null || distanciaM <= 0) {
      return BigDecimal.ZERO;
    }
    return BigDecimal.valueOf(distanciaM)
        .divide(METROS_POR_KM, 4, RoundingMode.HALF_UP)
        .multiply(p.tokensPorKm());
  }

  private static BigDecimal adicionalPeso(BigDecimal pesoKg, ParametrosRecompensa p) {
    return pesoKg == null ? BigDecimal.ZERO : pesoKg.multiply(p.tokensPorKg());
  }

  private static BigDecimal adicionalVolume(BigDecimal volumeL, ParametrosRecompensa p) {
    return volumeL == null
        ? BigDecimal.ZERO
        : volumeL
            .divide(LITROS_POR_UNIDADE_DE_VOLUME, 4, RoundingMode.HALF_UP)
            .multiply(p.tokensPorCemLitros());
  }
}
