package com.omnitribo.carteira.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import com.omnitribo.carteira.SuporteCarteira;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Prova que a reconciliação CONSEGUE FALHAR.
 *
 * <p>Existe porque `assertLedgerReconcilia` é a asserção mais repetida da fase — fecha todo teste
 * de concorrência — e, sem esta classe, ninguém jamais tinha verificado que a consulta por trás
 * dela é capaz de produzir uma linha. Se o {@code WHERE} de {@code ReconciliacaoRepository} fosse
 * invertido, ou o {@code LEFT JOIN} virasse {@code INNER JOIN}, a consulta devolveria vazio sempre
 * e TODOS os testes de concorrência continuariam verdes provando muito menos do que aparentam.
 *
 * <p>Uma verificação de integridade que nunca foi vista acusando não é uma verificação — é uma
 * função que retorna vazio.
 */
@Import(JwtTestConfig.class)
class ReconciliacaoTest extends TesteIntegracaoMvcBase {

  private static final String BASE = "/api/v1/admin/carteiras/reconciliacao";
  private static final UUID ADMIN_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
  private static final UUID ALICE_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  private UUID usuario;
  private UUID carteiraId;

  @BeforeEach
  void montarCarteiraIntegra() {
    usuario = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO usuario (id, nome, email, senha_hash, handle, xp, nivel, streak, rating,
                             papel, status, criado_em, atualizado_em, versao)
        VALUES (?, 'Conciliado', ?, '{bcrypt}$2a$10$naoUsadoNesteTeste', ?, 0, 1, 0, 0.0,
                'USUARIO', 'ATIVO', NOW(), NOW(), 0)
        """,
        usuario,
        "recon-" + usuario + "@teste.dev",
        "r" + usuario.toString().substring(0, 10));

    carteiraId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO carteira (id, usuario_id, saldo_brl, saldo_tokens, versao)"
            + " VALUES (?, ?, 50.00, 10, 0)",
        carteiraId,
        usuario);
    jdbcTemplate.update(
        """
        INSERT INTO lancamento (id, carteira_id, sinal, motivo, valor_brl, valor_tokens,
                                chave_idempotencia, saldo_apos_brl, saldo_apos_tokens, criado_em)
        VALUES (?, ?, 'CREDITO', 'BONUS', 50.00, 10, ?, 50.00, 10, NOW())
        """,
        UUID.randomUUID(),
        carteiraId,
        "recon-abertura-" + carteiraId);
  }

  @AfterEach
  void limpar() {
    jdbcTemplate.update("DELETE FROM lancamento WHERE carteira_id = ?", carteiraId);
    jdbcTemplate.update("DELETE FROM carteira WHERE id = ?", carteiraId);
    jdbcTemplate.update("DELETE FROM usuario WHERE id = ?", usuario);
  }

  // ─── Autorização ────────────────────────────────────────────────────────────────────────────

  @Test
  void adminRecebeRelatorioIntegro() throws Exception {
    mockMvc
        .perform(get(BASE).header("Authorization", bearer(ADMIN_ID, "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.integro").value(true))
        .andExpect(jsonPath("$.divergencias").isEmpty())
        .andExpect(jsonPath("$.carteirasVerificadas").isNumber());
  }

  @Test
  void usuarioComumDa403() throws Exception {
    // A resposta expõe saldos de TODOS os usuários quando há divergência, e a consulta varre a
    // tabela `lancamento` inteira — aberta, seria vazamento e DoS barato ao mesmo tempo.
    mockMvc
        .perform(get(BASE).header("Authorization", bearer(ALICE_ID, "USUARIO")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.divergencias").doesNotExist());
  }

  @Test
  void semTokenDa401() throws Exception {
    mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
  }

  // ─── A consulta consegue acusar ─────────────────────────────────────────────────────────────

  @Test
  void saldoAdulteradoSemLancamentoCorrespondenteEhDetectado() throws Exception {
    // Simula o pior defeito possível: alguém escreveu saldo sem passar pelo ledger.
    jdbcTemplate.update(
        "UPDATE carteira SET saldo_tokens = saldo_tokens + 7 WHERE id = ?", carteiraId);

    mockMvc
        .perform(get(BASE).header("Authorization", bearer(ADMIN_ID, "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.integro").value(false))
        .andExpect(jsonPath("$.divergencias.length()").value(1))
        .andExpect(jsonPath("$.divergencias[0].carteiraId").value(carteiraId.toString()))
        .andExpect(jsonPath("$.divergencias[0].saldoTokensRegistrado").value(17))
        .andExpect(jsonPath("$.divergencias[0].saldoTokensLedger").value(10));
  }

  @Test
  void lancamentoSemProjecaoCorrespondenteEhDetectado() throws Exception {
    // O sentido oposto: ledger cresceu e a projeção não acompanhou.
    jdbcTemplate.update(
        """
        INSERT INTO lancamento (id, carteira_id, sinal, motivo, valor_brl, valor_tokens,
                                chave_idempotencia, saldo_apos_brl, saldo_apos_tokens, criado_em)
        VALUES (?, ?, 'CREDITO', 'BONUS', 5.00, 0, ?, 55.00, 10, NOW())
        """,
        UUID.randomUUID(),
        carteiraId,
        "recon-orfao-" + carteiraId);

    mockMvc
        .perform(get(BASE).header("Authorization", bearer(ADMIN_ID, "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.integro").value(false))
        .andExpect(jsonPath("$.divergencias[0].saldoBrlRegistrado").value(50.00))
        .andExpect(jsonPath("$.divergencias[0].saldoBrlLedger").value(55.00));
  }

  @Test
  void carteiraSemLancamentoNenhumNaoEhDivergencia() throws Exception {
    // Pega a regressão LEFT JOIN → INNER JOIN. Uma carteira zerada e sem ledger é o estado normal
    // de usuário recém-registrado: acusá-la encheria o relatório de falso positivo, e com INNER
    // JOIN ela sumiria do resultado — escondendo o caso de saldo positivo com ZERO lançamentos,
    // que é a corrupção mais grave que existe.
    UUID novoUsuario = UUID.randomUUID();
    UUID novaCarteira = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO usuario (id, nome, email, senha_hash, handle, xp, nivel, streak, rating,
                             papel, status, criado_em, atualizado_em, versao)
        VALUES (?, 'Novato', ?, '{bcrypt}$2a$10$naoUsadoNesteTeste', ?, 0, 1, 0, 0.0,
                'USUARIO', 'ATIVO', NOW(), NOW(), 0)
        """,
        novoUsuario,
        "novato-" + novoUsuario + "@teste.dev",
        "n" + novoUsuario.toString().substring(0, 10));
    jdbcTemplate.update(
        "INSERT INTO carteira (id, usuario_id, saldo_brl, saldo_tokens, versao)"
            + " VALUES (?, ?, 0.00, 0, 0)",
        novaCarteira,
        novoUsuario);

    try {
      mockMvc
          .perform(get(BASE).header("Authorization", bearer(ADMIN_ID, "ADMIN")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.integro").value(true));
    } finally {
      jdbcTemplate.update("DELETE FROM carteira WHERE id = ?", novaCarteira);
      jdbcTemplate.update("DELETE FROM usuario WHERE id = ?", novoUsuario);
    }
  }

  @Test
  void carteiraComSaldoESemLancamentoNenhumEhDetectada() throws Exception {
    // A corrupção mais grave possível, e exatamente a que um INNER JOIN esconderia.
    UUID fantasma = UUID.randomUUID();
    UUID carteiraFantasma = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO usuario (id, nome, email, senha_hash, handle, xp, nivel, streak, rating,
                             papel, status, criado_em, atualizado_em, versao)
        VALUES (?, 'Fantasma', ?, '{bcrypt}$2a$10$naoUsadoNesteTeste', ?, 0, 1, 0, 0.0,
                'USUARIO', 'ATIVO', NOW(), NOW(), 0)
        """,
        fantasma,
        "fantasma-" + fantasma + "@teste.dev",
        "f" + fantasma.toString().substring(0, 10));
    jdbcTemplate.update(
        "INSERT INTO carteira (id, usuario_id, saldo_brl, saldo_tokens, versao)"
            + " VALUES (?, ?, 999.00, 0, 0)",
        carteiraFantasma,
        fantasma);

    try {
      mockMvc
          .perform(get(BASE).header("Authorization", bearer(ADMIN_ID, "ADMIN")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.integro").value(false))
          .andExpect(
              jsonPath("$.divergencias[?(@.carteiraId=='" + carteiraFantasma + "')]").isNotEmpty());
    } finally {
      jdbcTemplate.update("DELETE FROM carteira WHERE id = ?", carteiraFantasma);
      jdbcTemplate.update("DELETE FROM usuario WHERE id = ?", fantasma);
    }
  }

  // ─── Meta-teste do helper ───────────────────────────────────────────────────────────────────

  @Test
  void assertLedgerReconciliaFalhaQuandoHaDivergencia() {
    // Sem isto, `SuporteCarteira.assertLedgerReconcilia` é uma cópia não validada da consulta de
    // produção, repetida em sete testes de concorrência. Este é o teste que garante que aquelas
    // sete asserções significam alguma coisa.
    assertThat(catchThrowableSemDivergencia()).as("estado íntegro passa").isNull();

    jdbcTemplate.update("UPDATE carteira SET saldo_brl = saldo_brl + 1 WHERE id = ?", carteiraId);

    assertThatThrownBy(() -> SuporteCarteira.assertLedgerReconcilia(jdbcTemplate))
        .as("estado corrompido reprova")
        .isInstanceOf(AssertionError.class);

    jdbcTemplate.update(
        "UPDATE carteira SET saldo_brl = ? WHERE id = ?", new BigDecimal("50.00"), carteiraId);
  }

  private Throwable catchThrowableSemDivergencia() {
    try {
      SuporteCarteira.assertLedgerReconcilia(jdbcTemplate);
      return null;
    } catch (AssertionError e) {
      return e;
    }
  }

  private String bearer(UUID usuarioId, String papel) {
    return "Bearer " + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", papel);
  }
}
