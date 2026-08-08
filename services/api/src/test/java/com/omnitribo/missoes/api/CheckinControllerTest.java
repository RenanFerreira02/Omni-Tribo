package com.omnitribo.missoes.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Check-in geolocalizado: contrato HTTP + o que ficou gravado na trilha.
 *
 * <p>Cada teste começa com a tabela checkin limpa para os usuários que usa. Não é higiene
 * decorativa: a checagem de plausibilidade cinemática lê o ÚLTIMO check-in do usuário, então
 * resíduo de outro método de teste mudaria o veredito deste (um check-in aceito viraria suspeito).
 * O DELETE funciona aqui porque o Testcontainer conecta como superusuário — em produção o REVOKE da
 * V4 impede DELETE em checkin, e é por isso que NÃO existe teste afirmando que o append-only é
 * aplicado: ele passaria pelo motivo errado.
 */
@Import(JwtTestConfig.class)
class CheckinControllerTest extends TesteIntegracaoMvcBase {

  private static final UUID ALICE_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
  private static final UUID BOB_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000003");
  private static final UUID CAROL_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000004");
  private static final UUID DIANA_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000005");
  private static final UUID ERIK_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000006");

  private static final List<UUID> EXECUTORES = List.of(BOB_ID, CAROL_ID, DIANA_ID, ERIK_ID);

  private static final String BASE = "/api/v1/missoes";

  // Origem das missões, em Manaus.
  private static final String LAT_ORIGEM = "-3.1181";
  private static final String LON_ORIGEM = "-60.0217";

  // Deslocamentos em latitude. Nesta latitude o grau de meridiano do WGS84 mede ~110 578 m, então
  // 49 m = 0,00044313° e 51 m = 0,00046121°. Os testes de limite não confiam nesta aritmética: eles
  // conferem a distancia_alvo_m que o PostGIS de fato mediu e gravou.
  private static final String LAT_A_49M = "-3.1176569";
  private static final String LAT_A_51M = "-3.1176388";
  private static final String LAT_A_5M = "-3.1180548";

  // São Paulo — usado só para produzir um deslocamento impossível entre dois check-ins.
  private static final String LAT_SAO_PAULO = "-23.5629";
  private static final String LON_SAO_PAULO = "-46.6996";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  private final List<UUID> missoesCriadas = new ArrayList<>();

  @BeforeEach
  void limparCheckinsDosExecutores() {
    for (UUID executor : EXECUTORES) {
      jdbcTemplate.update("DELETE FROM checkin WHERE usuario_id = ?", executor);
    }
  }

  @AfterEach
  void limparMissoes() {
    for (UUID id : missoesCriadas) {
      jdbcTemplate.update("DELETE FROM checkin WHERE missao_id = ?", id);
      jdbcTemplate.update("DELETE FROM missao_evento WHERE missao_id = ?", id);
      jdbcTemplate.update("DELETE FROM missao WHERE id = ?", id);
    }
    missoesCriadas.clear();
  }

  // ─── Caminho feliz ─────────────────────────────────────────────────────────────────────────

  @Test
  void checkin_dentro_do_raio_transiciona_e_grava_linha_valida() throws Exception {
    UUID missaoId = missaoEmAndamento(BOB_ID);

    mockMvc
        .perform(checkin(missaoId, BOB_ID, LAT_A_5M, LON_ORIGEM, "10", false, "chave-feliz"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("AGUARDANDO_CONFIRMACAO"));

    Map<String, Object> linha = ultimoCheckin(missaoId);
    assertThat(linha.get("valido")).isEqualTo(true);
    assertThat(linha.get("suspeito")).isEqualTo(false);
    assertThat(linha.get("motivo_rejeicao")).isNull();
    assertThat(linha.get("mock_detectado")).isEqualTo(false);
    assertThat((BigDecimal) linha.get("distancia_alvo_m")).isLessThan(new BigDecimal("50"));
    // Primeiro check-in do usuário: não há anterior contra o que medir velocidade.
    assertThat(linha.get("velocidade_implicita_kmh")).isNull();
  }

  // ─── Limite exato do raio ──────────────────────────────────────────────────────────────────

  @Test
  void a_49_metros_de_um_raio_de_50_aceita() throws Exception {
    UUID missaoId = missaoEmAndamento(DIANA_ID);

    mockMvc
        .perform(checkin(missaoId, DIANA_ID, LAT_A_49M, LON_ORIGEM, "10", false, "chave-49"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("AGUARDANDO_CONFIRMACAO"));

    // Confere o que o PostGIS mediu, não o que a aritmética do teste supôs.
    BigDecimal medida = (BigDecimal) ultimoCheckin(missaoId).get("distancia_alvo_m");
    assertThat(medida).isBetween(new BigDecimal("47"), new BigDecimal("50"));
  }

  @Test
  void a_51_metros_de_um_raio_de_50_rejeita() throws Exception {
    UUID missaoId = missaoEmAndamento(ERIK_ID);

    mockMvc
        .perform(checkin(missaoId, ERIK_ID, LAT_A_51M, LON_ORIGEM, "10", false, "chave-51"))
        .andExpect(status().isUnprocessableEntity());

    Map<String, Object> linha = ultimoCheckin(missaoId);
    assertThat((BigDecimal) linha.get("distancia_alvo_m"))
        .isBetween(new BigDecimal("50.01"), new BigDecimal("53"));
    assertThat(linha.get("valido")).isEqualTo(false);
  }

  // ─── O teste central da fase ───────────────────────────────────────────────────────────────

  /**
   * Uma implementação ingênua que lançasse a exceção dentro da transação externa devolveria o 422
   * correto e perderia a linha de auditoria em silêncio. Nenhum outro teste pegaria isso — é a
   * razão de o registro rodar em REQUIRES_NEW.
   */
  @Test
  void checkin_fora_do_raio_responde_422_e_a_linha_de_auditoria_sobrevive() throws Exception {
    UUID missaoId = missaoEmAndamento(BOB_ID);

    mockMvc
        .perform(
            checkin(missaoId, BOB_ID, LAT_SAO_PAULO, LON_SAO_PAULO, "10", false, "chave-longe"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("raio")));

    assertThat(contarCheckins(missaoId)).isEqualTo(1);

    Map<String, Object> linha = ultimoCheckin(missaoId);
    assertThat(linha.get("valido")).isEqualTo(false);
    assertThat(linha.get("motivo_rejeicao")).asString().isNotBlank();

    // E a missão NÃO transicionou: a transação externa sofreu rollback como deveria.
    assertThat(statusDaMissao(missaoId)).isEqualTo("EM_ANDAMENTO");
  }

  // ─── Demais rejeições ──────────────────────────────────────────────────────────────────────

  @Test
  void acuracia_acima_de_50_responde_422_e_grava_a_tentativa() throws Exception {
    UUID missaoId = missaoEmAndamento(BOB_ID);

    mockMvc
        .perform(checkin(missaoId, BOB_ID, LAT_A_5M, LON_ORIGEM, "51", false, "chave-acuracia"))
        .andExpect(status().isUnprocessableEntity());

    Map<String, Object> linha = ultimoCheckin(missaoId);
    assertThat(linha.get("valido")).isEqualTo(false);
    assertThat(linha.get("motivo_rejeicao")).asString().contains("Precisão");
    assertThat(statusDaMissao(missaoId)).isEqualTo("EM_ANDAMENTO");
  }

  @Test
  void mocked_responde_422_e_grava_mock_detectado() throws Exception {
    UUID missaoId = missaoEmAndamento(BOB_ID);

    mockMvc
        .perform(checkin(missaoId, BOB_ID, LAT_A_5M, LON_ORIGEM, "10", true, "chave-mock"))
        .andExpect(status().isUnprocessableEntity());

    Map<String, Object> linha = ultimoCheckin(missaoId);
    assertThat(linha.get("valido")).isEqualTo(false);
    assertThat(linha.get("mock_detectado")).isEqualTo(true);
    assertThat(statusDaMissao(missaoId)).isEqualTo("EM_ANDAMENTO");
  }

  // ─── Autorização e ciclo de vida: erro ANTES de gravar ──────────────────────────────────────

  @Test
  void checkin_de_quem_nao_e_executor_responde_403_sem_gravar_linha() throws Exception {
    UUID missaoId = missaoEmAndamento(BOB_ID);

    mockMvc
        .perform(checkin(missaoId, CAROL_ID, LAT_A_5M, LON_ORIGEM, "10", false, "chave-intrusa"))
        .andExpect(status().isForbidden());

    // Não é tentativa de check-in que falhou na validação geoespacial: é chamada de quem não tem
    // nada a ver com a missão. Gravar isso poluiria a trilha antifraude com ruído.
    assertThat(contarCheckins(missaoId)).isZero();
  }

  /**
   * Missão ABERTA ainda não tem executor, então {@code ehMesmo(null)} é falso e a autorização barra
   * antes da transição: 403, não 409. É o contrato correto — responder 409 aqui contaria a um
   * estranho em que estado está uma missão que ele não executa.
   */
  @Test
  void checkin_em_missao_sem_executor_responde_403_sem_gravar_linha() throws Exception {
    UUID missaoId = criarEPublicar(); // ABERTA, ninguém aceitou

    mockMvc
        .perform(checkin(missaoId, BOB_ID, LAT_A_5M, LON_ORIGEM, "10", false, "chave-sem-executor"))
        .andExpect(status().isForbidden());

    assertThat(contarCheckins(missaoId)).isZero();
  }

  /**
   * O 409 de verdade: o executor é o certo (passa na autorização), mas a missão está ACEITA e ainda
   * não foi iniciada — EM_ANDAMENTO é o único estado de onde CHECKIN transiciona.
   */
  @Test
  void checkin_do_executor_em_missao_aceita_mas_nao_iniciada_responde_409_sem_gravar_linha()
      throws Exception {
    UUID missaoId = criarEPublicar();
    mockMvc
        .perform(post(BASE + "/{id}/aceitar", missaoId).header("Authorization", bearer(BOB_ID)))
        .andExpect(status().isOk());

    mockMvc
        .perform(checkin(missaoId, BOB_ID, LAT_A_5M, LON_ORIGEM, "10", false, "chave-nao-iniciada"))
        .andExpect(status().isConflict());

    assertThat(contarCheckins(missaoId)).isZero();
    assertThat(statusDaMissao(missaoId)).isEqualTo("ACEITA");
  }

  // ─── Idempotência ──────────────────────────────────────────────────────────────────────────

  @Test
  void mesma_chave_duas_vezes_devolve_o_mesmo_resultado_sem_novo_registro() throws Exception {
    UUID missaoId = missaoEmAndamento(BOB_ID);

    MvcResult primeira =
        mockMvc
            .perform(checkin(missaoId, BOB_ID, LAT_A_5M, LON_ORIGEM, "10", false, "chave-repetida"))
            .andExpect(status().isOk())
            .andReturn();

    MvcResult segunda =
        mockMvc
            .perform(checkin(missaoId, BOB_ID, LAT_A_5M, LON_ORIGEM, "10", false, "chave-repetida"))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(contarCheckins(missaoId)).isEqualTo(1);
    assertThat(JSON.readTree(segunda.getResponse().getContentAsString()).get("status").asText())
        .isEqualTo(
            JSON.readTree(primeira.getResponse().getContentAsString()).get("status").asText())
        .isEqualTo("AGUARDANDO_CONFIRMACAO");
  }

  @Test
  void chave_repetida_numa_rejeicao_tambem_replica_sem_novo_registro() throws Exception {
    UUID missaoId = missaoEmAndamento(BOB_ID);

    mockMvc
        .perform(
            checkin(missaoId, BOB_ID, LAT_SAO_PAULO, LON_SAO_PAULO, "10", false, "chave-rejeitada"))
        .andExpect(status().isUnprocessableEntity());
    mockMvc
        .perform(
            checkin(missaoId, BOB_ID, LAT_SAO_PAULO, LON_SAO_PAULO, "10", false, "chave-rejeitada"))
        .andExpect(status().isUnprocessableEntity());

    // Idempotência vale para o fracasso também: um retry de rede não pode multiplicar linhas na
    // trilha antifraude e inventar um padrão de tentativas que não existiu.
    assertThat(contarCheckins(missaoId)).isEqualTo(1);
  }

  @Test
  void chave_de_cliente_igual_vinda_de_outro_usuario_nao_colide() throws Exception {
    UUID missaoBob = missaoEmAndamento(BOB_ID);
    UUID missaoCarol = missaoEmAndamento(CAROL_ID);

    // A MESMA string de chave, de dois usuários diferentes. Se a coluna guardasse a chave crua, a
    // segunda chamada bateria na UNIQUE e receberia o replay do check-in do Bob. Uma chave banal e
    // previsível como esta é exatamente o que um cliente ingênuo geraria.
    String mesmaChave = "chave-banal-do-cliente";
    mockMvc
        .perform(checkin(missaoBob, BOB_ID, LAT_A_5M, LON_ORIGEM, "10", false, mesmaChave))
        .andExpect(status().isOk());
    mockMvc
        .perform(checkin(missaoCarol, CAROL_ID, LAT_A_5M, LON_ORIGEM, "10", false, mesmaChave))
        .andExpect(status().isOk());

    assertThat(contarCheckins(missaoBob)).isEqualTo(1);
    assertThat(contarCheckins(missaoCarol)).isEqualTo(1);
    assertThat(statusDaMissao(missaoCarol)).isEqualTo("AGUARDANDO_CONFIRMACAO");
  }

  /**
   * Segundo check-in com chave NOVA depois que a missão já transicionou. É o caso realista do
   * usuário que toca duas vezes, ou do app que reinicia e gera outra Idempotency-Key: a
   * idempotência não ajuda, e quem protege é a máquina de estados.
   */
  @Test
  void segundo_checkin_com_chave_nova_apos_transicao_responde_409_sem_gravar_linha()
      throws Exception {
    UUID missaoId = missaoEmAndamento(BOB_ID);

    mockMvc
        .perform(checkin(missaoId, BOB_ID, LAT_A_5M, LON_ORIGEM, "10", false, "chave-primeira"))
        .andExpect(status().isOk());

    mockMvc
        .perform(checkin(missaoId, BOB_ID, LAT_A_5M, LON_ORIGEM, "10", false, "chave-segunda"))
        .andExpect(status().isConflict());

    assertThat(contarCheckins(missaoId)).isEqualTo(1);
    assertThat(statusDaMissao(missaoId)).isEqualTo("AGUARDANDO_CONFIRMACAO");
  }

  /**
   * O campo mocked é Boolean e ausente equivale a false. Se alguém trocar por primitivo, ou
   * inverter a lógica de mockedOuFalso(), todo cliente que omite o campo passa a ser recusado como
   * mock — e sem este teste a suíte continuaria verde, porque todos os outros preenchem o campo.
   */
  @Test
  void mocked_ausente_no_json_e_tratado_como_false() throws Exception {
    UUID missaoId = missaoEmAndamento(BOB_ID);

    mockMvc
        .perform(
            post(BASE + "/{id}/checkin", missaoId)
                .header("Authorization", bearer(BOB_ID))
                .header("Idempotency-Key", "chave-sem-mocked")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"lat\":%s,\"lon\":%s,\"acuraciaM\":10}".formatted(LAT_A_5M, LON_ORIGEM)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("AGUARDANDO_CONFIRMACAO"));

    assertThat(ultimoCheckin(missaoId).get("mock_detectado")).isEqualTo(false);
  }

  @Test
  void checkin_rejeitado_nao_deixa_evento_na_trilha_da_missao() throws Exception {
    UUID missaoId = missaoEmAndamento(BOB_ID);

    mockMvc
        .perform(
            checkin(
                missaoId, BOB_ID, LAT_SAO_PAULO, LON_SAO_PAULO, "10", false, "chave-sem-trilha"))
        .andExpect(status().isUnprocessableEntity());

    // A linha em checkin sobrevive; o evento de transição não pode existir, porque transição
    // nenhuma ocorreu. As duas coisas juntas provam que só a parte certa foi commitada.
    assertThat(contarCheckins(missaoId)).isEqualTo(1);
    assertThat(contarEventos(missaoId, "CHECK_IN_REGISTRADO")).isZero();
  }

  @Test
  void checkin_em_missao_inexistente_responde_404() throws Exception {
    mockMvc
        .perform(
            checkin(
                UUID.randomUUID(), BOB_ID, LAT_A_5M, LON_ORIGEM, "10", false, "chave-inexistente"))
        .andExpect(status().isNotFound());
  }

  /**
   * Chave curta demais é recusada. Não é preciosismo: uma chave trivial e constante prenderia o
   * cliente para sempre no replay do primeiro check-in daquela missão — inclusive no de uma
   * rejeição, que ele nunca conseguiria repetir para corrigir.
   */
  @Test
  void chave_de_idempotencia_curta_demais_responde_400() throws Exception {
    UUID missaoId = missaoEmAndamento(BOB_ID);

    mockMvc
        .perform(checkin(missaoId, BOB_ID, LAT_A_5M, LON_ORIGEM, "10", false, "1"))
        .andExpect(status().isBadRequest());

    assertThat(contarCheckins(missaoId)).isZero();
  }

  @Test
  void sem_header_de_idempotencia_responde_400() throws Exception {
    UUID missaoId = missaoEmAndamento(BOB_ID);

    mockMvc
        .perform(
            post(BASE + "/{id}/checkin", missaoId)
                .header("Authorization", bearer(BOB_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoCheckin(LAT_A_5M, LON_ORIGEM, "10", false)))
        .andExpect(status().isBadRequest());

    assertThat(contarCheckins(missaoId)).isZero();
  }

  // ─── Cinemática ────────────────────────────────────────────────────────────────────────────

  /**
   * Dois check-ins do mesmo usuário a ~2 700 km de distância, com segundos entre eles. É aceito — a
   * regra marca, não rejeita — e a resposta não conta ao cliente que ele foi sinalizado.
   */
  @Test
  void velocidade_implausivel_aceita_mas_marca_suspeito() throws Exception {
    UUID missaoManaus = missaoEmAndamento(BOB_ID, LAT_ORIGEM, LON_ORIGEM);
    UUID missaoSaoPaulo = missaoEmAndamento(BOB_ID, LAT_SAO_PAULO, LON_SAO_PAULO);

    mockMvc
        .perform(checkin(missaoManaus, BOB_ID, LAT_ORIGEM, LON_ORIGEM, "10", false, "chave-mn"))
        .andExpect(status().isOk());

    MvcResult segunda =
        mockMvc
            .perform(
                checkin(
                    missaoSaoPaulo, BOB_ID, LAT_SAO_PAULO, LON_SAO_PAULO, "10", false, "chave-sp"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("AGUARDANDO_CONFIRMACAO"))
            .andReturn();

    Map<String, Object> linha = ultimoCheckin(missaoSaoPaulo);
    assertThat(linha.get("valido")).isEqualTo(true);
    assertThat(linha.get("suspeito")).isEqualTo(true);
    assertThat((BigDecimal) linha.get("velocidade_implicita_kmh"))
        .isGreaterThan(new BigDecimal("120"));

    // A suspeita NÃO vaza para o cliente: contar ao fraudador que foi flagrado ensina exatamente
    // quanto desacelerar na próxima tentativa.
    assertThat(segunda.getResponse().getContentAsString()).doesNotContain("suspeito");
  }

  // ─── Apoio ─────────────────────────────────────────────────────────────────────────────────

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder checkin(
      UUID missaoId,
      UUID executor,
      String lat,
      String lon,
      String acuracia,
      boolean mocked,
      String chave) {
    return post(BASE + "/{id}/checkin", missaoId)
        .header("Authorization", bearer(executor))
        .header("Idempotency-Key", chave)
        .contentType(MediaType.APPLICATION_JSON)
        .content(corpoCheckin(lat, lon, acuracia, mocked));
  }

  private static String corpoCheckin(String lat, String lon, String acuracia, boolean mocked) {
    return """
        { "lat": %s, "lon": %s, "acuraciaM": %s, "mocked": %s }
        """
        .formatted(lat, lon, acuracia, mocked);
  }

  private long contarCheckins(UUID missaoId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM checkin WHERE missao_id = ?", Long.class, missaoId);
  }

  private Map<String, Object> ultimoCheckin(UUID missaoId) {
    return jdbcTemplate.queryForMap(
        "SELECT * FROM checkin WHERE missao_id = ? ORDER BY criado_em DESC LIMIT 1", missaoId);
  }

  private long contarEventos(UUID missaoId, String tipo) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM missao_evento WHERE missao_id = ? AND tipo = ?",
        Long.class,
        missaoId,
        tipo);
  }

  private String statusDaMissao(UUID missaoId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM missao WHERE id = ?", String.class, missaoId);
  }

  private UUID missaoEmAndamento(UUID executor) throws Exception {
    return missaoEmAndamento(executor, LAT_ORIGEM, LON_ORIGEM);
  }

  private UUID missaoEmAndamento(UUID executor, String lat, String lon) throws Exception {
    UUID missaoId = criarEPublicar(lat, lon);
    mockMvc
        .perform(post(BASE + "/{id}/aceitar", missaoId).header("Authorization", bearer(executor)))
        .andExpect(status().isOk());
    mockMvc
        .perform(post(BASE + "/{id}/iniciar", missaoId).header("Authorization", bearer(executor)))
        .andExpect(status().isOk());
    return missaoId;
  }

  private UUID criarEPublicar() throws Exception {
    return criarEPublicar(LAT_ORIGEM, LON_ORIGEM);
  }

  private UUID criarEPublicar(String lat, String lon) throws Exception {
    Instant inicio = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Instant fim = inicio.plus(2, ChronoUnit.DAYS);
    String corpo =
        """
        {
          "categoria": "ENTREGA",
          "titulo": "Missao para check-in geolocalizado",
          "descricao": "Fixture do teste de check-in.",
          "valorBrl": 0.00,
          "pesoKg": 10.00,
          "volumeL": 40.00,
          "origemLat": %s,
          "origemLon": %s,
          "cep": "69005040",
          "logradouro": "Avenida Eduardo Ribeiro",
          "bairro": "Centro",
          "cidade": "Manaus",
          "uf": "AM",
          "raioCheckinM": 50,
          "janelaInicio": "%s",
          "janelaFim": "%s"
        }
        """
            .formatted(lat, lon, inicio, fim);

    MvcResult resultado =
        mockMvc
            .perform(
                post(BASE)
                    .header("Authorization", bearer(ALICE_ID))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(corpo))
            .andExpect(status().isCreated())
            .andReturn();

    UUID id =
        UUID.fromString(
            JSON.readTree(resultado.getResponse().getContentAsString()).get("id").asText());
    missoesCriadas.add(id);

    mockMvc
        .perform(post(BASE + "/{id}/publicar", id).header("Authorization", bearer(ALICE_ID)))
        .andExpect(status().isOk());
    return id;
  }

  private String bearer(UUID usuarioId) {
    return "Bearer "
        + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", "USUARIO");
  }
}
