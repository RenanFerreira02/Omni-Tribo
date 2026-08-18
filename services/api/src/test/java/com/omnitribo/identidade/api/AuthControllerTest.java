package com.omnitribo.identidade.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import com.omnitribo.identidade.infra.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@Import(JwtTestConfig.class)
class AuthControllerTest extends TesteIntegracaoMvcBase {

  @Autowired MockMvc mockMvc;
  @Autowired RefreshTokenRepository refreshTokenRepository;

  private static final String ALICE_EMAIL = "alice@omnitribo.dev";
  private static final String SENHA_ALICE = "Senha@123"; // seed V900, hash já com prefixo {bcrypt}

  /**
   * IP único por instância de teste. BloqueioLoginService usa sha256(ip+email) como chave; IPs
   * distintos = buckets distintos = sem interferência entre testes da mesma suíte.
   */
  private String ipTeste;

  @BeforeEach
  void setup() {
    refreshTokenRepository.deleteAll();
    // Gera IP pseudo-aleatório único por execução de teste para isolar os buckets de rate limiting.
    ipTeste = "10.test." + UUID.randomUUID().toString().substring(0, 8);
  }

  // ── 1. Login válido ──────────────────────────────────────────────────────

  @Test
  void loginValido_retorna200_comAccessERefreshToken() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .with(vindoDe(ipTeste))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json("email", ALICE_EMAIL, "senha", SENHA_ALICE)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isString())
            .andExpect(jsonPath("$.refreshToken").isString())
            .andExpect(jsonPath("$.tipoToken").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(900))
            .andReturn();

    // Token nunca deve aparecer como campo sensivelmente nomeado na resposta
    verificarSemDadosSensiveis(result.getResponse().getContentAsString(), SENHA_ALICE);
  }

  // ── 2 e 3. Login inválido: senha errada e email inexistente → MESMA mensagem ──

  @Test
  void loginSenhaErrada_retorna401_comMensagemGenerica() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .with(vindoDe(ipTeste))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json("email", ALICE_EMAIL, "senha", "SenhaErrada!123456")))
            .andExpect(status().isUnauthorized())
            .andReturn();
    String detalhe = extrairDetalhe(result);
    assertThat(detalhe).isEqualTo("Credenciais inválidas");
    // O 401 também não pode ecoar a senha tentada.
    verificarSemDadosSensiveis(result.getResponse().getContentAsString(), "SenhaErrada!123456");
  }

  @Test
  void loginEmailInexistente_retorna401_comMensagemIdentica() throws Exception {
    // Usamos dois IPs diferentes mas ambos únicos para este teste, para isolar os buckets.
    String ip2 = ipTeste + "-b";

    MvcResult senhaErrada =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .with(vindoDe(ipTeste))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json("email", ALICE_EMAIL, "senha", "SenhaErrada!123456")))
            .andReturn();

    MvcResult emailInexistente =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .with(vindoDe(ip2))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json("email", "naoexiste@exemplo.com", "senha", "SenhaErrada!123456")))
            .andReturn();

    // Mensagem IDÊNTICA: previne enumeração de usuários via resposta diferenciada.
    assertThat(extrairDetalhe(senhaErrada)).isEqualTo(extrairDetalhe(emailInexistente));
    assertThat(senhaErrada.getResponse().getStatus())
        .isEqualTo(emailInexistente.getResponse().getStatus());
  }

  // ── 4. Sem token em endpoint protegido → 401 ────────────────────────────

  @Test
  void semToken_retorna401_emFormatoProblemDetail() throws Exception {
    mockMvc
        .perform(get("/api/v1/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.detail").isString());
  }

  // ── 5. Token expirado → 401 ─────────────────────────────────────────────

  @Test
  void tokenExpirado_retorna401() throws Exception {
    String tokenExpirado =
        JwtTestConfig.gerarTokenExpirado(UUID.randomUUID(), ALICE_EMAIL, "USUARIO");

    mockMvc
        .perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tokenExpirado))
        .andExpect(status().isUnauthorized());
  }

  // ── 7 e 8. Rotação de refresh + detecção de reuso ───────────────────────

  @Test
  void refreshValido_rotaciona_e_tokenAntigoEhRecusado() throws Exception {
    // (a) Login → obtém tokens
    LoginResponse primeiro = realizarLogin();
    String refreshOriginal = primeiro.refreshToken();

    // (b) Rotação → recebe novos tokens
    MvcResult rotacaoResult =
        mockMvc
            .perform(
                post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"refreshToken\":\"" + refreshOriginal + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isString())
            .andExpect(jsonPath("$.refreshToken").isString())
            .andReturn();

    // Garantir que o novo refresh token é diferente do original
    LoginResponse rotacionado =
        JSON.readValue(rotacaoResult.getResponse().getContentAsString(), LoginResponse.class);
    assertThat(rotacionado.refreshToken()).isNotEqualTo(refreshOriginal);

    // (c) Usar o refresh ANTIGO novamente → REJEIÇÃO (token já revogado)
    mockMvc
        .perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshOriginal + "\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void reuso_deRefreshRotacionado_revogaFamiliaInteira() throws Exception {
    // Login → obtém tokens
    LoginResponse inicial = realizarLogin();
    String refreshV1 = inicial.refreshToken();

    // Rotação legítima: V1 → V2
    MvcResult rotacao1 =
        mockMvc
            .perform(
                post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"refreshToken\":\"" + refreshV1 + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    String refreshV2 =
        JSON.readValue(rotacao1.getResponse().getContentAsString(), LoginResponse.class)
            .refreshToken();

    // Rotação legítima: V2 → V3
    mockMvc
        .perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshV2 + "\"}"))
        .andExpect(status().isOk());

    // Reuso de V1 (já rotacionado) → DETECÇÃO DE REUSO → toda a família revogada
    mockMvc
        .perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshV1 + "\"}"))
        .andExpect(status().isUnauthorized());

    // Confirma que TODOS os tokens da família estão revogados no banco
    long naoRevogados =
        refreshTokenRepository.findAll().stream().filter(t -> t.getRevogadoEm() == null).count();
    assertThat(naoRevogados)
        .as("Após detecção de reuso, nenhum token da família deve estar ativo")
        .isZero();
  }

  // ── 9. 6ª tentativa de login em 1 minuto → 429 ──────────────────────────

  @Test
  void sextaTentativa_emUmMinuto_retorna429_comRetryAfter() throws Exception {
    // Email único: garante bucket próprio independente de outros testes (IP é 127.0.0.1 do mock).
    String emailTeste = "rateLimitTeste_" + UUID.randomUUID() + "@exemplo.com";

    for (int i = 0; i < 5; i++) {
      mockMvc.perform(
          post("/api/v1/auth/login")
              .contentType(MediaType.APPLICATION_JSON)
              .content(json("email", emailTeste, "senha", "SenhaQualquer!123")));
    }

    // 6ª tentativa → 429
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("email", emailTeste, "senha", "SenhaQualquer!123")))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.status").value(429));
  }

  // ── 10. Senha comum rejeitada no registro ───────────────────────────────

  @Test
  void registro_comSenhaComum_retorna400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/registrar")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"nome\":\"Teste\",\"email\":\"novouser@teste.com\",\"handle\":\"testecomum\","
                        // "password123456" está na lista senhas-comuns.txt; ehComum() é
                        // case-insensitive
                        + "\"senha\":\"Password123456\"}")) // lowercase = password123456 ✓
        .andExpect(status().isBadRequest());
  }

  @Test
  void registro_naoDevolveSenhaNemHash() throws Exception {
    String senha = "SenhaLongaDeTeste@2026";
    String sufixo = UUID.randomUUID().toString().substring(0, 8);

    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/registrar")
                    .with(vindoDe(ipTeste))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json(
                            "nome", "Teste " + sufixo,
                            "email", "novo" + sufixo + "@omnitribo.dev",
                            "handle", "handle" + sufixo,
                            "senha", senha)))
            .andExpect(status().isCreated())
            .andReturn();

    verificarSemDadosSensiveis(result.getResponse().getContentAsString(), senha);
  }

  @Test
  void registro_comSenhaCurta_naoEcoaSenhaNoErro() throws Exception {
    String senhaCurta = "abc123";

    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/registrar")
                    .with(vindoDe(ipTeste))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json(
                            "nome",
                            "Teste Curto",
                            "email",
                            "curto" + UUID.randomUUID() + "@omnitribo.dev",
                            "handle",
                            "curto" + UUID.randomUUID().toString().substring(0, 8),
                            "senha",
                            senhaCurta)))
            .andExpect(status().isBadRequest())
            .andReturn();

    // Guarda de regressão: mensagem de validação não pode devolver o valor rejeitado. Um
    // ProblemDetail que ecoasse a senha a colocaria em log de proxy, APM e histórico do cliente.
    assertThat(result.getResponse().getContentAsString()).doesNotContain(senhaCurta);
  }

  @Test
  void accessToken_naoCarregaSenhaNoPayload_eTemOsClaimsExigidos() throws Exception {
    LoginResponse tokens = realizarLogin();

    // JWT é assinado, não criptografado: qualquer pessoa com o token lê o payload. O que estiver
    // ali é público na prática — por isso senha jamais pode entrar, e por isso vale fixar o
    // conjunto de claims que a spec exige (sub/jti/papel/iat/exp/iss/aud).
    String[] partes = tokens.accessToken().split("\\.");
    assertThat(partes).as("JWT deve ter header.payload.assinatura").hasSize(3);
    String payload = new String(Base64.getUrlDecoder().decode(partes[1]), StandardCharsets.UTF_8);

    assertThat(payload).doesNotContainIgnoringCase("senha");
    assertThat(payload).doesNotContain(SENHA_ALICE);

    var claims = JSON.readTree(payload);
    assertThat(claims.hasNonNull("sub")).isTrue();
    assertThat(claims.hasNonNull("jti")).isTrue();
    assertThat(claims.path("papel").asText()).isEqualTo("USUARIO");
    assertThat(claims.path("iss").asText()).isEqualTo("omnitribo");
    assertThat(claims.path("aud").toString()).contains("omnitribo-app");
    assertThat(claims.hasNonNull("iat")).isTrue();
    assertThat(claims.hasNonNull("exp")).isTrue();
    // TTL de 15 min (item 2 da spec): exp - iat = 900 s.
    assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isEqualTo(900L);
  }

  // ── 11. Senha e token nunca aparecem em log ──────────────────────────────

  @Test
  void senhaEToken_nuncaAparecemEmLog() throws Exception {
    // Captura logs durante o login
    Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
    listAppender.start();
    rootLogger.addAppender(listAppender);

    try {
      LoginResponse resposta = realizarLogin();

      List<String> mensagensLog =
          listAppender.list.stream().map(ILoggingEvent::getMessage).toList();

      // Verifica que nenhuma mensagem de log contém o token ou a senha
      String accessToken = resposta.accessToken();
      String refreshToken = resposta.refreshToken();

      for (String msg : mensagensLog) {
        assertThat(msg).as("Log não deve conter accessToken").doesNotContain(accessToken);
        assertThat(msg).as("Log não deve conter refreshToken").doesNotContain(refreshToken);
        assertThat(msg)
            .as("Log não deve conter a senha em plaintext")
            .doesNotContainIgnoringCase(SENHA_ALICE);
      }
    } finally {
      rootLogger.detachAppender(listAppender);
    }
  }

  // ── /me retorna perfil do usuário autenticado ────────────────────────────

  @Test
  void me_comTokenValido_retornaPerfil() throws Exception {
    LoginResponse tokens = realizarLogin();

    mockMvc
        .perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tokens.accessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(ALICE_EMAIL))
        .andExpect(jsonPath("$.papel").value("USUARIO"));
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private LoginResponse realizarLogin() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    // IP único por teste: isola buckets de rate limiting entre casos de teste.
                    .with(vindoDe(ipTeste))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json("email", ALICE_EMAIL, "senha", SENHA_ALICE)))
            .andExpect(status().isOk())
            .andReturn();
    return JSON.readValue(result.getResponse().getContentAsString(), LoginResponse.class);
  }

  private String extrairDetalhe(MvcResult result) throws Exception {
    return JSON.readTree(result.getResponse().getContentAsString()).path("detail").asText();
  }

  private void verificarSemDadosSensiveis(String corpo, String senhaEnviada) {
    // Tokens são válidos na resposta (são o produto esperado) mas não devem aparecer com campos
    // que indiquem dados internos.
    assertThat(corpo).doesNotContain("senhaHash");
    assertThat(corpo).doesNotContain("senha_hash");
    // O que faltava: a senha em texto plano jamais volta ao cliente, nem ecoada de volta pelo
    // próprio corpo que o cliente enviou. Sem esta assertion o teste passaria mesmo se a resposta
    // devolvesse a senha inteira.
    assertThat(corpo)
        .as("Senha em texto plano não pode voltar na resposta")
        .doesNotContain(senhaEnviada);
    assertThat(corpo).doesNotContain("\"senha\"");
  }

  private String json(String... pares) {
    StringBuilder sb = new StringBuilder("{");
    for (int i = 0; i < pares.length; i += 2) {
      if (i > 0) sb.append(",");
      sb.append("\"").append(pares[i]).append("\":\"").append(pares[i + 1]).append("\"");
    }
    sb.append("}");
    return sb.toString();
  }
}
