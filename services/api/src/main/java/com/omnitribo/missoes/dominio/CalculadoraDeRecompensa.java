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
 * variável é a vontade de quem cria. Naquele momento ENTREGA e AJUDA ainda cunhavam token (lacuna
 * do §4.4), então isso era emissão sem contrapartida: 656 → 2.656 tokens em dois ciclos. Hoje as
 * duas pagam do pote — ENTREGA pelo patrocinador (ADR 0024) e AJUDA pela tribo (ADR 0025) —, e só
 * ENTREGA criada por humano ainda cunha.
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
   * @param valorOfertadoBrl valor que um TERCEIRO (hoje, a transportadora no webhook de entrega
   *     falida) declara estar disposto a custear. Nulo em toda missão criada por usuário — o app
   *     não tem esse campo e nunca terá, porque quem cria a missão não paga (ADR 0009).
   *     <p>Entra como INSUMO e nada mais: aumenta a recompensa em TOKEN, e jamais é gravado em
   *     {@code missao.valor_brl}, que {@code ck_missao_economia} trava em zero. A diferença importa
   *     — o executor continua recebendo XP e token, não reais, e a conversão do real ofertado em
   *     patrocínio do pote acontece fora do ciclo da missão.
   *     <p>Existe porque uma entrega difícil vale mais para quem a paga, e a transportadora é a
   *     única parte que sabe quanto: peso, volume e distância descrevem o esforço, não a urgência.
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
   * Resultado, com a complexidade EFETIVA — derivada ou declarada.
   *
   * <p>Devolver a complexidade, e não só os números, é o que permite ao app explicar o valor ao
   * usuário e ao servidor persistir a mesma coisa que mostrou na prévia.
   *
   * @param multiplicadorRisco o fator EFETIVAMENTE aplicado, depois do clamp. Viaja no resultado
   *     porque é congelado em {@code missao.multiplicador_risco} junto com {@code versao_formula} —
   *     sem ele, um crédito antigo não teria como ser explicado depois de o modelo mudar. Sempre
   *     preenchido: 1,00 quando não houve avaliação de risco.
   */
  public record Recompensa(
      int xp,
      long tokens,
      ComplexidadeMissao complexidade,
      int versaoFormula,
      BigDecimal multiplicadorRisco) {}

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

    // O risco multiplica a BASE, junto da complexidade — nunca o total.
    //
    // Multiplicar o total contradiria a decisão de projeto registrada no javadoc desta classe
    // ("base
    // multiplicada, adicionais somados"), e teria efeito perverso: uma entrega longa e pesada num
    // endereço arriscado veria os três adicionais escalados juntos, e a recompensa explodiria de
    // forma não linear justamente no caso extremo. Na base, o risco reprecifica a DIFICULDADE
    // intrínseca da missão, que é o que ele mede.
    BigDecimal risco = multiplicadorDeRiscoEfetivo(insumos.multiplicadorRisco(), p);
    BigDecimal total = new BigDecimal(base).multiply(multiplicador).multiply(risco);
    total = total.add(adicionalDistancia(insumos.distanciaM(), p));
    total = total.add(adicionalPeso(insumos.pesoKg(), p));
    total = total.add(adicionalVolume(insumos.volumeL(), p));
    total = total.add(adicionalValorOfertado(insumos.valorOfertadoBrl(), p));

    // HALF_UP e não truncamento: truncar tornaria a fórmula não monotônica em passos pequenos —
    // dois pesos diferentes cairiam no mesmo inteiro e um aumento de insumo não aumentaria nada.
    long tokens = Math.min(total.setScale(0, RoundingMode.HALF_UP).longValue(), p.tetoTokens());
    // Piso de 1: missão que não recompensa nada não é missão. Só acontece com base zerada na
    // configuração, mas o piso evita que um erro de calibração produza trabalho de graça.
    tokens = Math.max(tokens, 1L);

    int xp = (int) Math.min((long) tokens * p.xpPorToken(), p.tetoXp());

    return new Recompensa(xp, tokens, complexidade, p.versao(), risco);
  }

  /**
   * Multiplicador de risco efetivamente aplicado, sempre dentro do teto.
   *
   * <p>Ausente vira 1,00, o neutro: missão criada por usuário não passa por avaliação de risco, e
   * tratar isso como "risco desconhecido = risco alto" pagaria mais por ignorância.
   *
   * <p><b>O clamp é a segunda barreira, não a primeira.</b> {@code PrevisorDeRisco} já limita o
   * valor na origem; repetir aqui é deliberado, porque esta classe é a última função pura antes do
   * congelamento em banco e não pode confiar em quem a chamou. Um multiplicador fora de faixa vindo
   * de um chamador futuro — ou de um parâmetro mal calibrado — cunharia token além do previsto, e a
   * cunhagem não tem como ser desfeita depois de creditada.
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

  /**
   * Converte o valor ofertado por terceiro em tokens adicionais.
   *
   * <p>Não é câmbio. A taxa é de CALIBRAÇÃO, deliberadamente baixa, e existe para ordenar missões
   * por urgência — não para estabelecer quanto vale um token em reais. O ADR 0009 §6 recusa fixar
   * essa cotação em qualquer lugar do produto, porque token conversível é dinheiro, com KYC junto.
   *
   * <p>Negativo é tratado como zero em vez de reduzir a recompensa: um valor ofertado negativo é
   * dado ruim da transportadora, e deixá-lo subtrair permitiria a um parceiro rebaixar a recompensa
   * da comunidade abaixo do que o esforço já justifica.
   */
  private static BigDecimal adicionalValorOfertado(
      BigDecimal valorOfertadoBrl, ParametrosRecompensa p) {
    if (valorOfertadoBrl == null || valorOfertadoBrl.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    return valorOfertadoBrl.multiply(p.tokensPorRealOfertado());
  }
}
