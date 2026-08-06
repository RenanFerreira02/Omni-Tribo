package com.omnitribo;

import com.omnitribo.compartilhado.infra.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Substitui o JwtService <b>injetado</b> nos testes por uma implementação que assina com o par RSA
 * gerado em memória por TesteIntegracaoMvcBase — o que permite forjar token válido, expirado ou mal
 * assinado sem depender de arquivo em disco.
 *
 * <p>Atenção ao que o @Primary NÃO faz: ele apenas desempata a injeção quando há mais de um
 * candidato. O bean real (@Service JwtService, nome "jwtService") continua sendo instanciado junto
 * com este, e o @PostConstruct dele lê keys/private.pem de qualquer forma. Ou seja, a suíte exige
 * as chaves em disco mesmo nas classes que importam esta configuração. É por isso que `bash
 * tools/gerar-chaves-dev.sh` é pré-requisito do primeiro verify num clone novo, e um step dedicado
 * em .github/workflows/api.yml.
 */
@TestConfiguration
public class JwtTestConfig {

  private static final Duration TTL_TESTE = Duration.ofMinutes(15);
  private static final String ISSUER_TESTE = "omnitribo";
  private static final String AUDIENCE_TESTE = "omnitribo-app";

  @Bean
  @Primary
  public JwtService jwtServiceTeste() {
    return new JwtService(null) {
      @Override
      protected void carregarChaves() {
        // no-op: usa par RSA gerado programaticamente em TesteIntegracaoMvcBase.PAR_RSA_TESTE.
        // Sem este override, @PostConstruct tentaria carregar arquivos PEM com props=null → NPE.
      }

      @Override
      public String emitirAccessToken(UUID usuarioId, String email, String papel) {
        Instant agora = Instant.now();
        return Jwts.builder()
            .subject(usuarioId.toString())
            .id(UUID.randomUUID().toString())
            .claim("email", email)
            .claim("papel", papel)
            .issuer(ISSUER_TESTE)
            .audience()
            .add(AUDIENCE_TESTE)
            .and()
            .issuedAt(Date.from(agora))
            .expiration(Date.from(agora.plus(TTL_TESTE)))
            .signWith(TesteIntegracaoMvcBase.chavePrivadaTeste(), Jwts.SIG.RS256)
            .compact();
      }

      @Override
      public Claims validar(String token) {
        try {
          return Jwts.parser()
              .verifyWith(TesteIntegracaoMvcBase.chavePublicaTeste())
              .requireIssuer(ISSUER_TESTE)
              .requireAudience(AUDIENCE_TESTE)
              .build()
              .parseSignedClaims(token)
              .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
          throw new JwtService.JwtValidacaoException("Token inválido ou expirado");
        }
      }
    };
  }

  /**
   * Gera um JWT válido direto com a chave de teste, sem passar pelo endpoint de login.
   *
   * <p>Existe para testes que precisam de muitos usuários autenticados ao mesmo tempo (o aceite
   * concorrente usa 50): fazer login HTTP para cada um esbarraria no limite de 5 tentativas por
   * minuto do BloqueioLoginService e o teste falharia por 429, não pela regra em avaliação.
   */
  public static String gerarTokenValido(UUID usuarioId, String email, String papel) {
    Instant agora = Instant.now();
    return Jwts.builder()
        .subject(usuarioId.toString())
        .id(UUID.randomUUID().toString())
        .claim("email", email)
        .claim("papel", papel)
        .issuer(ISSUER_TESTE)
        .audience()
        .add(AUDIENCE_TESTE)
        .and()
        .issuedAt(Date.from(agora))
        .expiration(Date.from(agora.plus(TTL_TESTE)))
        .signWith(TesteIntegracaoMvcBase.chavePrivadaTeste(), Jwts.SIG.RS256)
        .compact();
  }

  /** Gera um JWT já expirado usando a chave de teste, para testar rejeição de token expirado. */
  public static String gerarTokenExpirado(UUID usuarioId, String email, String papel) {
    Instant passado = Instant.now().minus(Duration.ofHours(1));
    return Jwts.builder()
        .subject(usuarioId.toString())
        .id(UUID.randomUUID().toString())
        .claim("email", email)
        .claim("papel", papel)
        .issuer(ISSUER_TESTE)
        .audience()
        .add(AUDIENCE_TESTE)
        .and()
        .issuedAt(Date.from(passado.minus(Duration.ofHours(1))))
        .expiration(Date.from(passado))
        .signWith(TesteIntegracaoMvcBase.chavePrivadaTeste(), Jwts.SIG.RS256)
        .compact();
  }
}
