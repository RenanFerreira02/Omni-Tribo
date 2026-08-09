package com.omnitribo.identidade.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Direitos do titular: exportar, consentir e ser esquecido.
 *
 * <p>A exclusão usa usuários DESCARTÁVEIS criados aqui, nunca os do seed: a operação é
 * irreversível, e anonimizar a Alice envenenaria todos os outros testes da suíte — o contêiner
 * PostGIS é singleton para a JVM inteira.
 */
@Import(JwtTestConfig.class)
class LgpdControllerTest extends TesteIntegracaoMvcBase {

  private static final UUID ALICE_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
  private static final String SENHA = "Senha@123";
  private static final String BASE = "/api/v1/usuarios/me";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  private final List<UUID> descartaveis = new java.util.ArrayList<>();

  @AfterEach
  void limpar() {
    for (UUID id : descartaveis) {
      jdbcTemplate.update("DELETE FROM refresh_token WHERE usuario_id = ?", id);
      jdbcTemplate.update("DELETE FROM consentimento WHERE usuario_id = ?", id);
      jdbcTemplate.update("DELETE FROM usuario WHERE id = ?", id);
    }
    descartaveis.clear();
    // Consentimentos gravados pelos testes de PUT sobre a Alice: a tabela é append-only, então o
    // resíduo mudaria o estado atual visto pelo teste seguinte.
    jdbcTemplate.update(
        "DELETE FROM consentimento WHERE usuario_id = ? AND versao_texto = 'teste'", ALICE_ID);
  }

  // ─── Exportação ────────────────────────────────────────────────────────────────────────────

  @Test
  void exportacao_traz_uma_secao_por_modulo() throws Exception {
    mockMvc
        .perform(autenticado(get(BASE + "/dados"), ALICE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.geradoEm").exists())
        // Uma seção por módulo contribuinte. Se um módulo novo esquecer de implementar a porta, a
        // exportação sai incompleta em silêncio — é esta asserção que trava as seções conhecidas.
        .andExpect(jsonPath("$.identidade").isArray())
        .andExpect(jsonPath("$.missoes").isArray())
        .andExpect(jsonPath("$.lancamentos").isArray())
        .andExpect(jsonPath("$.checkins").isArray())
        .andExpect(jsonPath("$.identidade[0].nome").value("Alice Ferreira"))
        .andExpect(jsonPath("$.identidade[0].tribo").value("Tribo Pinheiros"));
  }

  /**
   * O titular tem direito aos DADOS dele, não ao material criptográfico que protege a conta. Um
   * hash exportado é força bruta offline entregue de bandeja, num arquivo que a pessoa vai guardar
   * sem cuidado nenhum.
   */
  @Test
  void exportacao_nao_contem_segredo() throws Exception {
    String corpo =
        mockMvc
            .perform(autenticado(get(BASE + "/dados"), ALICE_ID))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(corpo)
        .doesNotContain("senha_hash")
        .doesNotContain("senhaHash")
        .doesNotContain("{bcrypt}")
        .doesNotContain("chave_idempotencia")
        // Identificar a contraparte de uma transferência exporia dado do OUTRO titular.
        .doesNotContain("contraparte_carteira_id");
  }

  @Test
  void exportacao_inclui_o_historico_de_consentimento() throws Exception {
    mockMvc
        .perform(autenticado(get(BASE + "/dados"), ALICE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.identidade[0].consentimentos").isArray())
        .andExpect(
            jsonPath("$.identidade[0].consentimentos.length()")
                .value(org.hamcrest.Matchers.greaterThanOrEqualTo(4)));
  }

  @Test
  void exportacao_sem_token_responde_401() throws Exception {
    mockMvc.perform(get(BASE + "/dados")).andExpect(status().isUnauthorized());
  }

  // ─── Consentimentos ────────────────────────────────────────────────────────────────────────

  @Test
  void lista_traz_todos_os_tipos_com_o_estado_mais_recente() throws Exception {
    mockMvc
        .perform(autenticado(get(BASE + "/consentimentos"), ALICE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        // Ordem estável: a de TipoConsentimento.values().
        .andExpect(jsonPath("$[0].tipo").value("LOCALIZACAO"))
        .andExpect(jsonPath("$[0].concedido").value(true))
        // O seed concede NOTIFICACAO e depois revoga. Vale a linha MAIS RECENTE — é o caso que uma
        // coluna sobrescrita esconderia e que um finder sem ordenação leria errado.
        .andExpect(jsonPath("$[1].tipo").value("NOTIFICACAO"))
        .andExpect(jsonPath("$[1].concedido").value(false))
        .andExpect(jsonPath("$[2].tipo").value("TERMOS"))
        .andExpect(jsonPath("$[2].concedido").value(true));
  }

  @Test
  void tipo_nunca_decidido_aparece_como_nao_concedido_em_vez_de_sumir() throws Exception {
    UUID novo = criarUsuarioDescartavel();

    mockMvc
        .perform(autenticado(get(BASE + "/consentimentos"), novo))
        .andExpect(status().isOk())
        // A tela precisa desenhar o interruptor antes da primeira escolha.
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].concedido").value(false))
        .andExpect(jsonPath("$[0].registradoEm").doesNotExist());
  }

  @Test
  void registrar_grava_linha_nova_e_nao_sobrescreve() throws Exception {
    long antes = contarConsentimentos(ALICE_ID);

    mockMvc
        .perform(
            autenticado(put(BASE + "/consentimentos/NOTIFICACAO"), ALICE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"concedido\":true,\"versaoTexto\":\"teste\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tipo").value("NOTIFICACAO"))
        .andExpect(jsonPath("$.concedido").value(true));

    // Append-only: a evidência de que ela havia revogado antes continua no banco.
    assertThat(contarConsentimentos(ALICE_ID)).isEqualTo(antes + 1);

    mockMvc
        .perform(autenticado(get(BASE + "/consentimentos"), ALICE_ID))
        .andExpect(jsonPath("$[1].tipo").value("NOTIFICACAO"))
        .andExpect(jsonPath("$[1].concedido").value(true));
  }

  @Test
  void registrar_sem_versao_do_texto_responde_400() throws Exception {
    // Sem a versão, o registro não diz COM O QUE a pessoa concordou — e aí não serve como
    // evidência.
    mockMvc
        .perform(
            autenticado(put(BASE + "/consentimentos/TERMOS"), ALICE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"concedido\":true}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/requisicao-invalida"));
  }

  @Test
  void tipo_de_consentimento_desconhecido_responde_400() throws Exception {
    mockMvc
        .perform(
            autenticado(put(BASE + "/consentimentos/MARKETING"), ALICE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"concedido\":true,\"versaoTexto\":\"teste\"}"))
        .andExpect(status().isBadRequest());
  }

  // ─── Exclusão de conta ─────────────────────────────────────────────────────────────────────

  @Test
  void excluir_anonimiza_preservando_o_ledger_e_revoga_a_sessao() throws Exception {
    UUID vitima = criarUsuarioDescartavel();
    UUID refresh = criarRefreshToken(vitima);

    mockMvc
        .perform(
            autenticado(delete(BASE), vitima)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"senha\":\"" + SENHA + "\"}"))
        .andExpect(status().isNoContent());

    Map<String, Object> linha =
        jdbcTemplate.queryForMap(
            "SELECT nome, email, handle, status, anonimizado_em FROM usuario WHERE id = ?", vitima);

    // A LINHA CONTINUA EXISTINDO: apagá-la quebraria a integridade do ledger append-only e a
    // conservação de TOKEN deixaria de fechar.
    assertThat(linha.get("nome")).isEqualTo("Usuário removido");
    assertThat(linha.get("email")).asString().endsWith("@anonimizado.invalid");
    assertThat(linha.get("handle")).asString().startsWith("removido_");
    assertThat(linha.get("status")).isEqualTo("INATIVO");
    assertThat(linha.get("anonimizado_em")).isNotNull();

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT revogado_em IS NOT NULL FROM refresh_token WHERE id = ?",
                Boolean.class,
                refresh))
        .isTrue();
  }

  /**
   * O access token vive 15 minutos e sobrevive à exclusão. Sem este filtro, a tela de perfil
   * mostraria "Usuário removido" com o XP intacto — o fantasma da conta que acabou de ser apagada.
   */
  @Test
  void perfil_de_conta_anonimizada_responde_404() throws Exception {
    UUID vitima = criarUsuarioDescartavel();

    mockMvc
        .perform(
            autenticado(delete(BASE), vitima)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"senha\":\"" + SENHA + "\"}"))
        .andExpect(status().isNoContent());

    mockMvc.perform(autenticado(get(BASE), vitima)).andExpect(status().isNotFound());
  }

  @Test
  void senha_errada_responde_403_e_nao_anonimiza() throws Exception {
    UUID vitima = criarUsuarioDescartavel();

    mockMvc
        .perform(
            autenticado(delete(BASE), vitima)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"senha\":\"errada-mesmo\"}"))
        // 403 e não 401: o token está válido; quem falhou foi a reconfirmação. Um 401 faria o app
        // tratar como sessão expirada e deslogar, escondendo o motivo real.
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/acesso-negado"));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT anonimizado_em IS NULL FROM usuario WHERE id = ?", Boolean.class, vitima))
        .isTrue();
  }

  @Test
  void sem_senha_no_corpo_responde_400() throws Exception {
    UUID vitima = criarUsuarioDescartavel();

    mockMvc
        .perform(
            autenticado(delete(BASE), vitima).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
  }

  /** Retry de rede sobre operação irreversível não pode devolver erro. */
  @Test
  void excluir_duas_vezes_e_idempotente() throws Exception {
    UUID vitima = criarUsuarioDescartavel();

    for (int i = 0; i < 2; i++) {
      mockMvc
          .perform(
              autenticado(delete(BASE), vitima)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"senha\":\"" + SENHA + "\"}"))
          .andExpect(status().isNoContent());
    }

    // E a segunda passagem não regerou nome/e-mail: o no-op é real, não uma reanonimização.
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM usuario WHERE id = ? AND nome = 'Usuário removido'",
                Long.class,
                vitima))
        .isEqualTo(1L);
  }

  // ─── Apoio ─────────────────────────────────────────────────────────────────────────────────

  private UUID criarUsuarioDescartavel() {
    UUID id = UUID.randomUUID();
    String sufixo = id.toString().substring(0, 8);
    jdbcTemplate.update(
        """
        INSERT INTO usuario (id, nome, email, senha_hash, handle, tribo_id, xp, nivel, streak,
                             rating, papel, status, criado_em, atualizado_em, versao)
        VALUES (?, 'Descartável', ?, '{bcrypt}' || crypt(?, gen_salt('bf', 10)), ?,
                NULL, 0, 1, 0, 0.0, 'USUARIO', 'ATIVO', NOW(), NOW(), 0)
        """,
        id,
        "descartavel+" + sufixo + "@teste.dev",
        SENHA,
        "desc_" + sufixo);
    descartaveis.add(id);
    return id;
  }

  private UUID criarRefreshToken(UUID usuarioId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO refresh_token (id, usuario_id, token_hash, familia_id, expira_em)
        VALUES (?, ?, ?, ?, NOW() + INTERVAL '30 days')
        """,
        id,
        usuarioId,
        "hash-" + id,
        UUID.randomUUID());
    return id;
  }

  private long contarConsentimentos(UUID usuarioId) {
    Long total =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM consentimento WHERE usuario_id = ?", Long.class, usuarioId);
    return total == null ? 0 : total;
  }

  private MockHttpServletRequestBuilder autenticado(
      MockHttpServletRequestBuilder builder, UUID usuarioId) {
    return builder.header(
        "Authorization",
        "Bearer " + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", "USUARIO"));
  }
}
