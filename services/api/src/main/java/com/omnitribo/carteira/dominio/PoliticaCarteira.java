package com.omnitribo.carteira.dominio;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limites operacionais da carteira, vinculados a {@code app.carteira.*}.
 *
 * <p>Os tetos de transferência não existem para impedir uso legítimo — existem para limitar o DANO
 * de uma conta comprometida. Quem rouba um token de acesso pode transferir; o teto por transação
 * impede o esvaziamento num único POST, e o teto por janela impede o mesmo esvaziamento fatiado em
 * N requisições. Um sem o outro é contornável de forma trivial.
 *
 * <p>Record imutável, no molde de {@code JwtProperties}: propriedade ausente falha no startup, em
 * vez de virar zero silencioso — e um teto zero recusaria toda transferência, ou pior, um teto lido
 * como {@code null} viraria NPE só quando alguém transferisse.
 */
@ConfigurationProperties(prefix = "app.carteira")
public record PoliticaCarteira(
    long transferenciaTetoPorTransacao,
    long transferenciaTetoPorJanela,
    Duration transferenciaJanela,
    BigDecimal saqueMinimoBrl) {}
