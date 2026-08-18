package com.omnitribo.identidade.dominio;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limiares do catálogo de conquistas, vinculados a {@code app.identidade.conquistas.*}.
 *
 * <p>A FÓRMULA é código ({@code CalculadoraDeConquistas}); os NÚMEROS são configuração. Record
 * imutável, no molde de {@code ParametrosRecompensa}, {@code PoliticaCarteira} e {@code
 * JwtProperties}: propriedade ausente falha no startup em vez de virar zero silencioso — e um
 * limiar lido como zero concederia toda conquista a todo mundo, que é o pior desfecho possível para
 * uma medalha.
 *
 * <p>Antes esta calibração era lida por seis {@code @Value} espalhados pelo construtor de {@code
 * PerfilService}, cada um com default inline. Exatamente a propriedade que os outros três blocos
 * defendem — falhar alto quando falta configuração — era a que este perdia.
 *
 * <p><b>Não tem {@code versao}, e a ausência é deliberada.</b> Conquista é DERIVADA e recalculada a
 * cada leitura, nunca gravada: não há linha em banco que uma versão pudesse explicar depois. Havia
 * uma {@code versao} aqui, lida do YAML e nunca usada de volta, com três javadocs prometendo que
 * subi-la protegeria o histórico — não protegia nada. O contraste é {@code
 * ParametrosRecompensa.versao}, que é congelada em {@code missao.versao_formula} e por isso
 * responde de verdade "este crédito estava certo quando foi feito?".
 *
 * <p>Registrado pelo {@code @ConfigurationPropertiesScan} de {@code ApiApplication}.
 */
@ConfigurationProperties(prefix = "app.identidade.conquistas")
public record ParametrosConquistas(
    long xpIniciante,
    long xpVizinhoPresente,
    long xpPilarDaTribo,
    int nivelVeterano,
    int streakConstante) {}
