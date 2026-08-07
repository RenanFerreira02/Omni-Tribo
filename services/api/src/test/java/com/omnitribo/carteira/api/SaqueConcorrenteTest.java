package com.omnitribo.carteira.api;

import static com.omnitribo.carteira.SuporteCarteira.assertLedgerReconcilia;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import java.math.BigDecimal;
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
 * Double-spend clássico: N saques simultâneos do saldo INTEIRO, com chaves de idempotência
 * DIFERENTES.
 *
 * <p>A distinção da chave é o que faz este teste ser sobre concorrência e não sobre idempotência.
 * Com a mesma chave, as requisições seriam replays e o resultado seria trivial. Com chaves
 * distintas, cada uma é uma operação legítima e independente pedindo todo o saldo — exatamente um
 * pode vencer, e o resto tem de ser recusado com 422 sem nunca levar a conta a negativo.
 *
 * <p>Sem `SELECT ... FOR UPDATE` na carteira, todas leriam saldo 100, todas passariam na
 * verificação e todas debitariam: o saldo terminaria negativo, ou o
 * `ck_carteira_saldo_nao_negativo` do banco derrubaria as perdedoras com 500 em vez do 422 correto.
 *
 * <p>Existe porque o CLAUDE.md é explícito: "Operação de valor (aceite, crédito, transferência,
 * saque) exige teste de concorrência multi-thread." O saque era o único da lista sem um.
 */
@Import(JwtTestConfig.class)
class SaqueConcorrenteTest extends TesteIntegracaoMvcBase {

  private static final Logger log = LoggerFactory.getLogger(SaqueConcorrenteTest.class);

  private static final int THREADS = 20;
  private static final BigDecimal SALDO = new BigDecimal("100.00");
  private static final String BASE = "/api/v1/carteira/saques";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  @Test
  void saquesSimultaneosDoSaldoInteiroDebitamUmaVezSo() throws Exception {
    UUID usuario = criarUsuarioComCarteira();

    CountDownLatch largada = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    List<Future<Integer>> respostas = new ArrayList<>(THREADS);

    for (int i = 0; i < THREADS; i++) {
      respostas.add(pool.submit(sacarTudoAoSinal(usuario, "saque-conc-" + i, largada)));
    }

    largada.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();

    List<Integer> status = new ArrayList<>(THREADS);
    for (Future<Integer> resposta : respostas) {
      status.add(resposta.get());
    }

    log.info(
        "Saque concorrente com {} threads → 201: {} | 422: {} | 500: {}",
        THREADS,
        contar(status, 201),
        contar(status, 422),
        contar(status, 500));

    assertThat(contar(status, 201))
        .as("exatamente um saque do saldo inteiro pode vencer")
        .isEqualTo(1L);
    assertThat(contar(status, 422))
        .as("os perdedores recebem 422 por saldo insuficiente, não 500")
        .isEqualTo(THREADS - 1L);
    assertThat(contar(status, 500))
        .as("nenhum débito pode escapar para a constraint do banco")
        .isZero();

    assertThat(saldoBrl(usuario))
        .as("saldo zerado, nunca negativo")
        .isEqualByComparingTo(BigDecimal.ZERO);

    Long debitos =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM lancamento WHERE carteira_id IN"
                + " (SELECT id FROM carteira WHERE usuario_id = ?) AND motivo = 'SAQUE'",
            Long.class,
            usuario);
    assertThat(debitos).as("um único lançamento de saque no ledger").isEqualTo(1L);

    assertLedgerReconcilia(jdbcTemplate);

    limpar(usuario);
  }

  // ─── Apoio ───────────────────────────────────────────────────────────────────────────────────

  private Callable<Integer> sacarTudoAoSinal(UUID usuario, String chave, CountDownLatch largada) {
    return () -> {
      try {
        largada.await();
        MvcResult resultado =
            mockMvc
                .perform(
                    post(BASE)
                        .header("Authorization", bearer(usuario))
                        .header("Idempotency-Key", chave)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valorBrl\":100.00}"))
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

  private UUID criarUsuarioComCarteira() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO usuario (id, nome, email, senha_hash, handle, xp, nivel, streak, rating,
                             papel, status, criado_em, atualizado_em, versao)
        VALUES (?, 'Sacador concorrente', ?, '{bcrypt}$2a$10$naoUsadoNesteTeste', ?, 0, 1, 0, 0.0,
                'USUARIO', 'ATIVO', NOW(), NOW(), 0)
        """,
        id,
        "saque-conc-" + id + "@teste.dev",
        "q" + id.toString().substring(0, 10));

    UUID carteiraId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO carteira (id, usuario_id, saldo_brl, saldo_tokens, versao)"
            + " VALUES (?, ?, ?, 0, 0)",
        carteiraId,
        id,
        SALDO);
    jdbcTemplate.update(
        """
        INSERT INTO lancamento (id, carteira_id, sinal, motivo, valor_brl, valor_tokens,
                                chave_idempotencia, saldo_apos_brl, saldo_apos_tokens, criado_em)
        VALUES (?, ?, 'CREDITO', 'RECOMPENSA_MISSAO', ?, 0, ?, ?, 0, NOW())
        """,
        UUID.randomUUID(),
        carteiraId,
        SALDO,
        "saque-conc-abertura-" + carteiraId,
        SALDO);
    return id;
  }

  private BigDecimal saldoBrl(UUID usuarioId) {
    return jdbcTemplate.queryForObject(
        "SELECT saldo_brl FROM carteira WHERE usuario_id = ?", BigDecimal.class, usuarioId);
  }

  private void limpar(UUID usuario) {
    jdbcTemplate.update(
        "DELETE FROM lancamento WHERE carteira_id IN"
            + " (SELECT id FROM carteira WHERE usuario_id = ?)",
        usuario);
    jdbcTemplate.update("DELETE FROM auditoria WHERE ator_id = ?", usuario);
    jdbcTemplate.update("DELETE FROM carteira WHERE usuario_id = ?", usuario);
    jdbcTemplate.update("DELETE FROM usuario WHERE id = ?", usuario);
  }

  private String bearer(UUID usuarioId) {
    return "Bearer "
        + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", "USUARIO");
  }
}
