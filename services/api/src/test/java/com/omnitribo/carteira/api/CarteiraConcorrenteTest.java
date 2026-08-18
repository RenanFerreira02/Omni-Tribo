package com.omnitribo.carteira.api;

import static com.omnitribo.carteira.SuporteCarteira.assertLedgerReconcilia;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
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
 * 50 threads creditando e debitando a MESMA carteira ao mesmo tempo.
 *
 * <p>É o teste do lost update. Metade das threads transfere PARA a carteira central e metade
 * transfere DELA para fora, tudo simultâneo. Sem {@code SELECT ... FOR UPDATE}, duas transações
 * leriam o mesmo saldo, cada uma escreveria o seu resultado, e uma das operações sumiria — o saldo
 * final não bateria com a soma do ledger.
 *
 * <p>A asserção que fecha o caso é {@code assertLedgerReconcilia}: ela compara a projeção {@code
 * carteira.saldo_*} com {@code SUM(lancamento)} de TODA carteira. Um lost update quebra exatamente
 * essa igualdade, mesmo que todos os HTTP tenham respondido 201.
 */
@Import(JwtTestConfig.class)
class CarteiraConcorrenteTest extends TesteIntegracaoMvcBase {

  private static final Logger log = LoggerFactory.getLogger(CarteiraConcorrenteTest.class);

  private static final int THREADS = 50;
  private static final long SALDO_CENTRAL = 500L;
  private static final long SALDO_PAR = 100L;
  private static final String BASE = "/api/v1/carteira/transferencias";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  @Test
  void cinquentaThreadsCreditandoEDebitando_saldoFinalIgualASomaDoLedger() throws Exception {
    UUID tribo = criarTribo();
    UUID central = criarUsuarioComCarteira("central", tribo, SALDO_CENTRAL);

    List<UUID> pares = new ArrayList<>(THREADS);
    for (int i = 0; i < THREADS; i++) {
      pares.add(criarUsuarioComCarteira("par" + i, tribo, SALDO_PAR));
    }

    CountDownLatch largada = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    List<Future<Integer>> respostas = new ArrayList<>(THREADS);

    for (int i = 0; i < THREADS; i++) {
      UUID par = pares.get(i);
      // Índice par credita a central; índice ímpar debita. Todas as 50 threads disputam a MESMA
      // linha de carteira, que é o que o teste quer estressar.
      boolean credita = i % 2 == 0;
      UUID remetente = credita ? par : central;
      UUID destinatario = credita ? central : par;
      respostas.add(pool.submit(transferirAoSinal(remetente, destinatario, "conc-" + i, largada)));
    }

    largada.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();

    List<Integer> status = new ArrayList<>(THREADS);
    for (Future<Integer> resposta : respostas) {
      status.add(resposta.get());
    }

    log.info(
        "Carteira sob concorrência: {} threads → 201: {} | 422: {} | 500: {}",
        THREADS,
        contar(status, 201),
        contar(status, 422),
        contar(status, 500));

    assertThat(contar(status, 500)).as("nenhuma exceção de infraestrutura pode vazar").isZero();
    assertThat(contar(status, 201))
        .as("toda transferência tem de completar — saldo e teto comportam todas")
        .isEqualTo((long) THREADS);

    // 25 créditos de 10 e 25 débitos de 10 se anulam.
    assertThat(saldoTokens(central))
        .as("créditos e débitos se anulam; nenhuma operação pode ter sumido")
        .isEqualTo(SALDO_CENTRAL);

    // A prova de que não houve lost update: se uma escrita tivesse sobrescrito outra, a projeção
    // divergiria da soma do ledger mesmo com todos os HTTP em 201.
    assertLedgerReconcilia(jdbcTemplate);

    limpar(tribo, central, pares);
  }

  // ─── Apoio ───────────────────────────────────────────────────────────────────────────────────

  private Callable<Integer> transferirAoSinal(
      UUID remetente, UUID destinatario, String chave, CountDownLatch largada) {
    return () -> {
      try {
        largada.await();
        MvcResult resultado =
            mockMvc
                .perform(
                    post(BASE)
                        .header("Authorization", bearer(remetente))
                        .header("Idempotency-Key", "carteira-conc-" + chave)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinatarioId\":\"" + destinatario + "\",\"tokens\":10}"))
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

  private UUID criarTribo() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO tribo (id, nome, bairro, criada_em) VALUES (?, ?, ?, NOW())",
        id,
        "Tribo Concorrencia " + id.toString().substring(0, 8),
        "Bairro de Teste");
    return id;
  }

  private UUID criarUsuarioComCarteira(String prefixo, UUID triboId, long tokens) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO usuario (id, nome, email, senha_hash, handle, tribo_id, xp, nivel, streak,
                             rating, papel, status, criado_em, atualizado_em, versao)
        VALUES (?, ?, ?, '{bcrypt}$2a$10$naoUsadoNesteTeste', ?, ?, 0, 1, 0, 0.0,
                'USUARIO', 'ATIVO', NOW(), NOW(), 0)
        """,
        id,
        prefixo,
        prefixo + "-" + id + "@teste.dev",
        prefixo.charAt(0) + id.toString().substring(0, 10),
        triboId);

    UUID carteiraId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO carteira (id, usuario_id, saldo_brl, saldo_tokens, versao)"
            + " VALUES (?, ?, 0.00, ?, 0)",
        carteiraId,
        id,
        tokens);
    jdbcTemplate.update(
        """
        INSERT INTO lancamento (id, carteira_id, sinal, motivo, valor_brl, valor_tokens,
                                chave_idempotencia, saldo_apos_brl, saldo_apos_tokens, criado_em)
        VALUES (?, ?, 'CREDITO', 'BONUS', 0.00, ?, ?, 0.00, ?, NOW())
        """,
        UUID.randomUUID(),
        carteiraId,
        tokens,
        "abertura-" + carteiraId,
        tokens);
    return id;
  }

  private long saldoTokens(UUID usuarioId) {
    return jdbcTemplate.queryForObject(
        "SELECT saldo_tokens FROM carteira WHERE usuario_id = ?", Long.class, usuarioId);
  }

  /** Lançamentos de TODOS antes de qualquer carteira: contraparte_carteira_id tem FK. */
  private void limpar(UUID triboId, UUID central, List<UUID> pares) {
    List<UUID> todos = new ArrayList<>(pares);
    todos.add(central);
    for (UUID u : todos) {
      jdbcTemplate.update(
          "DELETE FROM lancamento WHERE carteira_id IN"
              + " (SELECT id FROM carteira WHERE usuario_id = ?)",
          u);
      jdbcTemplate.update("DELETE FROM auditoria WHERE ator_id = ?", u);
    }
    for (UUID u : todos) {
      jdbcTemplate.update("DELETE FROM carteira WHERE usuario_id = ?", u);
      jdbcTemplate.update("DELETE FROM usuario WHERE id = ?", u);
    }
    jdbcTemplate.update("DELETE FROM tribo WHERE id = ?", triboId);
  }

  private String bearer(UUID usuarioId) {
    return "Bearer "
        + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", "USUARIO");
  }
}
