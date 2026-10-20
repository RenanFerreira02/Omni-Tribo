package com.omnitribo.missoes.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Deriva a recompensa de uma missão a partir das suas características (ADR 0009).
 *
 * <p>Função pura — sem Spring, banco nem relógio: mesmas entradas, mesma saída. É o que torna a
 * fórmula testável em massa e versionável. A distância chega pronta, medida pelo PostGIS.
 *
 * <pre>
 *   tokens = min(teto, base(categoria) × mult(complexidade) × mult(risco)
 *                      + km × tokensPorKm
 *                      + kg × tokensPorKg
 *                      + (litros / 100) × tokensPorCemLitros)
 *   xp     = min(tetoXp, tokens × xpPorToken)
 * </pre>
 *
 * <p>Base multiplicada e adicionais somados: multiplicar tudo faria a entrega pesada e longa
 * explodir de forma não linear, e a soma mantém cada fator legível para quem contestar o valor.
 */
public final class CalculadoraDeRecompensa {

  private static final BigDecimal METROS_POR_KM = new BigDecimal("1000");
  private static final BigDecimal LITROS_POR_UNIDADE_DE_VOLUME = new BigDecimal("100");

  private CalculadoraDeRecompensa() {}

  /**
   * Insumos da fórmula.
   *
   * @param complexidadeDeclarada usada só sem peso e volume; com eles, a derivação vence.
   * @param distanciaM origem→destino em metros, medida pelo PostGIS; nula sem destino.
   * @param valorOfertadoBrl o que a TRANSPORTADORA declara custear na entrega falida; nulo em
   *     missão de usuário (quem cria não paga). É só INSUMO: aumenta a recompensa em TOKEN e nunca
   *     vai para {@code valor_brl}.
   */
  public record Insumos(
      CategoriaMissao categoria,
      ComplexidadeMissao complexidadeDeclarada,
      BigDecimal pesoKg,
      BigDecimal volumeL,
      Double distanciaM,
      BigDecimal valorOfertadoBrl,
      BigDecimal multiplicadorRisco) {

    /** Missão criada por usuário: sem valor ofertado e sem risco avaliado. */
    public Insumos(
        CategoriaMissao categoria,
        ComplexidadeMissao complexidadeDeclarada,
        BigDecimal pesoKg,
        BigDecimal volumeL,
        Double distanciaM,
        BigDecimal valorOfertadoBrl) {
      this(categoria, complexidadeDeclarada, pesoKg, volumeL, distanciaM, valorOfertadoBrl, null);
    }
  }

  /**
   * Resultado, com a complexidade EFETIVA — o app explica o valor e o servidor persiste o que
   * mostrou.
   *
   * @param multiplicadorRisco o fator aplicado depois do clamp (1,00 sem avaliação), congelado na
   *     missão para o crédito continuar explicável.
   */
  public record Recompensa(
      int xp,
      long tokens,
      ComplexidadeMissao complexidade,
      int versaoFormula,
      BigDecimal multiplicadorRisco) {

    /**
     * A mesma recompensa sem a parte em token, para a missão só-XP (ADR 0035). Zera DEPOIS de
     * calcular: o XP deriva dos tokens, e zerar antes zeraria os dois. Versão e complexidade ficam.
     */
    public Recompensa semToken() {
      return new Recompensa(xp, 0L, complexidade, versaoFormula, multiplicadorRisco);
    }
  }

  /** Calcula a recompensa. Determinística: mesmas entradas, mesma saída. */
  public static Recompensa calcular(Insumos insumos, ParametrosRecompensa p) {
    ComplexidadeMissao complexidade = complexidadeEfetiva(insumos, p);

    Long base = p.baseTokens().get(insumos.categoria());
    BigDecimal multiplicador = p.multiplicadorComplexidade().get(complexidade);
    if (base == null || multiplicador == null) {
      // Configuração incompleta é erro de operação: falhar alto é melhor que missão sem recompensa.
      throw new IllegalStateException(
          "Parâmetros de recompensa sem entrada para categoria "
              + insumos.categoria()
              + " ou complexidade "
              + complexidade
              + ".");
    }

    // O risco multiplica a BASE, nunca o total: no total ele escalaria distância, peso e volume e a
    // recompensa explodiria justamente no caso extremo (ADR 0022).
    BigDecimal risco = multiplicadorDeRiscoEfetivo(insumos.multiplicadorRisco(), p);
    BigDecimal total = new BigDecimal(base).multiply(multiplicador).multiply(risco);
    total = total.add(adicionalDistancia(insumos.distanciaM(), p));
    total = total.add(adicionalPeso(insumos.pesoKg(), p));
    total = total.add(adicionalVolume(insumos.volumeL(), p));
    total = total.add(adicionalValorOfertado(insumos.valorOfertadoBrl(), p));

    // HALF_UP, não truncamento: truncar tornaria a fórmula não monotônica em passos pequenos.
    long tokens = Math.min(total.setScale(0, RoundingMode.HALF_UP).longValue(), p.tetoTokens());
    // Piso de 1: um erro de calibração não pode produzir trabalho de graça.
    tokens = Math.max(tokens, 1L);

    int xp = (int) Math.min((long) tokens * p.xpPorToken(), p.tetoXp());

    return new Recompensa(xp, tokens, complexidade, p.versao(), risco);
  }

  /**
   * Multiplicador de risco aplicado, no intervalo [1,00; teto]. Ausente vira 1,00 — risco
   * desconhecido não paga mais. Segunda barreira depois do {@code PrevisorDeRisco}: esta é a última
   * função pura antes do congelamento, e não confia em quem a chamou.
   */
  private static BigDecimal multiplicadorDeRiscoEfetivo(
      BigDecimal informado, ParametrosRecompensa p) {
    if (informado == null) {
      return p.multiplicadorRiscoMinimo();
    }
    if (informado.compareTo(p.multiplicadorRiscoMinimo()) < 0) {
      return p.multiplicadorRiscoMinimo();
    }
    if (informado.compareTo(p.multiplicadorRiscoMaximo()) > 0) {
      return p.multiplicadorRiscoMaximo();
    }
    return informado;
  }

  /**
   * Complexidade efetiva: derivada com peso E volume, declarada sem eles. Onde há dado objetivo o
   * criador não opina; onde não há, declarar é a única opção honesta e o teto limita o dano.
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
   * Deriva de peso e volume — vale o MAIOR dos dois: isopor de 2 m³ pesa pouco e não cabe numa
   * moto.
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

  /**
   * Valor ofertado por terceiro em tokens adicionais. Não é câmbio: a taxa é de calibração, para
   * ordenar por urgência (ADR 0009 §6). Negativo vira zero — dado ruim não rebaixa a comunidade.
   */
  private static BigDecimal adicionalValorOfertado(
      BigDecimal valorOfertadoBrl, ParametrosRecompensa p) {
    if (valorOfertadoBrl == null || valorOfertadoBrl.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    return valorOfertadoBrl.multiply(p.tokensPorRealOfertado());
  }
}
