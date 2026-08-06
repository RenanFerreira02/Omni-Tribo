package com.omnitribo.missoes.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Aceite concorrente: 50 executores disputam a mesma missão e exatamente um vence.
 *
 * <p>Por que 50 usuários distintos e não 50 requisições do mesmo usuário: com um único usuário o
 * teste seria vazio — a segunda requisição falharia porque o status já é ACEITA, provando nada
 * sobre corrida. A disputa real do produto é entre executores diferentes. Como efeito colateral, o
 * RateLimitFilter chaveia o bucket por 'sub' do JWT, então 50 subjects são 50 buckets com uma
 * escrita cada e o limite não interfere — o que o teste ainda assim verifica, afirmando zero 429.
 *
 * <p>Por que HTTP real e não chamar o service direto: só o roundtrip prova que o perdedor recebe
 * 409 como ProblemDetail, e não uma exceção de infraestrutura vazada como 500.
 */
@Import(JwtTestConfig.class)
class MissaoAceiteConcorrenteTest extends TesteIntegracaoMvcBase {

  private static final Logger log = LoggerFactory.getLogger(MissaoAceiteConcorrenteTest.class);

  private static final int THREADS = 50;
  private static final UUID ALICE_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
  private static final String BASE = "/api/v1/missoes";

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbcTemplate;

  private final List<UUID> candidatos = new ArrayList<>();

  /**
   * missao.executor_id tem FK para usuario, então os 50 concorrentes precisam existir de verdade.
   * São inseridos por JdbcTemplate (artefato de teste, não migration) e removidos no final — nenhum
   * outro teste depende de contagem absoluta de usuários.
   */
  @BeforeEach
  void criarCandidatos() {
    candidatos.clear();
    for (int i = 0; i < THREADS; i++) {
      UUID id = UUID.randomUUID();
      jdbcTemplate.update(
          """
          INSERT INTO usuario (id, nome, email, senha_hash, handle, xp, nivel, streak, rating,
                               papel, status, criado_em, atualizado_em, versao)
          VALUES (?, ?, ?, '{bcrypt}$2a$10$naoUsadoNesteTeste', ?, 0, 1, 0, 0.0,
                  'USUARIO', 'ATIVO', NOW(), NOW(), 0)
          """,
          id,
          "Concorrente " + i,
          "concorrente-" + id + "@teste.dev",
          "conc" + id.toString().substring(0, 8));
      candidatos.add(id);
    }
  }

  @Test
  void cinquentaThreadsAceitandoMesmaMissao_apenasUmaVence() throws Exception {
    UUID missaoId = criarEPublicarMissao();

    CountDownLatch largada = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    List<Future<Integer>> respostas = new ArrayList<>(THREADS);

    for (UUID candidato : candidatos) {
      respostas.add(pool.submit(aceitarAoSinal(missaoId, candidato, largada)));
    }

    // Todas as threads já estão bloqueadas no latch: soltar de uma vez maximiza a sobreposição
    // real das transações, em vez de deixá-las se enfileirarem naturalmente na criação.
    largada.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

    List<Integer> status = new ArrayList<>(THREADS);
    for (Future<Integer> resposta : respostas) {
      status.add(resposta.get());
    }

    // Distribuição impressa para servir de evidência no relatório da fase — as assertions abaixo
    // são o que de fato reprova o build.
    log.info(
        "Aceite concorrente com {} threads → 200: {} | 409: {} | 429: {} | 500: {}",
        THREADS,
        contar(status, 200),
        contar(status, 409),
        contar(status, 429),
        contar(status, 500));

    assertThat(contar(status, 200)).as("exatamente um aceite pode vencer").isEqualTo(1);
    assertThat(contar(status, 409))
        .as("todos os perdedores recebem 409, o mesmo conflito de qualquer transição inválida")
        .isEqualTo(THREADS - 1);
    assertThat(contar(status, 429)).as("rate limit não pode mascarar o resultado").isZero();
    assertThat(contar(status, 500)).as("nenhuma exceção de infraestrutura pode vazar").isZero();

    // O banco tem de contar a mesma história que o HTTP contou.
    String statusFinal =
        jdbcTemplate.queryForObject(
            "SELECT status FROM missao WHERE id = ?", String.class, missaoId);
    UUID executorFinal =
        jdbcTemplate.queryForObject(
            "SELECT executor_id FROM missao WHERE id = ?", UUID.class, missaoId);
    Long eventosAceite =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM missao_evento WHERE missao_id = ? AND tipo = 'ACEITA'",
            Long.class,
            missaoId);

    assertThat(statusFinal).isEqualTo("ACEITA");
    assertThat(executorFinal).as("o vencedor ficou registrado como executor").isIn(candidatos);
    assertThat(eventosAceite)
        .as("a trilha append-only não pode registrar dois aceites para a mesma missão")
        .isEqualTo(1L);

    // Aceitar NÃO credita: é a regra que o protótipo violava.
    Long lancamentos =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM lancamento WHERE missao_id = ?", Long.class, missaoId);
    assertThat(lancamentos).as("crédito só pode existir em CONCLUIDA").isZero();

    limpar(missaoId);
  }

  private Callable<Integer> aceitarAoSinal(UUID missaoId, UUID candidato, CountDownLatch largada) {
    return () -> {
      try {
        largada.await();
        MvcResult resultado =
            mockMvc
                .perform(
                    post(BASE + "/{id}/aceitar", missaoId)
                        .header("Authorization", bearer(candidato)))
                .andReturn();
        return resultado.getResponse().getStatus();
      } catch (Exception e) {
        return 500;
      }
    };
  }

  private static long contar(List<Integer> status, int alvo) {
    return status.stream().filter(s -> s == alvo).count();
  }

  private UUID criarEPublicarMissao() throws Exception {
    Instant inicio = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    String corpo =
        """
        {
          "categoria": "ENTREGA",
          "titulo": "Missão disputada por muitos executores",
          "descricao": "Usada para verificar serialização do aceite concorrente.",
          "valorBrl": 40.00,
          "tokensRecompensa": 20,
          "xpRecompensa": 150,
          "origemLat": -23.5629,
          "origemLon": -46.6996,
          "cep": "05422030",
          "logradouro": "Rua dos Pinheiros",
          "bairro": "Pinheiros",
          "cidade": "São Paulo",
          "uf": "SP",
          "raioCheckinM": 50,
          "janelaInicio": "%s",
          "janelaFim": "%s"
        }
        """
            .formatted(inicio, inicio.plus(2, ChronoUnit.DAYS));

    MvcResult criacao =
        mockMvc
            .perform(
                post(BASE)
                    .header("Authorization", bearer(ALICE_ID))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(corpo))
            .andExpect(status().isCreated())
            .andReturn();

    UUID missaoId =
        UUID.fromString(
            objectMapper.readTree(criacao.getResponse().getContentAsString()).get("id").asText());

    mockMvc
        .perform(post(BASE + "/{id}/publicar", missaoId).header("Authorization", bearer(ALICE_ID)))
        .andExpect(status().isOk());

    return missaoId;
  }

  private void limpar(UUID missaoId) {
    jdbcTemplate.update("DELETE FROM missao_evento WHERE missao_id = ?", missaoId);
    jdbcTemplate.update("DELETE FROM missao WHERE id = ?", missaoId);
    for (UUID candidato : candidatos) {
      jdbcTemplate.update("DELETE FROM usuario WHERE id = ?", candidato);
    }
  }

  private String bearer(UUID usuarioId) {
    return "Bearer "
        + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", "USUARIO");
  }
}
