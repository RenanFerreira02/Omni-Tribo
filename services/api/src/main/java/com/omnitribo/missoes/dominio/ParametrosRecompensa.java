package com.omnitribo.missoes.dominio;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parâmetros da fórmula de recompensa ({@code app.missoes.recompensa.*}): a FÓRMULA é código, os
 * NÚMEROS são configuração. Record imutável — propriedade ausente falha no startup, em vez de virar
 * zero silencioso.
 */
@ConfigurationProperties(prefix = "app.missoes.recompensa")
public record ParametrosRecompensa(

    /**
     * Versão desta calibração, congelada em {@code missao.versao_formula}. <b>Mudou qualquer número
     * daqui? Suba a versão</b> — {@code CalculadoraDeRecompensaTest.douradoV1} falha de propósito
     * para forçar a decisão.
     */
    int versao,

    /** Piso por categoria, antes de qualquer multiplicador ou adicional. */
    Map<CategoriaMissao, Long> baseTokens,

    /** Multiplicador sobre a base; o criador só declara quando não há peso e volume. */
    Map<ComplexidadeMissao, BigDecimal> multiplicadorComplexidade,

    /** Adicional por quilômetro origem→destino; zero sem destino. */
    BigDecimal tokensPorKm,

    /** Adicional por quilo. */
    BigDecimal tokensPorKg,

    /** Adicional por 100 litros: volume importa independente do peso. */
    BigDecimal tokensPorCemLitros,

    /**
     * Adicional por real ofertado pela transportadora. Não é cotação: é calibração de urgência,
     * baixa de propósito — alta, o parceiro compraria posição na lista.
     */
    BigDecimal tokensPorRealOfertado,

    /** Teto absoluto por missão: peso e volume ainda vêm do request, e o teto limita um exagero. */
    long tetoTokens,

    /** XP por token de recompensa. XP é reputação sem ledger: escalar não afeta a conservação. */
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
    BigDecimal volumeMediaAteL,

    /** Piso do multiplicador de risco: 1,00 — risco nunca REDUZ a recompensa. */
    BigDecimal multiplicadorRiscoMinimo,

    /**
     * Teto do multiplicador que a ECONOMIA aceita honrar. Duplica o de {@code app.logistica.risco}
     * de propósito: recalibrar o modelo não pode ampliar a emissão sozinho. {@code
     * CoerenciaTetoRiscoTest} falha se os dois divergirem.
     */
    BigDecimal multiplicadorRiscoMaximo) {

  /**
   * Cópia defensiva dos mapas: um record não congela o CONTEÚDO, e uma base alterada em runtime
   * produziria valores que nenhuma {@code versao} explica.
   */
  public ParametrosRecompensa {
    baseTokens = Map.copyOf(baseTokens);
    multiplicadorComplexidade = Map.copyOf(multiplicadorComplexidade);
  }
}
