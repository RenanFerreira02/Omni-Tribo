package com.omnitribo.missoes.dominio;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parâmetros da fórmula de recompensa, vinculados a {@code app.missoes.recompensa.*}.
 *
 * <p>Ficam em properties, e não como constantes no código, porque são calibração de produto: a
 * relação entre esforço e recompensa vai ser ajustada com uso real, e cada ajuste não deveria
 * exigir recompilar. A FÓRMULA é código; os NÚMEROS são configuração.
 *
 * <p>Record imutável, no molde de {@code PoliticaCarteira} e {@code JwtProperties}: propriedade
 * ausente falha no startup em vez de virar zero silencioso — e um teto lido como zero recusaria
 * toda missão, ou pior, um mapa incompleto viraria NPE só quando alguém criasse a categoria que
 * faltava.
 *
 * <p>Registrado pelo {@code @ConfigurationPropertiesScan} de {@code ApiApplication}; não precisa de
 * {@code @Bean} nem de {@code @EnableConfigurationProperties}.
 */
@ConfigurationProperties(prefix = "app.missoes.recompensa")
public record ParametrosRecompensa(

    /**
     * Versão desta calibração, congelada em {@code missao.versao_formula} a cada criação.
     *
     * <p><b>Mudou qualquer número deste record? Suba a versão junto.</b> Sem isso, missões antigas
     * passam a ser "explicadas" por uma fórmula que não as produziu, e não há como responder se um
     * crédito estava certo quando foi feito. É o mesmo princípio do {@code saldo_apos_*} no ledger,
     * aplicado à origem do valor em vez de ao efeito dele.
     *
     * <p>{@code CalculadoraDeRecompensaTest.douradoV1} existe para forçar essa decisão: ele fixa a
     * saída desta calibração e falha se os números mudarem, obrigando quem mexeu a subir a versão.
     */
    int versao,

    /** Piso por categoria, antes de qualquer multiplicador ou adicional. */
    Map<CategoriaMissao, Long> baseTokens,

    /**
     * Multiplicador aplicado sobre a base. Único fator que o criador declara, e só quando não há
     * peso e volume para derivar.
     */
    Map<ComplexidadeMissao, BigDecimal> multiplicadorComplexidade,

    /**
     * Adicional por quilômetro entre origem e destino. Zero quando não há destino — TRIBO nunca
     * tem.
     */
    BigDecimal tokensPorKm,

    /**
     * Adicional por quilo. Junto com o volume, é o que separa "levar uma torneira" de "levar uma
     * caixa de porcelanato".
     */
    BigDecimal tokensPorKg,

    /**
     * Adicional por 100 litros. Volume importa independentemente do peso: isopor de 2 m³ não pesa
     * nada e não cabe em moto.
     */
    BigDecimal tokensPorCemLitros,

    /**
     * Teto absoluto por missão.
     *
     * <p>Existe mesmo com a fórmula fechada porque insumo declarado pelo criador ainda é insumo:
     * peso e volume vêm do request. O teto limita o dano de um exagero, e é o herdeiro direto do
     * {@code @Max(1000)} que antes era o ÚNICO controle — quando a recompensa era escolhida.
     */
    long tetoTokens,

    /**
     * XP concedido por token de recompensa. XP é reputação, não circula e não tem ledger, então
     * pode escalar com o esforço sem afetar a conservação da moeda.
     */
    int xpPorToken,

    /** Teto de XP por missão, pelo mesmo motivo do teto de tokens. */
    int tetoXp,

    /** Fronteira LEVE→MEDIA: peso, em kg. */
    BigDecimal pesoLeveAteKg,

    /** Fronteira LEVE→MEDIA: volume, em litros. */
    BigDecimal volumeLeveAteL,

    /** Fronteira MEDIA→PESADA: peso, em kg. */
    BigDecimal pesoMediaAteKg,

    /** Fronteira MEDIA→PESADA: volume, em litros. */
    BigDecimal volumeMediaAteL) {

  /**
   * Cópia defensiva dos mapas.
   *
   * <p>Um record não congela o CONTEÚDO das coleções que guarda: sem isto, quem tivesse a
   * referência do mapa original poderia alterar a base de uma categoria em runtime, e a fórmula
   * passaria a produzir valores que nenhuma {@code versao} explica. Como a calibração é exatamente
   * o que {@code versao_formula} promete rastrear, mutabilidade aqui não é detalhe de estilo — é a
   * garantia de auditoria vazando.
   */
  public ParametrosRecompensa {
    baseTokens = Map.copyOf(baseTokens);
    multiplicadorComplexidade = Map.copyOf(multiplicadorComplexidade);
  }
}
