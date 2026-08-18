package com.omnitribo.compartilhado.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import com.omnitribo.UsuarioDeTeste;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Contrato de erro RFC 9457. Cobre o que o app mobile vai programar contra.
 *
 * <p>Existe porque {@code type} era {@code about:blank} em toda resposta de erro do sistema — legal
 * pelo RFC, e inútil para o cliente, que ficava só com o número do status. Sem estas assertions,
 * voltar ao {@code about:blank} não quebraria build nenhum.
 *
 * <p>As URIs são conferidas por VALOR LITERAL, de propósito. Comparar com a constante {@code
 * TipoProblema.X} faria o teste concordar com qualquer mudança nela — inclusive uma que quebrasse
 * todo app já instalado. O ponto do teste é justamente travar o texto publicado.
 */
@Import(JwtTestConfig.class)
class ContratoErroTest extends TesteIntegracaoMvcBase {

  private static final String BASE = "https://omnitribo.dev/problemas/";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  /**
   * Usuário REAL. Antes o token era emitido para um {@code UUID.randomUUID()} sem linha em {@code
   * usuario}, e passava porque a autenticação só conferia a assinatura — o mesmo atalho que deixava
   * conta anonimizada escrever por 15 minutos (Pendência #3). Com {@code ConsultaSessao} no filtro,
   * aquele token vira 401 e nenhum dos contratos de 400/404 abaixo seria alcançado.
   */
  private UUID usuario;

  @BeforeEach
  void criarUsuario() {
    usuario = UsuarioDeTeste.criarAtivo(jdbcTemplate, "erro");
  }

  @AfterEach
  void removerUsuario() {
    UsuarioDeTeste.remover(jdbcTemplate, usuario);
  }

  private String token() {
    return JwtTestConfig.gerarTokenValido(usuario, "erro@omnitribo.dev", "USUARIO");
  }

  @Test
  void erro401_daCadeiaDeFiltros_temTipoEstavel() throws Exception {
    // 401 nasce no AuthenticationEntryPoint, que escreve JSON à mão — caminho totalmente separado
    // do GlobalExceptionHandler. É exatamente por isso que precisa de assertion própria: os dois
    // podiam divergir sem ninguém notar.
    mockMvc
        .perform(get("/api/v1/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value(BASE + "nao-autenticado"))
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.instance").value("/api/v1/auth/me"));
  }

  @Test
  void erro401_declaraUtf8_eNaoMutilaAcentuacao() throws Exception {
    // O 401 é escrito à mão pelo AuthenticationEntryPoint, com response.getWriter(). Sem
    // setCharacterEncoding explícito, o servlet cai no default ISO-8859-1 e "Autenticação
    // necessária" chega ao cliente como bytes Latin-1 rotulados application/problem+json — JSON é
    // UTF-8 por definição (RFC 8259 §8.1), então o app renderiza mojibake justamente no erro que
    // mais recebe: todo access token expira em 15 minutos.
    MvcResult resultado =
        mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized()).andReturn();

    assertThat(resultado.getResponse().getContentType()).containsIgnoringCase("charset=UTF-8");
    // Lido como UTF-8 de propósito: getContentAsString() sem argumento usa o encoding declarado
    // pela resposta e mascararia exatamente o defeito que este teste procura.
    String corpo = resultado.getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(corpo).contains("Autenticação necessária");
  }

  @Test
  void erro404_deRecursoInexistente_temTipoEstavelETraceId() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/missoes/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + token()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value(BASE + "nao-encontrado"))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.instance").exists())
        .andExpect(jsonPath("$.traceId").exists());
  }

  /**
   * Corpo que o Jackson CONSEGUE desserializar e a Bean Validation rejeita.
   *
   * <p>Os primitivos precisam vir preenchidos: {@code {}} nem chega à validação — Jackson falha ao
   * coagir null para {@code int raioCheckinM} e o erro vira HttpMessageNotReadable, coberto pelo
   * teste seguinte. Aqui o objetivo é o outro caminho: campos ausentes ou fora de faixa.
   *
   * <p>Este papel era de {@code tokensRecompensa} até o ADR 0009 remover a recompensa do DTO de
   * criação; {@code raioCheckinM} é o primitivo que sobrou.
   */
  private static final String CORPO_INVALIDO =
      """
      {"titulo":"ab","raioCheckinM":5}
      """;

  @Test
  void erro400_deValidacao_temTipoInstanceEListaDeCampos() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/missoes")
                .header("Authorization", "Bearer " + token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(CORPO_INVALIDO))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(BASE + "requisicao-invalida"))
        .andExpect(jsonPath("$.instance").value("/api/v1/missoes"))
        .andExpect(jsonPath("$.traceId").exists())
        .andExpect(jsonPath("$.errors").isArray())
        .andExpect(jsonPath("$.errors[0].campo").exists())
        .andExpect(jsonPath("$.errors[0].mensagem").exists());
  }

  @Test
  void erro400_deCorpoIlegivel_tambemEntraNoContrato() throws Exception {
    // Este NÃO passa por nenhum @ExceptionHandler escrito por nós: é o handleHttpMessageNotReadable
    // herdado do ResponseEntityExceptionHandler. Saía como
    // {"detail":"Failed to read request","instance":...,"status":400,"title":"Bad Request"} — sem
    // type e sem traceId, um segundo contrato de erro convivendo com o oficial. Prova o funil do
    // createResponseEntity; sem ele, os ~15 handlers herdados do Spring escapam todos.
    mockMvc
        .perform(
            post("/api/v1/missoes")
                .header("Authorization", "Bearer " + token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(BASE + "requisicao-invalida"))
        .andExpect(jsonPath("$.traceId").exists())
        .andExpect(jsonPath("$.instance").value("/api/v1/missoes"));
  }

  @Test
  void erro405_deMetodoNaoSuportado_naoEscapaSemTipo() throws Exception {
    // Segundo handler herdado, escolhido por exercitar um ramo diferente do resolverStatus.
    mockMvc
        .perform(delete("/api/v1/ping").header("Authorization", "Bearer " + token()))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.type").exists())
        .andExpect(jsonPath("$.traceId").exists());
  }

  @Test
  void nenhumErro_vazaAboutBlankOuDetalheInterno() throws Exception {
    MvcResult resultado =
        mockMvc
            .perform(
                post("/api/v1/missoes")
                    .header("Authorization", "Bearer " + token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CORPO_INVALIDO))
            .andReturn();

    String corpo = resultado.getResponse().getContentAsString();
    assertThat(corpo).doesNotContain("about:blank");
    // As três formas clássicas de vazamento num corpo de erro. Não substitui revisão, mas trava a
    // regressão mais provável: alguém trocar o handler genérico por ex.getMessage().
    assertThat(corpo).doesNotContain("com.omnitribo");
    assertThat(corpo).doesNotContain("Exception");
    assertThat(corpo).doesNotContain("org.hibernate");
  }
}
