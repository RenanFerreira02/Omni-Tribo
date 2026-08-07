package com.omnitribo.missoes.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * Check-in sob concorrência real.
 *
 * <p>O check-in é operação de valor: é o único portão para AGUARDANDO_CONFIRMACAO, que é o único
 * caminho para CONCLUIDA, que é o único ponto que poderá creditar carteira na F7. A regra do
 * projeto exige teste multi-thread para esse tipo de operação, e há duas corridas distintas a
 * provar.
 *
 * <p>Ambas dependem do mesmo mecanismo: o {@code SELECT ... FOR UPDATE} de {@code
 * MissaoRepository.buscarParaAtualizar} serializa requisições sobre a MESMA missão. Como a chave de
 * idempotência inclui o missaoId no material do hash, duas requisições com a mesma chave são
 * necessariamente da mesma missão — logo, sempre serializadas.
 */
@Import(JwtTestConfig.class)
class CheckinConcorrenteTest extends TesteIntegracaoMvcBase {

  private static final int THREADS = 50;

  private static final UUID ALICE_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
  private static final UUID BOB_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000003");

  private static final String BASE = "/api/v1/missoes";
  private static final String LAT_ORIGEM = "-3.1181";
  private static final String LON_ORIGEM = "-60.0217";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  private final List<UUID> missoesCriadas = new ArrayList<>();

  @BeforeEach
  void limparCheckinsDoExecutor() {
    jdbcTemplate.update("DELETE FROM checkin WHERE usuario_id = ?", BOB_ID);
  }

  @AfterEach
  void limpar() {
    for (UUID id : missoesCriadas) {
      jdbcTemplate.update("DELETE FROM checkin WHERE missao_id = ?", id);
      jdbcTemplate.update("DELETE FROM missao_evento WHERE missao_id = ?", id);
      jdbcTemplate.update("DELETE FROM missao WHERE id = ?", id);
    }
    missoesCriadas.clear();
  }

  /**
   * Mesma chave em 50 threads: idempotência sob corrida. Todas recebem 200 e o mesmo estado, e
   * existe exatamente UMA linha em checkin. Um 500 aqui significaria que a violação da constraint
   * uk_checkin_idempotencia vazou como erro de infraestrutura em vez de virar replay.
   */
  @Test
  void cinquenta_requisicoes_com_a_mesma_chave_gravam_um_unico_checkin() throws Exception {
    UUID missaoId = missaoEmAndamento();
    String chave = "corrida-mesma-chave-" + missaoId;

    List<Integer> status = dispararEmParalelo(missaoId, i -> chave);

    assertThat(status)
        .as("nenhuma requisição pode falhar com erro de infraestrutura")
        .doesNotContain(500);
    assertThat(status).as("idempotência: todas devolvem o mesmo resultado").containsOnly(200);
    assertThat(contarCheckins(missaoId)).isEqualTo(1);
    assertThat(statusDaMissao(missaoId)).isEqualTo("AGUARDANDO_CONFIRMACAO");
    // Uma transição, uma linha de trilha — não 50.
    assertThat(contarEventos(missaoId, "CHECK_IN_REGISTRADO")).isEqualTo(1);
  }

  /**
   * Chaves DIFERENTES em 50 threads: aqui a idempotência não ajuda, e quem protege é a máquina de
   * estados. Um vence e transiciona; os 49 restantes encontram a missão fora de EM_ANDAMENTO e
   * levam 409 — o mesmo 409 de qualquer transição inválida, não um erro especial.
   */
  @Test
  void cinquenta_requisicoes_com_chaves_distintas_produzem_um_vencedor_e_49_conflitos()
      throws Exception {
    UUID missaoId = missaoEmAndamento();

    List<Integer> status = dispararEmParalelo(missaoId, i -> "corrida-chave-distinta-" + i);

    assertThat(status).doesNotContain(500);
    assertThat(status).filteredOn(s -> s == 200).hasSize(1);
    assertThat(status).filteredOn(s -> s == 409).hasSize(THREADS - 1);
    // O que realmente importa: uma linha só na trilha antifraude. 50 linhas inventariam um padrão
    // de tentativas que nunca existiu.
    assertThat(contarCheckins(missaoId)).isEqualTo(1);
    assertThat(statusDaMissao(missaoId)).isEqualTo("AGUARDANDO_CONFIRMACAO");
  }

  // ─── Apoio ─────────────────────────────────────────────────────────────────────────────────

  private List<Integer> dispararEmParalelo(
      UUID missaoId, java.util.function.IntFunction<String> chaveDe) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    CountDownLatch largada = new CountDownLatch(1);
    List<Callable<Integer>> tarefas = new ArrayList<>(THREADS);

    for (int i = 0; i < THREADS; i++) {
      String chave = chaveDe.apply(i);
      tarefas.add(
          () -> {
            largada.await();
            MvcResult resultado =
                mockMvc
                    .perform(
                        post(BASE + "/{id}/checkin", missaoId)
                            .header("Authorization", bearer(BOB_ID))
                            .header("Idempotency-Key", chave)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(corpoCheckin()))
                    .andReturn();
            return resultado.getResponse().getStatus();
          });
    }

    List<Future<Integer>> futuros = new ArrayList<>(THREADS);
    for (Callable<Integer> tarefa : tarefas) {
      futuros.add(pool.submit(tarefa));
    }
    largada.countDown();

    List<Integer> status = new ArrayList<>(THREADS);
    for (Future<Integer> futuro : futuros) {
      status.add(futuro.get(60, TimeUnit.SECONDS));
    }
    pool.shutdown();
    assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    return status;
  }

  private static String corpoCheckin() {
    return """
        { "lat": %s, "lon": %s, "acuraciaM": 10, "mocked": false }
        """
        .formatted(LAT_ORIGEM, LON_ORIGEM);
  }

  private long contarCheckins(UUID missaoId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM checkin WHERE missao_id = ?", Long.class, missaoId);
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

  private UUID missaoEmAndamento() throws Exception {
    Instant inicio = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Instant fim = inicio.plus(2, ChronoUnit.DAYS);
    String corpo =
        """
        {
          "categoria": "ENTREGA",
          "titulo": "Missao para check-in concorrente",
          "descricao": "Fixture do teste de concorrencia.",
          "valorBrl": 25.00,
          "tokensRecompensa": 10,
          "xpRecompensa": 100,
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
            .formatted(LAT_ORIGEM, LON_ORIGEM, inicio, fim);

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
    mockMvc
        .perform(post(BASE + "/{id}/aceitar", id).header("Authorization", bearer(BOB_ID)))
        .andExpect(status().isOk());
    mockMvc
        .perform(post(BASE + "/{id}/iniciar", id).header("Authorization", bearer(BOB_ID)))
        .andExpect(status().isOk());
    return id;
  }

  private String bearer(UUID usuarioId) {
    return "Bearer "
        + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", "USUARIO");
  }
}
