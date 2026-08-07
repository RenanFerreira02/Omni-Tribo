package com.omnitribo.carteira.api;

import static com.omnitribo.carteira.SuporteCarteira.assertLedgerReconcilia;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regras de negócio da transferência P2P.
 *
 * <p>Todas as recusas são 422, e o ponto de cada teste não é só o código: é que a recusa aconteça
 * SEM EFEITO COLATERAL. Um 422 que já debitou é pior que um 500 — o usuário vê erro e perde saldo.
 */
@Import(JwtTestConfig.class)
class TransferenciaControllerTest extends TesteIntegracaoMvcBase {

  private static final String BASE = "/api/v1/carteira/transferencias";
  private static final long SALDO = 300L;

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  private UUID triboA;
  private UUID triboB;
  private UUID alfa;
  private UUID beta;
  private UUID forasteiro;

  @BeforeEach
  void montarCenario() {
    triboA = criarTribo("A");
    triboB = criarTribo("B");
    alfa = criarUsuarioComCarteira("alfa", triboA, SALDO);
    beta = criarUsuarioComCarteira("beta", triboA, SALDO);
    forasteiro = criarUsuarioComCarteira("forasteiro", triboB, SALDO);
  }

  @AfterEach
  void limpar() {
    for (UUID u : new UUID[] {alfa, beta, forasteiro}) {
      jdbcTemplate.update(
          "DELETE FROM lancamento WHERE carteira_id IN"
              + " (SELECT id FROM carteira WHERE usuario_id = ?)",
          u);
      jdbcTemplate.update("DELETE FROM auditoria WHERE ator_id = ?", u);
    }
    for (UUID u : new UUID[] {alfa, beta, forasteiro}) {
      jdbcTemplate.update("DELETE FROM carteira WHERE usuario_id = ?", u);
      jdbcTemplate.update("DELETE FROM usuario WHERE id = ?", u);
    }
    jdbcTemplate.update("DELETE FROM tribo WHERE id IN (?, ?)", triboA, triboB);
  }

  @Test
  void transferenciaEntreMembrosDaMesmaTriboFunciona() throws Exception {
    mockMvc
        .perform(requisicao(alfa, beta, 50, "ok-mesma-tribo"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.saldoTokensRemetente").value(SALDO - 50))
        .andExpect(jsonPath("$.replay").value(false))
        .andExpect(jsonPath("$.lancamentoEntradaId").isNotEmpty());

    assertThat(saldoTokens(alfa)).isEqualTo(SALDO - 50);
    assertThat(saldoTokens(beta)).isEqualTo(SALDO + 50);
    assertLedgerReconcilia(jdbcTemplate);
  }

  @Test
  void transferenciaParaOutraTriboDa422SemEfeito() throws Exception {
    mockMvc
        .perform(requisicao(alfa, forasteiro, 10, "outra-tribo"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("mesma tribo")));

    assertThat(saldoTokens(alfa)).as("nada saiu do remetente").isEqualTo(SALDO);
    assertThat(saldoTokens(forasteiro)).as("nada entrou no destinatário").isEqualTo(SALDO);
    assertThat(contarLancamentos(alfa)).as("a recusa não gravou lançamento").isEqualTo(1L);
  }

  @Test
  void saldoInsuficienteDa422SemEfeito() throws Exception {
    mockMvc
        .perform(requisicao(alfa, beta, SALDO + 1, "sem-saldo"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("insuficiente")));

    assertThat(saldoTokens(alfa)).isEqualTo(SALDO);
    assertThat(saldoTokens(beta)).isEqualTo(SALDO);
    assertThat(contarLancamentos(alfa)).as("só o lançamento de abertura").isEqualTo(1L);
    assertLedgerReconcilia(jdbcTemplate);
  }

  @Test
  void acimaDoTetoPorTransacaoDa422() throws Exception {
    // app.carteira.transferencia-teto-por-transacao = 500. O saldo é 300, então um valor acima do
    // teto também estaria acima do saldo — por isso a asserção é sobre a MENSAGEM, para garantir
    // que o teto recusou primeiro e o teste não passa pelo motivo errado.
    mockMvc
        .perform(requisicao(alfa, beta, 501, "acima-do-teto"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("por transação")));

    assertThat(saldoTokens(alfa)).isEqualTo(SALDO);
  }

  @Test
  void acimaDoTetoPorJanelaDa422DepoisDeVariasTransferenciasValidas() throws Exception {
    // app.carteira.transferencia-teto-por-janela = 2000 em 24h. Cada transferência abaixo do teto
    // por transação; o que fecha o cerco é a SOMA. Sem o teto por janela, quem controlasse a conta
    // esvaziaria a carteira fatiando o valor.
    jdbcTemplate.update("UPDATE carteira SET saldo_tokens = 5000 WHERE usuario_id = ?", alfa);
    jdbcTemplate.update(
        """
        INSERT INTO lancamento (id, carteira_id, sinal, motivo, valor_brl, valor_tokens,
                                chave_idempotencia, saldo_apos_brl, saldo_apos_tokens, criado_em)
        VALUES (?, (SELECT id FROM carteira WHERE usuario_id = ?), 'CREDITO', 'BONUS',
                0.00, 4700, ?, 0.00, 5000, NOW())
        """,
        UUID.randomUUID(),
        alfa,
        "topup-" + alfa);

    for (int i = 0; i < 4; i++) {
      mockMvc.perform(requisicao(alfa, beta, 500, "janela-" + i)).andExpect(status().isCreated());
    }

    // Somados: 2000. O próximo token já estoura.
    mockMvc
        .perform(requisicao(alfa, beta, 1, "janela-estouro"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("janela")));

    assertThat(saldoTokens(alfa)).as("o estouro não debitou").isEqualTo(5000L - 2000L);
    assertLedgerReconcilia(jdbcTemplate);
  }

  @Test
  void transferenciaParaSiMesmoDa422() throws Exception {
    // Sem esta guarda, o remetente e o destinatário seriam a MESMA linha e o serviço tentaria
    // travá-la duas vezes, além de creditar e debitar a mesma carteira — um no-op caro que ainda
    // consumiria uma chave de idempotência.
    mockMvc
        .perform(requisicao(alfa, alfa, 10, "auto-transferencia"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("própria carteira")));

    assertThat(saldoTokens(alfa)).isEqualTo(SALDO);
  }

  @Test
  void mesmaChaveDeIdempotenciaNaoTransfereDuasVezes() throws Exception {
    mockMvc.perform(requisicao(alfa, beta, 30, "replay-igual")).andExpect(status().isCreated());

    mockMvc
        .perform(requisicao(alfa, beta, 30, "replay-igual"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.replay").value(true));

    assertThat(saldoTokens(alfa)).as("debitado uma vez só").isEqualTo(SALDO - 30);
    assertThat(saldoTokens(beta)).as("creditado uma vez só").isEqualTo(SALDO + 30);
    assertLedgerReconcilia(jdbcTemplate);
  }

  @Test
  void retryDaMesmaChaveComValorDiferenteNaoCriaSegundaTransferencia() throws Exception {
    mockMvc.perform(requisicao(alfa, beta, 30, "chave-fixa")).andExpect(status().isCreated());

    // Valor e destinatário ficam FORA do material da chave de propósito: retry é replay da operação
    // original, não uma nova. Sem isso, reenviar a mesma chave com valor maior criaria uma segunda
    // transferência que o cliente acreditaria ser a mesma.
    mockMvc
        .perform(requisicao(alfa, beta, 200, "chave-fixa"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.replay").value(true));

    assertThat(saldoTokens(alfa)).as("o valor do retry é ignorado").isEqualTo(SALDO - 30);
    assertLedgerReconcilia(jdbcTemplate);
  }

  @Test
  void chaveDeIdempotenciaCurtaDemaisDa400ENao500() throws Exception {
    // Regressão: os controllers com header validado são anotados com @Validated, e nesse caso o
    // Spring desliga a validação de método embutida — a violação passa a vir do proxy AOP como
    // ConstraintViolationException. Sem handler dedicado isso virava 500 com log.error, e qualquer
    // cliente autenticado fabricava incidente falso mandando uma chave de 3 caracteres.
    mockMvc
        .perform(
            post(BASE)
                .header("Authorization", bearer(alfa))
                .header("Idempotency-Key", "abc")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"destinatarioId\":\"" + beta + "\",\"tokens\":10}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors").isNotEmpty());

    assertThat(saldoTokens(alfa)).isEqualTo(SALDO);
  }

  @Test
  void semHeaderDeIdempotenciaDa400() throws Exception {
    mockMvc
        .perform(
            post(BASE)
                .header("Authorization", bearer(alfa))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"destinatarioId\":\"" + beta + "\",\"tokens\":10}"))
        .andExpect(status().isBadRequest());
  }

  // ─── Apoio ───────────────────────────────────────────────────────────────────────────────────

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder requisicao(
      UUID remetente, UUID destinatario, long tokens, String chave) {
    return post(BASE)
        .header("Authorization", bearer(remetente))
        .header("Idempotency-Key", "teste-" + chave)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"destinatarioId\":\"" + destinatario + "\",\"tokens\":" + tokens + "}");
  }

  private UUID criarTribo(String nome) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO tribo (id, nome, bairro, criada_em) VALUES (?, ?, ?, NOW())",
        id,
        "Tribo " + nome + " " + id.toString().substring(0, 8),
        "Bairro " + nome);
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
    // Lançamento de abertura para que a reconciliação parta de um estado íntegro.
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

  private long contarLancamentos(UUID usuarioId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM lancamento WHERE carteira_id IN"
            + " (SELECT id FROM carteira WHERE usuario_id = ?)",
        Long.class,
        usuarioId);
  }

  private String bearer(UUID usuarioId) {
    return "Bearer "
        + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", "USUARIO");
  }
}
