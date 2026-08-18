package com.omnitribo.compartilhado.infra;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Vinculado a app.jwt.* no application.yml.
// Record imutável — falha no startup se propriedade obrigatória estiver ausente.
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
    String chavePrivadaCaminho,
    String chavePublicaCaminho,
    Duration ttlAccess,
    String issuer,
    String audience) {}
