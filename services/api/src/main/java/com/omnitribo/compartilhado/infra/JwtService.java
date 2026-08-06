package com.omnitribo.compartilhado.infra;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@EnableConfigurationProperties(JwtProperties.class)
public class JwtService {

  private final JwtProperties props;
  private PrivateKey privateKey;
  private PublicKey publicKey;

  public JwtService(JwtProperties props) {
    this.props = props;
  }

  @PostConstruct
  protected void carregarChaves() {
    try {
      this.privateKey = carregarChavePrivada(props.chavePrivadaCaminho());
      this.publicKey = carregarChavePublica(props.chavePublicaCaminho());
    } catch (Exception e) {
      // Falha de startup é intencional: sem chaves RSA a API não pode emitir ou validar tokens.
      throw new IllegalStateException(
          "Falha ao carregar chaves RSA para JWT: " + e.getMessage(), e);
    }
  }

  /** Emite um access token RS256 para o usuário, com TTL configurado em app.jwt.ttl-access. */
  public String emitirAccessToken(UUID usuarioId, String email, String papel) {
    Instant agora = Instant.now();
    return Jwts.builder()
        .subject(usuarioId.toString())
        // jti aleatório por token: permite blocklist individual no futuro sem estado global.
        .id(UUID.randomUUID().toString())
        .claim("email", email)
        .claim("papel", papel)
        .issuer(props.issuer())
        .audience()
        .add(props.audience())
        .and()
        .issuedAt(Date.from(agora))
        .expiration(Date.from(agora.plus(props.ttlAccess())))
        // RS256 (RSA + SHA-256): assimétrico — chave privada assina, pública verifica.
        // Vantagem sobre HS256: o recurso server pode verificar sem conhecer a chave privada.
        .signWith(privateKey, Jwts.SIG.RS256)
        .compact();
  }

  /**
   * Valida assinatura, expiração, issuer e audience do token.
   *
   * @throws JwtValidacaoException se qualquer verificação falhar
   */
  public Claims validar(String token) {
    try {
      return Jwts.parser()
          .verifyWith(publicKey)
          .requireIssuer(props.issuer())
          .requireAudience(props.audience())
          .build()
          .parseSignedClaims(token)
          .getPayload();
    } catch (JwtException | IllegalArgumentException e) {
      // JwtException cobre: assinatura inválida, token expirado, issuer/audience errados.
      // Não relançamos a exceção original: evita vazar detalhes do token na resposta.
      throw new JwtValidacaoException("Token inválido ou expirado");
    }
  }

  // ─── Carregamento de chaves PEM ───────────────────────────────────────────

  private PrivateKey carregarChavePrivada(String caminho)
      throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
    String pem = lerPem(caminho, "PRIVATE KEY", "RSA PRIVATE KEY");
    byte[] der = Base64.getDecoder().decode(pem);
    return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
  }

  private PublicKey carregarChavePublica(String caminho)
      throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
    String pem = lerPem(caminho, "PUBLIC KEY");
    byte[] der = Base64.getDecoder().decode(pem);
    return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
  }

  private String lerPem(String caminho, String... cabecalhosPossiveis) throws IOException {
    String conteudo = Files.readString(Path.of(caminho));
    for (String cabecalho : cabecalhosPossiveis) {
      String inicio = "-----BEGIN " + cabecalho + "-----";
      String fim = "-----END " + cabecalho + "-----";
      if (conteudo.contains(inicio)) {
        return conteudo.replace(inicio, "").replace(fim, "").replaceAll("\\s", "");
      }
    }
    throw new IOException("Formato PEM não reconhecido em: " + caminho);
  }

  /** Lançada quando o JWT falha em qualquer verificação (assinatura, exp, iss, aud). */
  public static class JwtValidacaoException extends RuntimeException {
    private final HttpStatus status = HttpStatus.UNAUTHORIZED;

    public JwtValidacaoException(String mensagem) {
      super(mensagem);
    }

    public HttpStatus getStatus() {
      return status;
    }
  }
}
