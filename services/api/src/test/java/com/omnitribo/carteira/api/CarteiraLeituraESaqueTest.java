package com.omnitribo.carteira.api;

import static com.omnitribo.carteira.SuporteCarteira.assertLedgerReconcilia;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Saldo, extrato e saque. */
@Import(JwtTestConfig.class)
class CarteiraLeituraESaqueTest extends TesteIntegracaoMvcBase {

  private static final String BASE = "/api/v1/carteira";
  private static final BigDecimal SALDO_BRL = new BigDecimal("100.00");

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  private UUID usuario;
  private UUID carteiraId;

  @BeforeEach
  void montarCenario() {
    usuario = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO usuario (id, nome, email, senha_hash, handle, xp, nivel, streak, rating,
                             papel, status, criado_em, atualizado_em, versao)
        VALUES (?, 'Sacador', ?, '{bcrypt}$2a$10$naoUsadoNesteTeste', ?, 0, 1, 0, 0.0,
                'USUARIO', 'ATIVO', NOW(), NOW(), 0)
        """,
        usuario,
        "sacador-" + usuario + "@teste.dev",
        "s" + usuario.toString().substring(0, 10));

    carteiraId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO carteira (id, usuario_id, saldo_brl, saldo_tokens, versao)"
            + " VALUES (?, ?, ?, 0, 0)",
        carteiraId,
        usuario,
        SALDO_BRL);
    jdbcTemplate.update(
        """
        INSERT INTO lancamento (id, carteira_id, sinal, motivo, valor_brl, valor_tokens,
                                chave_idempotencia, saldo_apos_brl, saldo_apos_tokens, criado_em)
        VALUES (?, ?, 'CREDITO', 'RECOMPENSA_MISSAO', ?, 0, ?, ?, 0, NOW() - INTERVAL '2 hours')
        """,
        UUID.randomUUID(),
        carteiraId,
        SALDO_BRL,
        "abertura-" + carteiraId,
        SALDO_BRL);
  }

  @AfterEach
  void limpar() {
    jdbcTemplate.update("DELETE FROM lancamento WHERE carteira_id = ?", carteiraId);
    jdbcTemplate.update("DELETE FROM auditoria WHERE ator_id = ?", usuario);
    jdbcTemplate.update("DELETE FROM carteira WHERE id = ?", carteiraId);
    jdbcTemplate.update("DELETE FROM usuario WHERE id = ?", usuario);
  }

  // ─── Saldo e extrato ────────────────────────────────────────────────────────────────────────

  @Test
  void saldoVemDoUsuarioDoToken() throws Exception {
    mockMvc
        .perform(get(BASE).header("Authorization", bearer(usuario)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.usuarioId").value(usuario.toString()))
        .andExpect(jsonPath("$.saldoBrl").value(100.00))
        .andExpect(jsonPath("$.saldoTokens").value(0));
  }

  @Test
  void extratoEhCronologicoDecrescenteENaoExpoeAChaveDeIdempotencia() throws Exception {
    // Um segundo lançamento mais recente, para que a ordem seja verificável de verdade.
    jdbcTemplate.update(
        """
        INSERT INTO lancamento (id, carteira_id, sinal, motivo, valor_brl, valor_tokens,
                                chave_idempotencia, saldo_apos_brl, saldo_apos_tokens, criado_em)
        VALUES (?, ?, 'CREDITO', 'BONUS', 5.00, 0, ?, 105.00, 0, NOW())
        """,
        UUID.randomUUID(),
        carteiraId,
        "recente-" + carteiraId);
    jdbcTemplate.update("UPDATE carteira SET saldo_brl = 105.00 WHERE id = ?", carteiraId);

    mockMvc
        .perform(get(BASE + "/lancamentos").header("Authorization", bearer(usuario)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.conteudo.length()").value(2))
        .andExpect(jsonPath("$.conteudo[0].motivo").value("BONUS"))
        .andExpect(jsonPath("$.conteudo[1].motivo").value("RECOMPENSA_MISSAO"))
        // Envelope estável de PaginaResponse — o mesmo contrato da listagem de missões.
        .andExpect(jsonPath("$.totalElementos").value(2))
        .andExpect(jsonPath("$.primeira").value(true))
        .andExpect(jsonPath("$.pageable").doesNotExist())
        // A chave é um sha256 do material que inclui a chave do cliente: devolvê-la permitiria
        // confirmar chaves alheias por comparação, e não serve para nada a quem lê um extrato.
        .andExpect(jsonPath("$.conteudo[0].chaveIdempotencia").doesNotExist());
  }

  @Test
  void extratoRespeitaPaginacao() throws Exception {
    mockMvc
        .perform(
            get(BASE + "/lancamentos")
                .param("pagina", "0")
                .param("tamanho", "1")
                .header("Authorization", bearer(usuario)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.conteudo.length()").value(1))
        .andExpect(jsonPath("$.tamanho").value(1));
  }

  @Test
  void tamanhoDePaginaAcimaDoMaximoDa400() throws Exception {
    mockMvc
        .perform(
            get(BASE + "/lancamentos")
                .param("tamanho", "500")
                .header("Authorization", bearer(usuario)))
        .andExpect(status().isBadRequest());
  }

  // ─── Saque ──────────────────────────────────────────────────────────────────────────────────

  @Test
  void saqueValidoDebitaEDevolveProtocolo() throws Exception {
    mockMvc
        .perform(saque("50.00", "saque-ok"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.protocolo").isNotEmpty())
        .andExpect(jsonPath("$.saldoBrlRestante").value(50.00))
        .andExpect(jsonPath("$.replay").value(false));

    assertThat(saldoBrl()).isEqualByComparingTo(new BigDecimal("50.00"));
    assertLedgerReconcilia(jdbcTemplate);
  }

  @Test
  void saqueAcimaDoSaldoDa422SemEfeito() throws Exception {
    mockMvc
        .perform(saque("500.00", "saque-sem-saldo"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("insuficiente")));

    assertThat(saldoBrl()).as("recusa não pode debitar").isEqualByComparingTo(SALDO_BRL);
    assertLedgerReconcilia(jdbcTemplate);
  }

  @Test
  void saqueAbaixoDoMinimoDa422() throws Exception {
    // app.carteira.saque-minimo-brl = 10.00 — abaixo disso a taxa do gateway inviabiliza.
    mockMvc
        .perform(saque("1.00", "saque-baixo"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("mínimo")));

    assertThat(saldoBrl()).isEqualByComparingTo(SALDO_BRL);
  }

  @Test
  void saqueRepetidoComMesmaChaveNaoDebitaDuasVezes() throws Exception {
    mockMvc.perform(saque("30.00", "saque-replay")).andExpect(status().isCreated());
    mockMvc
        .perform(saque("30.00", "saque-replay"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.replay").value(true));

    assertThat(saldoBrl())
        .as("dois POSTs com a mesma chave debitam uma vez só")
        .isEqualByComparingTo(new BigDecimal("70.00"));
    assertLedgerReconcilia(jdbcTemplate);
  }

  @Test
  void saqueComMaisDeDuasCasasDecimaisDa400() throws Exception {
    // Espelha numeric(12,2): sem a validação, o driver arredondaria em silêncio e o usuário veria
    // debitado um valor diferente do que pediu.
    mockMvc.perform(saque("10.999", "saque-precisao")).andExpect(status().isBadRequest());
  }

  // ─── Apoio ───────────────────────────────────────────────────────────────────────────────────

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder saque(
      String valor, String chave) {
    return post(BASE + "/saques")
        .header("Authorization", bearer(usuario))
        .header("Idempotency-Key", "teste-" + chave)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"valorBrl\":" + valor + "}");
  }

  private BigDecimal saldoBrl() {
    return jdbcTemplate.queryForObject(
        "SELECT saldo_brl FROM carteira WHERE id = ?", BigDecimal.class, carteiraId);
  }

  private String bearer(UUID usuarioId) {
    return "Bearer "
        + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", "USUARIO");
  }
}
