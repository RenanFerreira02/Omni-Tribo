package com.omnitribo.carteira.api;

import static com.omnitribo.carteira.SuporteCarteira.assertLedgerReconcilia;
import static com.omnitribo.carteira.SuporteCarteira.contarLancamentosDaMissao;
import static com.omnitribo.carteira.SuporteCarteira.limparMissao;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import java.math.BigDecimal;
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
 * 100 threads confirmando a MESMA missão: exatamente um crédito.
 *
 * <p>É o teste central da fase. A conclusão é a única operação que credita carteira, e a chave de
 * idempotência dela é derivada da própria missão — sem header do cliente. Portanto as 100
 * requisições produzem a MESMA chave e disputam a mesma linha de {@code lancamento}.
 *
 * <p>Todas as 100 usam o token do MESMO criador, e é assim que tem de ser: {@code /confirmar} exige
 * o criador, então a disputa realista é entre retries de um cliente — exatamente o que acontece
 * quando um app perde a resposta e reenvia. O resultado esperado não é "1 sucesso e 99 conflitos":
 * é <b>100 sucessos</b>, porque um retry não é conflito. O que não pode duplicar é o EFEITO.
 *
 * <p>Se a sondagem de idempotência não estivesse sob o lock da missão, este teste falharia de duas
 * formas possíveis: dois lançamentos gravados (corrida entre sondar e inserir) ou 500 por violação
 * de {@code uk_lancamento_idempotencia}.
 */
@Import(JwtTestConfig.class)
class ConclusaoConcorrenteTest extends TesteIntegracaoMvcBase {

  private static final Logger log = LoggerFactory.getLogger(ConclusaoConcorrenteTest.class);

  private static final int THREADS = 100;
  private static final String BASE = "/api/v1/missoes";

  private static final BigDecimal VALOR_BRL = new BigDecimal("0.00");
  private static final long TOKENS = 20L;
  private static final int XP = 150;

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  @Test
  void cemThreadsConfirmandoMesmaMissao_creditamExatamenteUmaVez() throws Exception {
    UUID criador = criarUsuarioComCarteira("criador");
    UUID executor = criarUsuarioComCarteira("executor");

    BigDecimal brlAntes = saldoBrl(executor);
    long tokensAntes = saldoTokens(executor);
    long xpAntes = xpDe(executor);

    UUID missaoId = montarMissaoAguardandoConfirmacao(criador, executor);

    CountDownLatch largada = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    List<Future<Integer>> respostas = new ArrayList<>(THREADS);

    for (int i = 0; i < THREADS; i++) {
      respostas.add(pool.submit(confirmarAoSinal(missaoId, criador, largada)));
    }

    // Soltar todas de uma vez maximiza a sobreposição real das transações, em vez de deixá-las se
    // enfileirarem naturalmente pelo custo de criação das threads.
    largada.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();

    List<Integer> status = new ArrayList<>(THREADS);
    for (Future<Integer> resposta : respostas) {
      status.add(resposta.get());
    }

    log.info(
        "Conclusão concorrente com {} threads → 200: {} | 409: {} | 422: {} | 429: {} | 500: {}",
        THREADS,
        contar(status, 200),
        contar(status, 409),
        contar(status, 422),
        contar(status, 429),
        contar(status, 500));

    assertThat(contar(status, 200))
        .as("retry da mesma operação é replay idempotente, não conflito — todas respondem 200")
        .isEqualTo(THREADS);
    assertThat(contar(status, 500)).as("nenhuma exceção de infraestrutura pode vazar").isZero();
    assertThat(contar(status, 409))
        .as("sondar a idempotência antes de checar a transição evita 409 em retry legítimo")
        .isZero();

    // ─── O banco tem de contar a mesma história ───────────────────────────────────────────────

    assertThat(contarLancamentosDaMissao(jdbcTemplate, missaoId))
        .as("crédito único: 100 requisições, 1 lançamento")
        .isEqualTo(1L);

    assertThat(saldoBrl(executor))
        .as("BRL creditado exatamente uma vez")
        .isEqualByComparingTo(brlAntes.add(VALOR_BRL));
    assertThat(saldoTokens(executor))
        .as("tokens creditados exatamente uma vez")
        .isEqualTo(tokensAntes + TOKENS);
    assertThat(xpDe(executor))
        .as("XP somado exatamente uma vez — 100 concessões teriam dado 15000")
        .isEqualTo(xpAntes + XP);

    Long eventosConclusao =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM missao_evento WHERE missao_id = ? AND tipo = 'CONFIRMADA'",
            Long.class,
            missaoId);
    assertThat(eventosConclusao).as("trilha append-only registra uma conclusão só").isEqualTo(1L);

    Long eventosOutbox =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox WHERE agregado_id = ? AND tipo_evento = 'MissaoConcluida'",
            Long.class,
            missaoId);
    assertThat(eventosOutbox)
        .as("um fato, um anúncio — o outbox não pode multiplicar a notificação")
        .isEqualTo(1L);

    String statusFinal =
        jdbcTemplate.queryForObject(
            "SELECT status FROM missao WHERE id = ?", String.class, missaoId);
    assertThat(statusFinal).isEqualTo("CONCLUIDA");

    assertLedgerReconcilia(jdbcTemplate);

    limpar(missaoId, criador, executor);
  }

  // ─── Apoio ───────────────────────────────────────────────────────────────────────────────────

  private Callable<Integer> confirmarAoSinal(UUID missaoId, UUID criador, CountDownLatch largada) {
    return () -> {
      try {
        largada.await();
        MvcResult resultado =
            mockMvc
                .perform(
                    post(BASE + "/{id}/confirmar", missaoId)
                        .header("Authorization", bearer(criador)))
                .andReturn();
        return resultado.getResponse().getStatus();
      } catch (Exception e) {
        // Exceção vazada vira 500 para aparecer como falha de asserção, e não como erro de teste.
        return 500;
      }
    };
  }

  private static long contar(List<Integer> status, int alvo) {
    return status.stream().filter(s -> s == alvo).count();
  }

  /** Percorre a máquina de estados de verdade até EM_ANDAMENTO e faz o último salto por SQL. */
  private UUID montarMissaoAguardandoConfirmacao(UUID criador, UUID executor) throws Exception {
    Instant inicio = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    String corpo =
        """
        {
          "categoria": "ENTREGA",
          "titulo": "Missão para conclusão concorrente",
          "descricao": "Verifica que 100 confirmações simultâneas creditam uma vez só.",
          "valorBrl": 0.00,
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
                    .header("Authorization", bearer(criador))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(corpo))
            .andExpect(status().isCreated())
            .andReturn();

    UUID missaoId =
        UUID.fromString(
            JSON.readTree(criacao.getResponse().getContentAsString()).get("id").asText());

    mockMvc
        .perform(post(BASE + "/{id}/publicar", missaoId).header("Authorization", bearer(criador)))
        .andExpect(status().isOk());
    mockMvc
        .perform(post(BASE + "/{id}/aceitar", missaoId).header("Authorization", bearer(executor)))
        .andExpect(status().isOk());
    mockMvc
        .perform(post(BASE + "/{id}/iniciar", missaoId).header("Authorization", bearer(executor)))
        .andExpect(status().isOk());

    // CHECKIN só chega em F6; o salto por SQL é o mesmo recurso já usado em MissaoControllerTest.
    jdbcTemplate.update(
        "UPDATE missao SET status = 'AGUARDANDO_CONFIRMACAO' WHERE id = ?", missaoId);
    return missaoId;
  }

  private UUID criarUsuarioComCarteira(String prefixo) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO usuario (id, nome, email, senha_hash, handle, xp, nivel, streak, rating,
                             papel, status, criado_em, atualizado_em, versao)
        VALUES (?, ?, ?, '{bcrypt}$2a$10$naoUsadoNesteTeste', ?, 0, 1, 0, 0.0,
                'USUARIO', 'ATIVO', NOW(), NOW(), 0)
        """,
        id,
        prefixo + " concorrente",
        prefixo + "-" + id + "@teste.dev",
        prefixo.charAt(0) + id.toString().substring(0, 10));

    jdbcTemplate.update(
        "INSERT INTO carteira (id, usuario_id, saldo_brl, saldo_tokens, versao)"
            + " VALUES (?, ?, 0.00, 0, 0)",
        UUID.randomUUID(),
        id);
    return id;
  }

  private BigDecimal saldoBrl(UUID usuarioId) {
    return jdbcTemplate.queryForObject(
        "SELECT saldo_brl FROM carteira WHERE usuario_id = ?", BigDecimal.class, usuarioId);
  }

  private long saldoTokens(UUID usuarioId) {
    return jdbcTemplate.queryForObject(
        "SELECT saldo_tokens FROM carteira WHERE usuario_id = ?", Long.class, usuarioId);
  }

  private long xpDe(UUID usuarioId) {
    return jdbcTemplate.queryForObject(
        "SELECT xp FROM usuario WHERE id = ?", Long.class, usuarioId);
  }

  private void limpar(UUID missaoId, UUID... usuarios) {
    limparMissao(jdbcTemplate, missaoId);
    for (UUID usuario : usuarios) {
      jdbcTemplate.update(
          "DELETE FROM lancamento WHERE carteira_id IN"
              + " (SELECT id FROM carteira WHERE usuario_id = ?)",
          usuario);
      jdbcTemplate.update("DELETE FROM auditoria WHERE ator_id = ?", usuario);
      jdbcTemplate.update("DELETE FROM carteira WHERE usuario_id = ?", usuario);
      jdbcTemplate.update("DELETE FROM usuario WHERE id = ?", usuario);
    }
  }

  private String bearer(UUID usuarioId) {
    return "Bearer "
        + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", "USUARIO");
  }
}
