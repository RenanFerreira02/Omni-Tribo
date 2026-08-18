package com.omnitribo.notificacoes.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Caixa de entrada: contrato HTTP e, sobretudo, o ISOLAMENTO entre caixas.
 *
 * <p>A tabela {@code alerta} era escrita desde a F7 e nunca lida. O risco de um endpoint novo sobre
 * uma tabela antiga é justamente este: a escrita já povoou linhas de vários usuários, e o filtro
 * por dono é a única coisa entre a caixa de um e a do outro. É o que a maior parte destes testes
 * mede.
 */
@Import(JwtTestConfig.class)
class AlertaControllerTest extends TesteIntegracaoMvcBase {

  private static final UUID ALICE_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
  private static final UUID BOB_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000003");

  private static final String BASE = "/api/v1/alertas";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  @BeforeEach
  @AfterEach
  void limparAlertas() {
    jdbcTemplate.update("DELETE FROM alerta WHERE usuario_id IN (?, ?)", ALICE_ID, BOB_ID);
  }

  // ─── Listagem ──────────────────────────────────────────────────────────────────────────────

  @Test
  void lista_do_mais_recente_para_o_mais_antigo() throws Exception {
    gravar(ALICE_ID, "MISSAO_CONCLUIDA", "Mais antiga", false, 60);
    gravar(ALICE_ID, "MISSAO_CONCLUIDA", "Mais recente", false, 1);

    mockMvc
        .perform(autenticado(get(BASE), ALICE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElementos").value(2))
        .andExpect(jsonPath("$.conteudo[0].titulo").value("Mais recente"))
        .andExpect(jsonPath("$.conteudo[1].titulo").value("Mais antiga"))
        // O dono NÃO volta no corpo: a caixa é sempre a de quem perguntou, e devolver usuarioId só
        // daria ao cliente um campo para tentar variar.
        .andExpect(jsonPath("$.conteudo[0].usuarioId").doesNotExist());
  }

  @Test
  void a_caixa_de_um_usuario_nao_mostra_alerta_de_outro() throws Exception {
    gravar(ALICE_ID, "MISSAO_CONCLUIDA", "Da Alice", false, 1);
    gravar(BOB_ID, "MISSAO_CONCLUIDA", "Do Bob", false, 1);

    mockMvc
        .perform(autenticado(get(BASE), ALICE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElementos").value(1))
        .andExpect(jsonPath("$.conteudo[0].titulo").value("Da Alice"));
  }

  @Test
  void apenas_nao_lidos_filtra_o_que_ja_foi_lido() throws Exception {
    gravar(ALICE_ID, "MISSAO_CONCLUIDA", "Já lida", true, 2);
    gravar(ALICE_ID, "MISSAO_CONCLUIDA", "Pendente", false, 1);

    mockMvc
        .perform(autenticado(get(BASE).param("apenasNaoLidos", "true"), ALICE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElementos").value(1))
        .andExpect(jsonPath("$.conteudo[0].titulo").value("Pendente"));
  }

  @Test
  void tamanho_de_pagina_acima_do_teto_responde_400() throws Exception {
    mockMvc
        .perform(autenticado(get(BASE).param("tamanho", "101"), ALICE_ID))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/requisicao-invalida"));
  }

  @Test
  void sem_token_responde_401() throws Exception {
    mockMvc
        .perform(get(BASE))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/nao-autenticado"));
  }

  // ─── Contador ──────────────────────────────────────────────────────────────────────────────

  @Test
  void contador_conta_so_as_nao_lidas_do_proprio_usuario() throws Exception {
    gravar(ALICE_ID, "MISSAO_CONCLUIDA", "Pendente 1", false, 3);
    gravar(ALICE_ID, "MISSAO_CONCLUIDA", "Pendente 2", false, 2);
    gravar(ALICE_ID, "MISSAO_CONCLUIDA", "Já lida", true, 1);
    gravar(BOB_ID, "MISSAO_CONCLUIDA", "Do Bob, pendente", false, 1);

    mockMvc
        .perform(autenticado(get(BASE + "/nao-lidos/contagem"), ALICE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.naoLidos").value(2));
  }

  // ─── Marcar como lida ──────────────────────────────────────────────────────────────────────

  @Test
  void marcar_como_lida_persiste_e_sai_do_contador() throws Exception {
    UUID alertaId = gravar(ALICE_ID, "MISSAO_CONCLUIDA", "Pendente", false, 1);

    mockMvc
        .perform(autenticado(patch(BASE + "/" + alertaId + "/lido"), ALICE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lido").value(true));

    assertThat(lido(alertaId)).isTrue();

    mockMvc
        .perform(autenticado(get(BASE + "/nao-lidos/contagem"), ALICE_ID))
        .andExpect(jsonPath("$.naoLidos").value(0));
  }

  /**
   * O app marca ao ABRIR a notificação, então o segundo toque e o retry de rede são normais, não
   * excepcionais. Um 409 aqui faria a tela mostrar erro por ter feito exatamente o que devia.
   */
  @Test
  void marcar_duas_vezes_e_idempotente() throws Exception {
    UUID alertaId = gravar(ALICE_ID, "MISSAO_CONCLUIDA", "Pendente", false, 1);

    mockMvc
        .perform(autenticado(patch(BASE + "/" + alertaId + "/lido"), ALICE_ID))
        .andExpect(status().isOk());
    mockMvc
        .perform(autenticado(patch(BASE + "/" + alertaId + "/lido"), ALICE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lido").value(true));
  }

  /**
   * 404, e não 403. Um 403 confirmaria que o id existe — e como o id de um alerta viaja em
   * notificação, essa confirmação é enumerável.
   */
  @Test
  void marcar_alerta_alheio_responde_404_e_nao_altera_nada() throws Exception {
    UUID doBob = gravar(BOB_ID, "MISSAO_CONCLUIDA", "Do Bob", false, 1);

    mockMvc
        .perform(autenticado(patch(BASE + "/" + doBob + "/lido"), ALICE_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/nao-encontrado"));

    assertThat(lido(doBob)).isFalse();
  }

  @Test
  void marcar_alerta_inexistente_responde_404() throws Exception {
    mockMvc
        .perform(autenticado(patch(BASE + "/" + UUID.randomUUID() + "/lido"), ALICE_ID))
        .andExpect(status().isNotFound());
  }

  // ─── Apoio ─────────────────────────────────────────────────────────────────────────────────

  private UUID gravar(UUID dono, String tipo, String titulo, boolean lido, int minutosAtras) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO alerta (id, usuario_id, tipo, titulo, corpo, missao_id, lido, criado_em)
        VALUES (?, ?, ?, ?, ?, NULL, ?, ?)
        """,
        id,
        dono,
        tipo,
        titulo,
        "corpo de teste",
        lido,
        java.sql.Timestamp.from(Instant.now().minus(minutosAtras, ChronoUnit.MINUTES)));
    return id;
  }

  private boolean lido(UUID alertaId) {
    List<Boolean> linhas =
        jdbcTemplate.queryForList("SELECT lido FROM alerta WHERE id = ?", Boolean.class, alertaId);
    return !linhas.isEmpty() && Boolean.TRUE.equals(linhas.get(0));
  }

  private MockHttpServletRequestBuilder autenticado(
      MockHttpServletRequestBuilder builder, UUID usuarioId) {
    return builder.header(
        "Authorization",
        "Bearer " + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", "USUARIO"));
  }
}
