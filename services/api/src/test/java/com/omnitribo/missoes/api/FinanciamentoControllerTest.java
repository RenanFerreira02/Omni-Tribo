package com.omnitribo.missoes.api;

import static com.omnitribo.carteira.SuporteCarteira.assertLedgerReconcilia;
import static com.omnitribo.carteira.SuporteCarteira.tokensEmCirculacao;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
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
 * Financiamento de missão TRIBO e CONSERVAÇÃO da moeda.
 *
 * <p>A tese econômica da fase: sem sumidouro, concluir missão cunha token do nada e a economia é
 * fictícia. Com o pote, o token que o executor recebe é exatamente o token que um membro financiou
 * — a soma {@code SUM(carteira.saldo_tokens) + SUM(missao.pote_tokens)} é INVARIANTE ao longo de
 * todo o ciclo de vida da missão. É essa igualdade que os testes aqui verificam a cada etapa, e não
 * apenas no fim.
 */
@Import(JwtTestConfig.class)
class FinanciamentoControllerTest extends TesteIntegracaoMvcBase {

  private static final String MISSOES = "/api/v1/missoes";
  private static final long SALDO = 1000L;
  private static final long RECOMPENSA = 100L;

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  // Chamado direto, não pelo @Scheduled: app.agendamento.habilitado é false em teste de propósito,
  // para que nenhum job mude estado entre o arrange e o assert.
  @Autowired com.omnitribo.missoes.dominio.ExpiracaoMissoesService expiracaoMissoesService;

  private UUID tribo;
  private UUID outraTribo;
  private UUID criador;
  private UUID financiador;
  private UUID executor;
  private UUID forasteiro;
  private UUID missaoId;

  @BeforeEach
  void montarCenario() throws Exception {
    tribo = criarTribo("Financiadora");
    outraTribo = criarTribo("Alheia");
    criador = criarUsuarioComCarteira("criador", tribo, SALDO);
    financiador = criarUsuarioComCarteira("financiador", tribo, SALDO);
    executor = criarUsuarioComCarteira("executor", tribo, 0);
    forasteiro = criarUsuarioComCarteira("forasteiro", outraTribo, SALDO);
    missaoId = criarMissaoTriboEmRascunho();
  }

  @AfterEach
  void limpar() {
    jdbcTemplate.update("DELETE FROM outbox WHERE agregado_id = ?", missaoId);
    jdbcTemplate.update("DELETE FROM alerta WHERE missao_id = ?", missaoId);
    jdbcTemplate.update("DELETE FROM missao_evento WHERE missao_id = ?", missaoId);
    jdbcTemplate.update("DELETE FROM missao WHERE id = ?", missaoId);
    UUID[] usuarios = {criador, financiador, executor, forasteiro};
    for (UUID u : usuarios) {
      jdbcTemplate.update(
          "DELETE FROM lancamento WHERE carteira_id IN"
              + " (SELECT id FROM carteira WHERE usuario_id = ?)",
          u);
      jdbcTemplate.update("DELETE FROM auditoria WHERE ator_id = ?", u);
    }
    for (UUID u : usuarios) {
      jdbcTemplate.update("DELETE FROM carteira WHERE usuario_id = ?", u);
      jdbcTemplate.update("DELETE FROM usuario WHERE id = ?", u);
    }
    jdbcTemplate.update("DELETE FROM tribo WHERE id IN (?, ?)", tribo, outraTribo);
  }

  @Test
  void cicloCompleto_financiarPublicarConcluir_conservaOsTokens() throws Exception {
    long circulacaoInicial = tokensEmCirculacao(jdbcTemplate);

    // (1) Financiar: sai da carteira, entra no pote. Nada é criado nem destruído.
    mockMvc
        .perform(financiar(financiador, RECOMPENSA, "financiar-1"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.poteTokens").value(RECOMPENSA))
        .andExpect(jsonPath("$.saldoTokensRestante").value(SALDO - RECOMPENSA));

    assertThat(tokensEmCirculacao(jdbcTemplate))
        .as("financiar move token de lugar, não cria nem destrói")
        .isEqualTo(circulacaoInicial);

    // (2) Publicar agora é possível: o pote cobre a recompensa.
    mockMvc
        .perform(
            post(MISSOES + "/{id}/publicar", missaoId).header("Authorization", bearer(criador)))
        .andExpect(status().isOk());

    // (3) Executar até AGUARDANDO_CONFIRMACAO.
    mockMvc
        .perform(
            post(MISSOES + "/{id}/aceitar", missaoId).header("Authorization", bearer(executor)))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post(MISSOES + "/{id}/iniciar", missaoId).header("Authorization", bearer(executor)))
        .andExpect(status().isOk());
    jdbcTemplate.update(
        "UPDATE missao SET status = 'AGUARDANDO_CONFIRMACAO' WHERE id = ?", missaoId);

    // (4) Concluir: o executor é pago DO POTE, não com token cunhado.
    mockMvc
        .perform(
            post(MISSOES + "/{id}/confirmar", missaoId).header("Authorization", bearer(criador)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.poteTokens").value(0));

    assertThat(saldoTokens(executor)).as("executor recebeu a recompensa").isEqualTo(RECOMPENSA);
    assertThat(poteDaMissao()).as("pote esvaziado pelo pagamento").isZero();
    assertThat(tokensEmCirculacao(jdbcTemplate))
        .as("CONSERVAÇÃO: nenhum token foi cunhado no ciclo inteiro")
        .isEqualTo(circulacaoInicial);

    assertLedgerReconcilia(jdbcTemplate);
  }

  @Test
  void cancelarMissaoFinanciadaEstornaOPoteAoFinanciador() throws Exception {
    long circulacaoInicial = tokensEmCirculacao(jdbcTemplate);

    mockMvc
        .perform(financiar(financiador, RECOMPENSA, "financiar-cancelamento"))
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            post(MISSOES + "/{id}/publicar", missaoId).header("Authorization", bearer(criador)))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post(MISSOES + "/{id}/cancelar", missaoId)
                .header("Authorization", bearer(criador))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"motivo\":\"Não haverá mutirão\"}"))
        .andExpect(status().isOk());

    // Sem estorno, os tokens ficariam presos no pote de uma missão morta: a soma continuaria
    // fechando, mas uma parte dela estaria em custódia inalcançável — o mesmo, na prática, que
    // queimar dinheiro dos outros.
    assertThat(saldoTokens(financiador))
        .as("financiador recuperou o que pôs no pote")
        .isEqualTo(SALDO);
    assertThat(poteDaMissao()).as("pote zerado no estorno").isZero();
    assertThat(tokensEmCirculacao(jdbcTemplate)).isEqualTo(circulacaoInicial);
    assertLedgerReconcilia(jdbcTemplate);
  }

  @Test
  void expirarMissaoFinanciadaEstornaOPoteAoFinanciador() throws Exception {
    // Regressão do buraco mais grave da fase: o job de expiração NÃO passa por
    // MissaoService.aplicar,
    // então o estorno de lá era código morto para EXPIRADA. Como toda missão TRIBO publicada tem
    // pote > 0 (a publicação exige), bastava ninguém aceitar até a janela vencer para os tokens de
    // quem financiou sumirem — e a reconciliação continuaria dizendo integro=true, porque ledger e
    // projeção seguem batendo. A perda era invisível para o endpoint que existe para achá-la.
    long circulacaoInicial = tokensEmCirculacao(jdbcTemplate);

    mockMvc
        .perform(financiar(financiador, RECOMPENSA, "financiar-expiracao"))
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            post(MISSOES + "/{id}/publicar", missaoId).header("Authorization", bearer(criador)))
        .andExpect(status().isOk());

    // Vence a janela sem que ninguém aceite.
    jdbcTemplate.update(
        "UPDATE missao SET janela_fim = NOW() - INTERVAL '1 hour' WHERE id = ?", missaoId);

    // Chamado direto: app.agendamento.habilitado é false em teste, de propósito.
    expiracaoMissoesService.expirarLote(50);

    assertThat(statusDaMissao()).isEqualTo("EXPIRADA");
    assertThat(poteDaMissao()).as("pote zerado na expiração").isZero();
    assertThat(saldoTokens(financiador))
        .as("financiador recuperou o que pôs no pote de uma missão que expirou")
        .isEqualTo(SALDO);
    assertThat(tokensEmCirculacao(jdbcTemplate))
        .as("CONSERVAÇÃO: expirar não pode destruir token")
        .isEqualTo(circulacaoInicial);
    assertLedgerReconcilia(jdbcTemplate);
  }

  @Test
  void financiarAcimaDaRecompensaDa422() throws Exception {
    // A conclusão debita exatamente tokensRecompensa e CONCLUIDA é terminal: sobra no pote ficaria
    // presa para sempre. Recusar o excedente na entrada é mais honesto que estornar resíduo na
    // saída — o financiador descobre na hora que aquele token não é necessário.
    mockMvc
        .perform(financiar(financiador, RECOMPENSA + 1, "financiar-excedente"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("acima da")));

    assertThat(poteDaMissao()).isZero();
    assertThat(saldoTokens(financiador)).isEqualTo(SALDO);
  }

  @Test
  void cancelarRascunhoFinanciadoEstornaOPote() throws Exception {
    // É a saída que torna seguro financiar um rascunho. Sem a transição RASCUNHO --CANCELAR-->
    // CANCELADA, um rascunho co-financiado e abandonado prenderia os tokens para sempre: de
    // RASCUNHO só se saía por PUBLICAR, e o estorno só roda em CANCELADA/EXPIRADA.
    long circulacaoInicial = tokensEmCirculacao(jdbcTemplate);

    mockMvc
        .perform(financiar(financiador, RECOMPENSA, "financiar-rascunho"))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            post(MISSOES + "/{id}/cancelar", missaoId)
                .header("Authorization", bearer(criador))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"motivo\":\"Desisti antes de publicar\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELADA"));

    assertThat(saldoTokens(financiador))
        .as("co-financiador recuperou o que pôs num rascunho abandonado")
        .isEqualTo(SALDO);
    assertThat(poteDaMissao()).isZero();
    assertThat(tokensEmCirculacao(jdbcTemplate)).isEqualTo(circulacaoInicial);
    assertLedgerReconcilia(jdbcTemplate);
  }

  @Test
  void estornoComDoisFinanciadoresDevolveAParteDeCadaUm() throws Exception {
    // Exercita a agregação por carteira e a ordenação de lock de FinanciamentoCarteiraService, que
    // com um financiador só eram código morto na suíte.
    long circulacaoInicial = tokensEmCirculacao(jdbcTemplate);
    long saldoCriadorAntes = saldoTokens(criador);

    mockMvc.perform(financiar(financiador, 60, "dois-a")).andExpect(status().isCreated());
    mockMvc.perform(financiar(criador, 40, "dois-b")).andExpect(status().isCreated());
    assertThat(poteDaMissao()).isEqualTo(RECOMPENSA);

    mockMvc
        .perform(
            post(MISSOES + "/{id}/cancelar", missaoId)
                .header("Authorization", bearer(criador))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"motivo\":\"Cancelado com dois financiadores\"}"))
        .andExpect(status().isOk());

    assertThat(saldoTokens(financiador))
        .as("cada um recebe exatamente a sua parte")
        .isEqualTo(SALDO);
    assertThat(saldoTokens(criador)).isEqualTo(saldoCriadorAntes);
    assertThat(poteDaMissao()).isZero();
    assertThat(tokensEmCirculacao(jdbcTemplate)).isEqualTo(circulacaoInicial);

    Long estornos =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM lancamento WHERE missao_id = ? AND motivo = 'ESTORNO'",
            Long.class,
            missaoId);
    assertThat(estornos).as("um lançamento de estorno por carteira").isEqualTo(2L);
    assertLedgerReconcilia(jdbcTemplate);
  }

  @Test
  void mesmoFinanciadorEmDuasParcelasRecebeUmEstornoAgregado() throws Exception {
    // Exercita o `distinct` por carteira: duas parcelas do mesmo financiador têm de virar UM
    // estorno com a soma, não dois — e nem um só com a metade.
    mockMvc.perform(financiar(financiador, 30, "parcela-1")).andExpect(status().isCreated());
    mockMvc.perform(financiar(financiador, 20, "parcela-2")).andExpect(status().isCreated());

    mockMvc
        .perform(
            post(MISSOES + "/{id}/cancelar", missaoId)
                .header("Authorization", bearer(criador))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"motivo\":\"Cancelado\"}"))
        .andExpect(status().isOk());

    assertThat(saldoTokens(financiador)).as("as duas parcelas voltaram").isEqualTo(SALDO);

    Long estornos =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM lancamento WHERE missao_id = ? AND motivo = 'ESTORNO'",
            Long.class,
            missaoId);
    assertThat(estornos).as("uma carteira, um estorno agregado").isEqualTo(1L);
    assertLedgerReconcilia(jdbcTemplate);
  }

  @Test
  void doisFinanciamentosSimultaneosNaoEstouramOTetoDoPote() throws Exception {
    // O teto do pote é verificado sob o lock da missão. Dois membros pedindo 60 cada num pote que
    // precisa de 100: um passa, o outro é recusado — senão o pote passaria da recompensa e a sobra
    // ficaria presa.
    java.util.concurrent.CountDownLatch largada = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(2);

    java.util.concurrent.Future<Integer> a =
        pool.submit(financiarAoSinal(financiador, 60, "corrida-a", largada));
    java.util.concurrent.Future<Integer> b =
        pool.submit(financiarAoSinal(criador, 60, "corrida-b", largada));

    largada.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

    java.util.List<Integer> status = java.util.List.of(a.get(), b.get());
    assertThat(status.stream().filter(s -> s == 201).count()).isEqualTo(1L);
    assertThat(status.stream().filter(s -> s == 422).count()).isEqualTo(1L);
    assertThat(status.stream().filter(s -> s == 500).count()).isZero();

    assertThat(poteDaMissao()).as("o pote não pode passar da recompensa").isEqualTo(60L);
    assertLedgerReconcilia(jdbcTemplate);
  }

  @Test
  void membroDaTriboPodeCoFinanciarRascunhoAlheio() throws Exception {
    // Co-financiamento é o propósito da moeda comunitária: restringir o financiamento de rascunho
    // ao criador obrigaria uma pessoa só a bancar 100% da missão.
    mockMvc
        .perform(financiar(financiador, RECOMPENSA, "co-financiar"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.poteTokens").value(RECOMPENSA));

    assertThat(poteDaMissao()).isEqualTo(RECOMPENSA);
  }

  @Test
  void publicarSemPoteSuficienteDa422() throws Exception {
    // A guarda existe para fechar um beco sem saída: como a conclusão paga DO pote, publicar sem
    // cobertura criaria uma missão que chega em AGUARDANDO_CONFIRMACAO e nunca pode ser concluída.
    mockMvc
        .perform(
            post(MISSOES + "/{id}/publicar", missaoId).header("Authorization", bearer(criador)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("pote")));

    assertThat(statusDaMissao()).as("missão continua em rascunho").isEqualTo("RASCUNHO");
  }

  @Test
  void poteParcialAindaBloqueiaAPublicacao() throws Exception {
    mockMvc
        .perform(financiar(financiador, RECOMPENSA - 1, "financiar-parcial"))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            post(MISSOES + "/{id}/publicar", missaoId).header("Authorization", bearer(criador)))
        .andExpect(status().isUnprocessableEntity());

    assertThat(statusDaMissao()).isEqualTo("RASCUNHO");
  }

  @Test
  void financiadorDeOutraTriboDa422() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/tribos/{triboId}/financiamentos", tribo)
                .header("Authorization", bearer(forasteiro))
                .header("Idempotency-Key", "teste-forasteiro")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"missaoId\":\"" + missaoId + "\",\"tokens\":10}"))
        .andExpect(status().isUnprocessableEntity());

    assertThat(poteDaMissao()).isZero();
    assertThat(saldoTokens(forasteiro)).isEqualTo(SALDO);
  }

  @Test
  void financiamentoComSaldoInsuficienteDa422SemEfeito() throws Exception {
    mockMvc
        .perform(financiar(financiador, SALDO + 1, "financiar-sem-saldo"))
        .andExpect(status().isUnprocessableEntity());

    assertThat(poteDaMissao()).as("pote intacto").isZero();
    assertThat(saldoTokens(financiador)).as("carteira intacta").isEqualTo(SALDO);
    assertLedgerReconcilia(jdbcTemplate);
  }

  @Test
  void financiamentoRepetidoComMesmaChaveNaoDebitaNemCreditaDuasVezes() throws Exception {
    mockMvc.perform(financiar(financiador, 50, "financiar-replay")).andExpect(status().isCreated());
    mockMvc
        .perform(financiar(financiador, 50, "financiar-replay"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.replay").value(true))
        .andExpect(jsonPath("$.poteTokens").value(50));

    assertThat(poteDaMissao()).as("pote creditado uma vez só").isEqualTo(50L);
    assertThat(saldoTokens(financiador)).isEqualTo(SALDO - 50);
    assertLedgerReconcilia(jdbcTemplate);
  }

  // ─── Apoio ───────────────────────────────────────────────────────────────────────────────────

  private java.util.concurrent.Callable<Integer> financiarAoSinal(
      UUID quem, long tokens, String chave, java.util.concurrent.CountDownLatch largada) {
    return () -> {
      try {
        largada.await();
        return mockMvc
            .perform(financiar(quem, tokens, chave))
            .andReturn()
            .getResponse()
            .getStatus();
      } catch (Exception e) {
        return 500;
      }
    };
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder financiar(
      UUID quem, long tokens, String chave) {
    return post("/api/v1/tribos/{triboId}/financiamentos", tribo)
        .header("Authorization", bearer(quem))
        .header("Idempotency-Key", "teste-" + chave)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"missaoId\":\"" + missaoId + "\",\"tokens\":" + tokens + "}");
  }

  private UUID criarMissaoTriboEmRascunho() throws Exception {
    Instant inicio = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    String corpo =
        """
        {
          "categoria": "TRIBO",
          "titulo": "Mutirão de limpeza da praça",
          "descricao": "Missão comunitária paga com tokens financiados pela tribo.",
          "valorBrl": 0.00,
          "tokensRecompensa": %d,
          "xpRecompensa": 80,
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
            .formatted(RECOMPENSA, inicio, inicio.plus(2, ChronoUnit.DAYS));

    MvcResult criacao =
        mockMvc
            .perform(
                post(MISSOES)
                    .header("Authorization", bearer(criador))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(corpo))
            .andExpect(status().isCreated())
            .andReturn();

    return UUID.fromString(
        JSON.readTree(criacao.getResponse().getContentAsString()).get("id").asText());
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
    if (tokens > 0) {
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
    }
    return id;
  }

  private long saldoTokens(UUID usuarioId) {
    return jdbcTemplate.queryForObject(
        "SELECT saldo_tokens FROM carteira WHERE usuario_id = ?", Long.class, usuarioId);
  }

  private long poteDaMissao() {
    return jdbcTemplate.queryForObject(
        "SELECT pote_tokens FROM missao WHERE id = ?", Long.class, missaoId);
  }

  private String statusDaMissao() {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM missao WHERE id = ?", String.class, missaoId);
  }

  private String bearer(UUID usuarioId) {
    return "Bearer "
        + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", "USUARIO");
  }
}
