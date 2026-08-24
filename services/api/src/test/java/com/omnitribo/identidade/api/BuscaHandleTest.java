package com.omnitribo.identidade.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/v1/usuarios/busca} — encontrar o vizinho pelo {@code @}, sem digitar UUID.
 *
 * <p>Fecha a Pendência #3 sem reabrir a decisão que o {@code TriboController} registra: não há
 * listagem de membros, porque ela daria a qualquer autenticado um mapa social do bairro — e, como a
 * transferência é restrita à mesma tribo, esse mapa seria uma lista de alvos.
 *
 * <p><b>O teste central deste arquivo é o da INDISTINGUIBILIDADE</b>: handle inexistente e handle
 * de outra tribo têm de produzir respostas iguais. Se divergirem, o endpoint vira oráculo de
 * enumeração — e é o tipo de regressão que passa por revisão sem ninguém notar, porque cada
 * resposta isolada parece razoável.
 */
@Import(JwtTestConfig.class)
@DisplayName("Busca por handle")
class BuscaHandleTest extends TesteIntegracaoMvcBase {

  private static final String URL = "/api/v1/usuarios/busca";

  /** Seed V900: Tribo Pinheiros. */
  private static final UUID ALICE = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

  /** Seed V904: MESMA tribo da alice (Pinheiros). */
  private static final UUID FERNANDA = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000904");

  /** Seed V900: Tribo Vila Madalena — OUTRA tribo. */
  private static final UUID BOB = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000003");

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  @AfterEach
  void restaurar() {
    jdbcTemplate.update(
        "UPDATE usuario SET status = 'ATIVO', anonimizado_em = NULL WHERE id = ?", FERNANDA);
  }

  @Test
  @DisplayName("acha o vizinho da mesma tribo e devolve só o necessário para conferir")
  void achaNaMesmaTribo() throws Exception {
    mockMvc
        .perform(
            get(URL).param("handle", handleDe(FERNANDA)).header("Authorization", bearer(ALICE)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(FERNANDA.toString()))
        .andExpect(jsonPath("$.nome").isNotEmpty())
        .andExpect(jsonPath("$.tribo").value("Tribo Pinheiros"))
        // Nada de e-mail, XP, nível ou saldo: quem digitou um @ não recebe dado pessoal de brinde.
        .andExpect(jsonPath("$.email").doesNotExist())
        .andExpect(jsonPath("$.xp").doesNotExist())
        .andExpect(jsonPath("$.nivel").doesNotExist());
  }

  @Test
  @DisplayName("ignora a caixa: @FERNANDA acha fernanda")
  void ignoraCaixa() throws Exception {
    mockMvc
        .perform(
            get(URL)
                .param("handle", handleDe(FERNANDA).toUpperCase(java.util.Locale.ROOT))
                .header("Authorization", bearer(ALICE)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(FERNANDA.toString()));
  }

  /**
   * O teste que sustenta a decisão de privacidade.
   *
   * <p>As duas respostas precisam ser BYTE A BYTE iguais no que o cliente consegue observar. Se o
   * "de outra tribo" devolvesse 403 e o "inexistente" 404, um atacante enumeraria os handles da
   * cidade inteira só comparando os códigos — é exatamente o oráculo que a auditoria F4 descreveu
   * ao mostrar que a premissa ingênua sobre 403/404 está invertida.
   */
  @Test
  @DisplayName("outra tribo e inexistente são a MESMA resposta")
  void outraTriboEInexistenteSaoIndistinguiveis() throws Exception {
    String deOutraTribo =
        mockMvc
            .perform(get(URL).param("handle", handleDe(BOB)).header("Authorization", bearer(ALICE)))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String inexistente =
        mockMvc
            .perform(
                get(URL)
                    .param("handle", "naoexiste" + UUID.randomUUID().toString().substring(0, 8))
                    .header("Authorization", bearer(ALICE)))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // `traceId` e `instance` variam por requisição; o resto tem de coincidir.
    assertThat(semRuido(deOutraTribo))
        .as("outra tribo e inexistente não podem ser distinguíveis")
        .isEqualTo(semRuido(inexistente));
  }

  @Test
  @DisplayName("conta anonimizada não é encontrável")
  void contaAnonimizadaNaoApareceu() throws Exception {
    jdbcTemplate.update(
        "UPDATE usuario SET status = 'INATIVO', anonimizado_em = NOW() WHERE id = ?", FERNANDA);

    mockMvc
        .perform(
            get(URL).param("handle", handleDe(FERNANDA)).header("Authorization", bearer(ALICE)))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("sem JWT é 401 — a identidade de quem pergunta nunca vem da query")
  void semTokenEh401() throws Exception {
    mockMvc
        .perform(get(URL).param("handle", handleDe(FERNANDA)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("handle em branco é 400")
  void handleVazioEh400() throws Exception {
    mockMvc
        .perform(get(URL).param("handle", "   ").header("Authorization", bearer(ALICE)))
        .andExpect(status().isBadRequest());
  }

  /**
   * O teto próprio, que é a defesa contra COLHEITA em massa.
   *
   * <p>`busca-handle-por-minuto` é 20 em `application-test.yml`, baixo de propósito — os outros
   * tetos estão em 10000 justamente para não mascarar teste nenhum, e este é a exceção porque aqui
   * o teto É o comportamento sob teste.
   *
   * <p>Usuário próprio (bob), e não alice: o balde é por usuário e vive dez minutos na Caffeine,
   * então esgotá-lo com alice contaminaria os testes acima dependendo da ordem de execução.
   */
  @Test
  @DisplayName("varredura de handles aleatórios é barrada pelo teto próprio")
  void varreduraEhBarradaPeloTeto() throws Exception {
    int limite = 20;
    for (int i = 0; i < limite; i++) {
      mockMvc
          .perform(
              get(URL)
                  .param("handle", "varredura" + i + UUID.randomUUID().toString().substring(0, 6))
                  .header("Authorization", bearer(BOB)))
          .andExpect(status().isNotFound());
    }

    // A seguinte não chega nem a consultar o banco: morre no filtro.
    mockMvc
        .perform(get(URL).param("handle", "maisuma").header("Authorization", bearer(BOB)))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/limite-requisicoes"));
  }

  // ─── Auxiliares ─────────────────────────────────────────────────────────────────────────────

  private String handleDe(UUID usuarioId) {
    return jdbcTemplate.queryForObject(
        "SELECT handle FROM usuario WHERE id = ?", String.class, usuarioId);
  }

  /** Remove os campos que variam por requisição, para comparar o resto. */
  private static String semRuido(String corpo) {
    return corpo.replaceAll("\"(traceId|instance)\":\"[^\"]*\"", "");
  }

  private String bearer(UUID usuarioId) {
    return "Bearer "
        + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", "USUARIO");
  }
}
