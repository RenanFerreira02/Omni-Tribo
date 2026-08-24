package com.omnitribo.carteira.api;

import static com.omnitribo.carteira.SuporteCarteira.assertLedgerReconcilia;
import static com.omnitribo.carteira.SuporteCarteira.tokensEmCirculacao;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Resgate de benefício: o SUMIDOURO do TOKEN.
 *
 * <p>O que este teste mede, e que nenhum outro mede: a circulação total <b>DIMINUI</b>. Todas as
 * outras operações de valor movem token de lugar — o financiamento tira da carteira e põe no pote,
 * a conclusão tira do pote e põe na carteira, e a soma fecha. O resgate queima, e é isso que
 * transforma a economia de estoque em ciclo. Ver ADR 0027.
 *
 * <p><b>A reconciliação continua verde durante a queima</b>, e a asserção está aqui de propósito: é
 * mais uma demonstração de que ledger×projeção nunca provou conservação. Cunhar escrevia os dois
 * lados; queimar também.
 */
@Import(JwtTestConfig.class)
@DisplayName("Resgate de benefício")
class ResgateBeneficioTest extends TesteIntegracaoMvcBase {

  private static final String RESGATES = "/api/v1/resgates";
  private static final String BENEFICIOS = "/api/v1/beneficios";

  /** Seed V900. */
  private static final UUID ADMIN = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

  /** Seed V906: Padaria Pão da Praça, Cidade Líder. */
  private static final UUID PADARIA = UUID.fromString("22222222-0000-0000-0000-000000000960");

  /** Seed V906: café coado, 15 tokens, BEM, ativo. */
  private static final UUID CAFE = UUID.fromString("33333333-0000-0000-0000-000000000960");

  /** Seed V906: panetone, INATIVO num parceiro ativo. */
  private static final UUID BENEFICIO_INATIVO =
      UUID.fromString("33333333-0000-0000-0000-000000000965");

  /** Seed V906: livro usado, ATIVO num parceiro DESLIGADO. */
  private static final UUID BENEFICIO_DE_PARCEIRO_INATIVO =
      UUID.fromString("33333333-0000-0000-0000-000000000966");

  /** Tribo Cidade Líder (V903), dona dos parceiros da V906. */
  private static final UUID TRIBO_CIDADE_LIDER =
      UUID.fromString("aaaaaaaa-0000-0000-0000-000000000901");

  /** Coordenada de referência da V903 — a padaria fica a ~200 m. */
  private static final String LAT = "-23.57260";

  private static final String LON = "-46.50630";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  private UUID usuario;

  @BeforeEach
  void criarUsuarioComSaldo() {
    usuario = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO usuario (id, nome, email, senha_hash, handle, tribo_id, xp, nivel, streak,
                             rating, papel, status, criado_em, atualizado_em, versao)
        VALUES (?, 'Resgatador', ?, '{bcrypt}$2a$10$naoUsado', ?, ?, 0, 1, 0, 0.0,
                'USUARIO', 'ATIVO', NOW(), NOW(), 0)
        """,
        usuario,
        usuario + "@teste.dev",
        "r" + usuario.toString().substring(0, 10),
        TRIBO_CIDADE_LIDER);

    UUID carteiraId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO carteira (id, usuario_id, saldo_brl, saldo_tokens, versao)"
            + " VALUES (?, ?, 0.00, 100, 1)",
        carteiraId,
        usuario);
    // Ledger coerente com o saldo: sem isto, assertLedgerReconcilia reprovaria de saída — e
    // reprovaria com razão, porque saldo sem origem é a corrupção mais grave possível.
    jdbcTemplate.update(
        """
        INSERT INTO lancamento (id, carteira_id, sinal, motivo, valor_brl, valor_tokens,
                                chave_idempotencia, saldo_apos_brl, saldo_apos_tokens, criado_em)
        VALUES (?, ?, 'CREDITO', 'BONUS', 0.00, 100, ?, 0.00, 100, NOW())
        """,
        UUID.randomUUID(),
        carteiraId,
        "seed-resgate-" + usuario);
  }

  @AfterEach
  void limpar() {
    jdbcTemplate.update("DELETE FROM auditoria WHERE ator_id = ?", usuario);
    jdbcTemplate.update("DELETE FROM resgate WHERE usuario_id = ?", usuario);
    jdbcTemplate.update(
        "DELETE FROM lancamento WHERE carteira_id IN (SELECT id FROM carteira WHERE usuario_id = ?)",
        usuario);
    jdbcTemplate.update("DELETE FROM carteira WHERE usuario_id = ?", usuario);
    jdbcTemplate.update("DELETE FROM usuario WHERE id = ?", usuario);
    // Benefícios que os testes de cadastro criaram.
    jdbcTemplate.update("DELETE FROM beneficio WHERE titulo LIKE 'Teste %'");
  }

  // ─── O sumidouro ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("resgatar QUEIMA: a circulação cai exatamente o custo")
  void resgateQueimaToken() throws Exception {
    long circulacaoAntes = tokensEmCirculacao(jdbcTemplate);

    String corpo =
        mockMvc
            .perform(resgatar(CAFE, "resgate-queima-0001"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.custoTokens").value(15))
            .andExpect(jsonPath("$.saldoTokensRestante").value(85))
            .andExpect(jsonPath("$.status").value("PENDENTE"))
            .andExpect(jsonPath("$.replay").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();

    String codigo = (String) JSON.readValue(corpo, Map.class).get("codigoRetirada");
    assertThat(codigo)
        .as("código de retirada, 8 caracteres sem ambiguidade visual")
        .matches("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{8}");

    assertThat(tokensEmCirculacao(jdbcTemplate))
        .as("SUMIDOURO: a circulação DIMINUI — nenhuma outra operação do sistema faz isso")
        .isEqualTo(circulacaoAntes - 15);

    // O lançamento não credita ninguém: é o que separa queima de transferência.
    Map<String, Object> lancamento =
        jdbcTemplate.queryForMap(
            "SELECT sinal, motivo, valor_tokens, missao_id, contraparte_carteira_id"
                + " FROM lancamento WHERE motivo = 'RESGATE' AND carteira_id ="
                + " (SELECT id FROM carteira WHERE usuario_id = ?)",
            usuario);
    assertThat(lancamento.get("sinal")).isEqualTo("DEBITO");
    assertThat(lancamento.get("valor_tokens")).isEqualTo(15L);
    assertThat(lancamento.get("missao_id")).as("resgate não pertence a missão nenhuma").isNull();
    assertThat(lancamento.get("contraparte_carteira_id"))
        .as("sem contraparte: ninguém recebeu o que foi debitado")
        .isNull();

    assertLedgerReconcilia(jdbcTemplate);
  }

  @Test
  @DisplayName("replay da mesma Idempotency-Key não queima duas vezes")
  void replayNaoQueimaDuasVezes() throws Exception {
    long circulacaoAntes = tokensEmCirculacao(jdbcTemplate);

    String primeiro =
        mockMvc
            .perform(resgatar(CAFE, "resgate-replay-0001"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.replay").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();

    mockMvc
        .perform(resgatar(CAFE, "resgate-replay-0001"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.replay").value(true))
        .andExpect(jsonPath("$.saldoTokensRestante").value(85))
        .andExpect(
            jsonPath("$.codigoRetirada")
                .value(JSON.readValue(primeiro, Map.class).get("codigoRetirada")));

    assertThat(tokensEmCirculacao(jdbcTemplate))
        .as("uma queima, não duas")
        .isEqualTo(circulacaoAntes - 15);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resgate WHERE usuario_id = ?", Long.class, usuario))
        .isEqualTo(1L);

    assertLedgerReconcilia(jdbcTemplate);
  }

  /**
   * Resgate é operação de valor, então exige teste multi-thread — e num sumidouro o prejuízo da
   * corrida é do USUÁRIO: queimar duas vezes tira dele um saldo que ele gastou uma vez só.
   */
  @Test
  @DisplayName("dez resgates simultâneos com a mesma chave queimam uma vez só")
  void resgateConcorrenteQueimaUmaVez() throws Exception {
    long circulacaoAntes = tokensEmCirculacao(jdbcTemplate);

    int threads = 10;
    CountDownLatch largada = new CountDownLatch(1);
    CountDownLatch fim = new CountDownLatch(threads);
    AtomicInteger criados = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(threads);

    for (int i = 0; i < threads; i++) {
      pool.submit(
          () -> {
            try {
              largada.await();
              var resposta =
                  mockMvc.perform(resgatar(CAFE, "resgate-concorrente")).andReturn().getResponse();
              if (resposta.getStatus() == 201
                  && resposta.getContentAsString().contains("\"replay\":false")) {
                criados.incrementAndGet();
              }
            } catch (Exception e) {
              // Engolido: a asserção que importa é a circulação final.
            } finally {
              fim.countDown();
            }
          });
    }

    largada.countDown();
    assertThat(fim.await(30, TimeUnit.SECONDS)).isTrue();
    pool.shutdownNow();

    assertThat(tokensEmCirculacao(jdbcTemplate))
        .as("a corrida não pode queimar duas vezes")
        .isEqualTo(circulacaoAntes - 15);
    assertThat(criados.get()).as("exatamente um resgate foi criado").isEqualTo(1);

    assertLedgerReconcilia(jdbcTemplate);
  }

  @Test
  @DisplayName("saldo insuficiente é 422 com type do catálogo, sem efeito colateral")
  void saldoInsuficienteEh422() throws Exception {
    jdbcTemplate.update("UPDATE carteira SET saldo_tokens = 5 WHERE usuario_id = ?", usuario);
    long circulacaoAntes = tokensEmCirculacao(jdbcTemplate);

    mockMvc
        .perform(resgatar(CAFE, "resgate-sem-saldo-0001"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type").value("https://omnitribo.dev/problemas/regra-negocio-violada"));

    assertThat(tokensEmCirculacao(jdbcTemplate))
        .as("422 sai sem escrever nada")
        .isEqualTo(circulacaoAntes);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resgate WHERE usuario_id = ?", Long.class, usuario))
        .isZero();
  }

  @Test
  @DisplayName("benefício inativo e benefício de parceiro desligado não são resgatáveis")
  void beneficioIndisponivelEh422() throws Exception {
    mockMvc
        .perform(resgatar(BENEFICIO_INATIVO, "resgate-inativo-0001"))
        .andExpect(status().isUnprocessableEntity());

    // A combinação que passa despercebida: o benefício está ativo, quem saiu foi o parceiro.
    mockMvc
        .perform(resgatar(BENEFICIO_DE_PARCEIRO_INATIVO, "resgate-parceiro-off-0001"))
        .andExpect(status().isUnprocessableEntity());
  }

  // ─── Catálogo ───────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("catálogo por proximidade traz distância derivada e ordena pela mais perto")
  void catalogoPorProximidade() throws Exception {
    mockMvc
        .perform(
            get(BENEFICIOS)
                .header("Authorization", bearer(usuario))
                .param("lat", LAT)
                .param("lon", LON)
                .param("raioMetros", "3000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.conteudo[0].distanciaM").isNumber())
        .andExpect(jsonPath("$.conteudo[0].parceiroId").value(PADARIA.toString()));
  }

  @Test
  @DisplayName("catálogo por tribo não traz distância, e esconde inativos")
  void catalogoPorTribo() throws Exception {
    String corpo =
        mockMvc
            .perform(
                get(BENEFICIOS)
                    .header("Authorization", bearer(usuario))
                    .param("triboId", TRIBO_CIDADE_LIDER.toString())
                    .param("tamanho", "50"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.conteudo[0].distanciaM").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(corpo)
        .as("benefício inativo não aparece")
        .doesNotContain(BENEFICIO_INATIVO.toString());
    assertThat(corpo)
        .as("benefício de parceiro desligado não aparece")
        .doesNotContain(BENEFICIO_DE_PARCEIRO_INATIVO.toString());
  }

  @Test
  @DisplayName("proximidade e tribo juntos é 422 — os recortes são exclusivos")
  void filtrosCombinadosEh422() throws Exception {
    mockMvc
        .perform(
            get(BENEFICIOS)
                .header("Authorization", bearer(usuario))
                .param("lat", LAT)
                .param("lon", LON)
                .param("raioMetros", "3000")
                .param("triboId", TRIBO_CIDADE_LIDER.toString()))
        .andExpect(status().isUnprocessableEntity());
  }

  // ─── Administração ──────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("baixa do parceiro é PENDENTE → UTILIZADO, e é idempotente")
  void baixaEhIdempotente() throws Exception {
    String corpo =
        mockMvc
            .perform(resgatar(CAFE, "resgate-baixa-0001"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String resgateId = (String) JSON.readValue(corpo, Map.class).get("id");

    for (int i = 0; i < 2; i++) {
      mockMvc
          .perform(
              patch("/api/v1/admin/resgates/{id}", resgateId)
                  .header("Authorization", bearer(ADMIN)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("UTILIZADO"))
          .andExpect(jsonPath("$.utilizadoEm").isNotEmpty());
    }
  }

  @Test
  @DisplayName("usuário comum não dá baixa nem cadastra benefício")
  void usuarioComumEh403() throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/admin/resgates/{id}", UUID.randomUUID())
                .header("Authorization", bearer(usuario)))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            post("/api/v1/admin/beneficios")
                .header("Authorization", bearer(usuario))
                .contentType(MediaType.APPLICATION_JSON)
                .content(cadastro("Teste café", "Um café.", 10)))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("benefício anunciado em reais é reprovado na borda")
  void beneficioEmReaisEhReprovado() throws Exception {
    // ADR 0009 §6: preço em moeda corrente publica uma cotação token→real implícita.
    mockMvc
        .perform(
            post("/api/v1/admin/beneficios")
                .header("Authorization", bearer(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(cadastro("Teste R$ 10 de desconto", "Desconto na compra.", 10)))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            post("/api/v1/admin/beneficios")
                .header("Authorization", bearer(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(cadastro("Teste desconto", "Vale dez reais na sua compra.", 10)))
        .andExpect(status().isBadRequest());

    // "realmente" NÃO pode ser reprovado: a fronteira de palavra existe para isso.
    mockMvc
        .perform(
            post("/api/v1/admin/beneficios")
                .header("Authorization", bearer(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(cadastro("Teste pão", "Um pão realmente quentinho.", 10)))
        .andExpect(status().isCreated());
  }

  // ─── Auxiliares ─────────────────────────────────────────────────────────────────────────────

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder resgatar(
      UUID beneficioId, String chave) {
    return post(RESGATES)
        .header("Authorization", bearer(usuario))
        .header("Idempotency-Key", chave)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"beneficioId\":\"" + beneficioId + "\"}");
  }

  private static String cadastro(String titulo, String descricao, long custo) {
    return """
        {"parceiroId":"%s","titulo":"%s","descricao":"%s","custoTokens":%d,"tipo":"BEM"}
        """
        .formatted(PADARIA, titulo, descricao, custo);
  }

  private String bearer(UUID usuarioId) {
    return "Bearer "
        + JwtTestConfig.gerarTokenValido(
            usuarioId, usuarioId + "@teste.dev", usuarioId.equals(ADMIN) ? "ADMIN" : "USUARIO");
  }
}
