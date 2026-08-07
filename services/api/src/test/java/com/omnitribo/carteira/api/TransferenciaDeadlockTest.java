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
 * A prova da ordenação determinística de locks.
 *
 * <p>Duas threads transferem em sentidos OPOSTOS entre as MESMAS duas carteiras, ao mesmo tempo,
 * 100 rodadas seguidas. É o cenário canônico de deadlock: cada transação precisa das duas linhas, e
 * se cada uma as pedisse na ordem do seu próprio sentido (A→B pega A depois B; B→A pega B depois
 * A), cada uma seguraria uma e esperaria pela outra. O PostgreSQL detectaria o ciclo e mataria uma
 * com {@code 40P01} depois de {@code deadlock_timeout} — que sai como 500 para o usuário, de forma
 * intermitente.
 *
 * <p>{@code TransferenciaService.ordenarELocar} trava sempre do MENOR id de carteira para o maior,
 * independentemente do sentido da transferência. Com isso uma transação só pode esperar por quem
 * segura um id estritamente menor, a espera anda numa direção só e o ciclo é impossível por
 * construção.
 *
 * <p>100 rodadas, e não uma: deadlock é uma corrida, e uma rodada única passaria por sorte mesmo
 * com a ordenação errada. O que o número compra é a chance de o intercalamento ruim acontecer.
 *
 * <p>Cada rodada usa {@code Idempotency-Key} nova. Reaproveitar a chave transformaria as rodadas 2
 * a 100 em replays baratos que nunca chegam a pedir o segundo lock — o teste passaria sem testar
 * nada.
 */
@Import(JwtTestConfig.class)
class TransferenciaDeadlockTest extends TesteIntegracaoMvcBase {

  private static final Logger log = LoggerFactory.getLogger(TransferenciaDeadlockTest.class);

  private static final int RODADAS = 100;
  private static final long SALDO_INICIAL = 200L;
  private static final String BASE = "/api/v1/carteira/transferencias";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  @Test
  void transferenciasCruzadasSimultaneas_semDeadlockESaldosCorretos() throws Exception {
    UUID tribo = criarTribo();
    UUID alfa = criarUsuarioComCarteira("alfa", tribo, SALDO_INICIAL);
    UUID beta = criarUsuarioComCarteira("beta", tribo, SALDO_INICIAL);

    List<Integer> todosOsStatus = new ArrayList<>(RODADAS * 2);
    ExecutorService pool = Executors.newFixedThreadPool(2);

    for (int rodada = 0; rodada < RODADAS; rodada++) {
      CountDownLatch largada = new CountDownLatch(1);

      Future<Integer> idaFuture =
          pool.submit(transferirAoSinal(alfa, beta, "ida-" + rodada, largada));
      Future<Integer> voltaFuture =
          pool.submit(transferirAoSinal(beta, alfa, "volta-" + rodada, largada));

      // As duas threads já estão bloqueadas: soltar juntas é o que cria a sobreposição real.
      largada.countDown();

      todosOsStatus.add(idaFuture.get(30, TimeUnit.SECONDS));
      todosOsStatus.add(voltaFuture.get(30, TimeUnit.SECONDS));
    }

    pool.shutdown();
    assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

    log.info(
        "Transferências cruzadas: {} rodadas × 2 sentidos → 201: {} | 422: {} | 500: {}",
        RODADAS,
        contar(todosOsStatus, 201),
        contar(todosOsStatus, 422),
        contar(todosOsStatus, 500));

    assertThat(contar(todosOsStatus, 500))
        .as("um único 500 aqui é deadlock (40P01) — a ordenação de locks falhou")
        .isZero();
    assertThat(contar(todosOsStatus, 201))
        .as("toda transferência tem de completar")
        .isEqualTo(RODADAS * 2L);

    // Cada carteira enviou e recebeu exatamente RODADAS tokens: volta ao saldo inicial.
    assertThat(saldoTokens(alfa)).as("alfa recebeu o mesmo que enviou").isEqualTo(SALDO_INICIAL);
    assertThat(saldoTokens(beta)).as("beta recebeu o mesmo que enviou").isEqualTo(SALDO_INICIAL);

    // 2 lançamentos por transferência (perna de saída e de entrada) × 2 sentidos × RODADAS.
    Long lancamentos =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM lancamento
            WHERE carteira_id IN (SELECT id FROM carteira WHERE usuario_id IN (?, ?))
              AND motivo IN ('TRANSFERENCIA_ENVIADA', 'TRANSFERENCIA_RECEBIDA')
            """,
            Long.class,
            alfa,
            beta);
    assertThat(lancamentos)
        .as("cada transferência gera exatamente duas pernas no ledger")
        .isEqualTo(RODADAS * 4L);

    assertLedgerReconcilia(jdbcTemplate);

    limpar(tribo, alfa, beta);
  }

  // ─── Apoio ───────────────────────────────────────────────────────────────────────────────────

  private Callable<Integer> transferirAoSinal(
      UUID remetente, UUID destinatario, String sufixoChave, CountDownLatch largada) {
    return () -> {
      try {
        largada.await();
        MvcResult resultado =
            mockMvc
                .perform(
                    post(BASE)
                        .header("Authorization", bearer(remetente))
                        .header("Idempotency-Key", "deadlock-" + remetente + "-" + sufixoChave)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinatarioId\":\"" + destinatario + "\",\"tokens\":1}"))
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
        "Tribo Deadlock " + id.toString().substring(0, 8),
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

    // Lançamento de abertura: sem ele a carteira teria saldo sem ledger e a reconciliação — que é
    // a asserção final deste teste — acusaria divergência de partida, mascarando o que ela mede.
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

  /**
   * Duas passadas, e a ordem importa.
   *
   * <p>{@code lancamento.contraparte_carteira_id} tem FK para {@code carteira}, então uma
   * transferência de alfa para beta deixa uma linha NA carteira de alfa apontando para a de beta.
   * Apagar carteira por carteira dentro de um laço único viola a FK na primeira: os lançamentos da
   * segunda ainda referenciam a primeira. Todos os lançamentos das duas saem antes de qualquer
   * carteira.
   */
  private void limpar(UUID triboId, UUID... usuarios) {
    for (UUID usuario : usuarios) {
      jdbcTemplate.update(
          "DELETE FROM lancamento WHERE carteira_id IN"
              + " (SELECT id FROM carteira WHERE usuario_id = ?)",
          usuario);
      jdbcTemplate.update("DELETE FROM auditoria WHERE ator_id = ?", usuario);
    }
    for (UUID usuario : usuarios) {
      jdbcTemplate.update("DELETE FROM carteira WHERE usuario_id = ?", usuario);
      jdbcTemplate.update("DELETE FROM usuario WHERE id = ?", usuario);
    }
    jdbcTemplate.update("DELETE FROM tribo WHERE id = ?", triboId);
  }

  private String bearer(UUID usuarioId) {
    return "Bearer "
        + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", "USUARIO");
  }
}
