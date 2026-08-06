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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import com.omnitribo.identidade.infra.RefreshTokenRepository;
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
  @Autowired ObjectMapper objectMapper;
  @Autowired RefreshTokenRepository refreshTokenRepository;

  private static final String ALICE_EMAIL = "alice@omnitribo.dev";
  private static final String SENHA_ALICE = "Senha@123"; // seed V9 + V10 prefix {bcrypt}

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
                    .header("X-Forwarded-For", ipTeste)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json("email", ALICE_EMAIL, "senha", SENHA_ALICE)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isString())
            .andExpect(jsonPath("$.refreshToken").isString())
            .andExpect(jsonPath("$.tipoToken").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(900))
            .andReturn();

    // Token nunca deve aparecer como campo sensivelmente nomeado na resposta
    verificarSemDadosSensiveis(result.getResponse().getContentAsString());
  }

  // ── 2 e 3. Login inválido: senha errada e email inexistente → MESMA mensagem ──

  @Test
  void loginSenhaErrada_retorna401_comMensagemGenerica() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .header("X-Forwarded-For", ipTeste)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json("email", ALICE_EMAIL, "senha", "SenhaErrada!123456")))
            .andExpect(status().isUnauthorized())
            .andReturn();
    String detalhe = extrairDetalhe(result);
    assertThat(detalhe).isEqualTo("Credenciais inválidas");
  }

  @Test
  void loginEmailInexistente_retorna401_comMensagemIdentica() throws Exception {
    // Usamos dois IPs diferentes mas ambos únicos para este teste, para isolar os buckets.
    String ip2 = ipTeste + "-b";

    MvcResult senhaErrada =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .header("X-Forwarded-For", ipTeste)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json("email", ALICE_EMAIL, "senha", "SenhaErrada!123456")))
            .andReturn();

    MvcResult emailInexistente =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .header("X-Forwarded-For", ip2)
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
        objectMapper.readValue(
            rotacaoResult.getResponse().getContentAsString(), LoginResponse.class);
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
        objectMapper
            .readValue(rotacao1.getResponse().getContentAsString(), LoginResponse.class)
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
                    .header("X-Forwarded-For", ipTeste)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json("email", ALICE_EMAIL, "senha", SENHA_ALICE)))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), LoginResponse.class);
  }

  private String extrairDetalhe(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString()).path("detail").asText();
  }

  private void verificarSemDadosSensiveis(String corpo) {
    // Tokens são válidos na resposta (são o produto esperado) mas não devem aparecer com campos
    // que indiquem dados internos.
    assertThat(corpo).doesNotContain("senhaHash");
    assertThat(corpo).doesNotContain("senha_hash");
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
