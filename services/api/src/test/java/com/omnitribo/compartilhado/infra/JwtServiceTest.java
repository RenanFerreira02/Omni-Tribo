package com.omnitribo.compartilhado.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Claims;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O {@code JwtService} REAL — RS256 com chaves lidas de PEM.
 *
 * <p>Os testes de integração usam o dublê de {@code JwtTestConfig}, que assina com um par gerado em
 * memória e nunca passa por este código. Sem esta classe, carregar PEM, assinar e recusar token
 * forjado, expirado ou de outra audiência só seria exercitado em produção.
 */
class JwtServiceTest {

  private static final String ISSUER = "omnitribo";
  private static final String AUDIENCE = "omnitribo-app";

  @TempDir Path dir;

  private Path privada;
  private Path publica;

  @BeforeEach
  void gerarChaves() throws Exception {
    KeyPair par = novoPar();
    privada = gravarPem(dir.resolve("private.pem"), "PRIVATE KEY", par.getPrivate().getEncoded());
    publica = gravarPem(dir.resolve("public.pem"), "PUBLIC KEY", par.getPublic().getEncoded());
  }

  @Test
  void token_emitido_valida_e_carrega_sujeito_papel_issuer_e_audiencia() {
    JwtService jwt = servico(privada, publica, Duration.ofMinutes(15), AUDIENCE);
    UUID usuario = UUID.randomUUID();

    Claims claims = jwt.validar(jwt.emitirAccessToken(usuario, "a@b.dev", "ADMIN"));

    assertThat(claims.getSubject()).isEqualTo(usuario.toString());
    assertThat(claims.get("papel", String.class)).isEqualTo("ADMIN");
    assertThat(claims.get("email", String.class)).isEqualTo("a@b.dev");
    assertThat(claims.getIssuer()).isEqualTo(ISSUER);
    assertThat(claims.getAudience()).containsExactly(AUDIENCE);
    assertThat(claims.getId()).as("jti presente").isNotBlank();
  }

  @Test
  void token_assinado_por_outra_chave_e_recusado() throws Exception {
    KeyPair intruso = novoPar();
    Path privadaIntrusa =
        gravarPem(dir.resolve("intrusa.pem"), "PRIVATE KEY", intruso.getPrivate().getEncoded());
    Path publicaIntrusa =
        gravarPem(dir.resolve("intrusa.pub"), "PUBLIC KEY", intruso.getPublic().getEncoded());
    String forjado =
        servico(privadaIntrusa, publicaIntrusa, Duration.ofMinutes(15), AUDIENCE)
            .emitirAccessToken(UUID.randomUUID(), "x@y.dev", "ADMIN");

    JwtService legitimo = servico(privada, publica, Duration.ofMinutes(15), AUDIENCE);

    assertThatThrownBy(() -> legitimo.validar(forjado))
        .isInstanceOf(JwtService.JwtValidacaoException.class);
  }

  @Test
  void token_expirado_e_recusado() {
    JwtService jwt = servico(privada, publica, Duration.ofSeconds(-30), AUDIENCE);
    String expirado = jwt.emitirAccessToken(UUID.randomUUID(), "a@b.dev", "USUARIO");

    assertThatThrownBy(() -> jwt.validar(expirado))
        .isInstanceOf(JwtService.JwtValidacaoException.class);
  }

  @Test
  void token_de_outra_audiencia_e_recusado() {
    String deOutroApp =
        servico(privada, publica, Duration.ofMinutes(15), "outro-app")
            .emitirAccessToken(UUID.randomUUID(), "a@b.dev", "USUARIO");

    JwtService jwt = servico(privada, publica, Duration.ofMinutes(15), AUDIENCE);

    assertThatThrownBy(() -> jwt.validar(deOutroApp))
        .isInstanceOf(JwtService.JwtValidacaoException.class);
  }

  @Test
  void lixo_no_lugar_do_token_e_recusado_sem_vazar_a_causa() {
    JwtService jwt = servico(privada, publica, Duration.ofMinutes(15), AUDIENCE);

    assertThatThrownBy(() -> jwt.validar("nao.e.jwt"))
        .isInstanceOf(JwtService.JwtValidacaoException.class)
        .hasMessage("Token inválido ou expirado");
  }

  @Test
  void sem_arquivo_de_chave_a_api_nao_sobe() {
    JwtService jwt =
        new JwtService(
            new JwtProperties(
                dir.resolve("nao-existe.pem").toString(),
                publica.toString(),
                Duration.ofMinutes(15),
                ISSUER,
                AUDIENCE));

    assertThatThrownBy(jwt::carregarChaves).isInstanceOf(IllegalStateException.class);
  }

  private static JwtService servico(Path priv, Path pub, Duration ttl, String audiencia) {
    JwtService jwt =
        new JwtService(new JwtProperties(priv.toString(), pub.toString(), ttl, ISSUER, audiencia));
    jwt.carregarChaves();
    return jwt;
  }

  private static KeyPair novoPar() throws Exception {
    KeyPairGenerator gerador = KeyPairGenerator.getInstance("RSA");
    gerador.initialize(2048);
    return gerador.generateKeyPair();
  }

  private static Path gravarPem(Path destino, String tipo, byte[] der) throws Exception {
    String corpo = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
    Files.writeString(
        destino, "-----BEGIN " + tipo + "-----\n" + corpo + "\n-----END " + tipo + "-----\n");
    return destino;
  }
}
