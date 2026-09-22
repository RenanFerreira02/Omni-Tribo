package com.omnitribo.missoes.api;

import static com.omnitribo.carteira.SuporteCarteira.assertLedgerReconcilia;
import static com.omnitribo.carteira.SuporteCarteira.tokensEmCirculacao;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.junit.jupiter.api.DisplayName;
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

  /**
   * Recompensa da missão do cenário, CAPTURADA da criação.
   *
   * <p>Era uma constante de 100 até o ADR 0009, quando a recompensa passou a ser derivada pela
   * CalculadoraDeRecompensa. Os casos de fronteira deste teste — financiar recompensa + 1, ou
   * recompensa - 1 para checar pote parcial — continuam válidos porque derivam deste valor, e não
   * de um número escrito à mão que quebraria a cada recalibração.
   */
  private long recompensa;

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  // Chamado direto, não pelo @Scheduled: app.agendamento.habilitado é false em teste de propósito,
  // para que nenhum job mude estado entre o arrange e o assert.
  @Autowired com.omnitribo.missoes.infra.ExpiracaoMissoesJob expiracaoMissoesJob;

  private UUID tribo;
  private UUID outraTribo;
  private UUID criador;
  private UUID financiador;
  private UUID executor;
  private UUID forasteiro;
  private UUID missaoId;

  /**
   * Missão AJUDA do bloco do ADR 0025, quando o teste cria uma. Nula nos demais.
   *
   * <p>Rastreada num campo para que {@link #limpar()} a apague: o contêiner é singleton para a JVM
   * inteira e não é truncado entre classes, então uma missão órfã aqui vaza para MigracaoTest.
   */
  private UUID missaoAjudaId;

  private long recompensaAjuda;

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
    if (missaoAjudaId != null) {
      jdbcTemplate.update("DELETE FROM outbox WHERE agregado_id = ?", missaoAjudaId);
      jdbcTemplate.update("DELETE FROM alerta WHERE missao_id = ?", missaoAjudaId);
      jdbcTemplate.update("DELETE FROM missao_evento WHERE missao_id = ?", missaoAjudaId);
      jdbcTemplate.update("DELETE FROM lancamento WHERE missao_id = ?", missaoAjudaId);
      jdbcTemplate.update("DELETE FROM missao WHERE id = ?", missaoAjudaId);
      missaoAjudaId = null;
    }
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
        .perform(financiar(financiador, recompensa, "financiar-1"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.poteTokens").value(recompensa))
        .andExpect(jsonPath("$.saldoTokensRestante").value(SALDO - recompensa));

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

    assertThat(saldoTokens(executor)).as("executor recebeu a recompensa").isEqualTo(recompensa);
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
        .perform(financiar(financiador, recompensa, "financiar-cancelamento"))
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
        .perform(financiar(financiador, recompensa, "financiar-expiracao"))
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            post(MISSOES + "/{id}/publicar", missaoId).header("Authorization", bearer(criador)))
        .andExpect(status().isOk());

    // Vence a janela sem que ninguém aceite.
    jdbcTemplate.update(
        "UPDATE missao SET janela_fim = NOW() - INTERVAL '1 hour' WHERE id = ?", missaoId);

    // Chamado direto: app.agendamento.habilitado é false em teste, de propósito.
    expiracaoMissoesJob.varrer(50, 5000);

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
        .perform(financiar(financiador, recompensa + 1, "financiar-excedente"))
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
        .perform(financiar(financiador, recompensa, "financiar-rascunho"))
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

    // Frações DERIVADAS da recompensa, e não 60+40 absolutos: elas precisam somar exatamente o
    // pote, e o valor total agora vem da calculadora. A divisão desigual é proposital — o estorno
    // tem de devolver a parte de cada um, não a média.
    long parteA = recompensa / 2;
    long parteB = recompensa - parteA;
    mockMvc.perform(financiar(financiador, parteA, "dois-a")).andExpect(status().isCreated());
    mockMvc.perform(financiar(criador, parteB, "dois-b")).andExpect(status().isCreated());
    assertThat(poteDaMissao()).isEqualTo(recompensa);

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
    long parcela1 = recompensa / 3;
    long parcela2 = recompensa / 4;
    mockMvc.perform(financiar(financiador, parcela1, "parcela-1")).andExpect(status().isCreated());
    mockMvc.perform(financiar(financiador, parcela2, "parcela-2")).andExpect(status().isCreated());

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

    // Cada um cabe sozinho no pote; os dois juntos estouram. É o que torna a corrida decidível:
    // exatamente um 201 e um 422, e nunca um pote acima da recompensa.
    long quaseTudo = recompensa - (recompensa / 4);
    java.util.concurrent.Future<Integer> a =
        pool.submit(financiarAoSinal(financiador, quaseTudo, "corrida-a", largada));
    java.util.concurrent.Future<Integer> b =
        pool.submit(financiarAoSinal(criador, quaseTudo, "corrida-b", largada));

    largada.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

    java.util.List<Integer> status = java.util.List.of(a.get(), b.get());
    assertThat(status.stream().filter(s -> s == 201).count()).isEqualTo(1L);
    assertThat(status.stream().filter(s -> s == 422).count()).isEqualTo(1L);
    assertThat(status.stream().filter(s -> s == 500).count()).isZero();

    assertThat(poteDaMissao()).as("o pote não pode passar da recompensa").isEqualTo(quaseTudo);
    assertLedgerReconcilia(jdbcTemplate);
  }

  @Test
  void membroDaTriboPodeCoFinanciarRascunhoAlheio() throws Exception {
    // Co-financiamento é o propósito da moeda comunitária: restringir o financiamento de rascunho
    // ao criador obrigaria uma pessoa só a bancar 100% da missão.
    mockMvc
        .perform(financiar(financiador, recompensa, "co-financiar"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.poteTokens").value(recompensa));

    assertThat(poteDaMissao()).isEqualTo(recompensa);
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
        .perform(financiar(financiador, recompensa - 1, "financiar-parcial"))
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
    mockMvc
        .perform(financiar(financiador, recompensa / 2, "financiar-replay"))
        .andExpect(status().isCreated());
    mockMvc
        .perform(financiar(financiador, recompensa / 2, "financiar-replay"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.replay").value(true))
        .andExpect(jsonPath("$.poteTokens").value(recompensa / 2));

    assertThat(poteDaMissao()).as("pote creditado uma vez só").isEqualTo(recompensa / 2);
    assertThat(saldoTokens(financiador)).isEqualTo(SALDO - recompensa / 2);
    assertLedgerReconcilia(jdbcTemplate);
  }

  /**
   * Retry de um financiamento que COMPLETOU o pote também devolve replay — e este é o caso que o
   * teste acima não pegava.
   *
   * <p>Com {@code recompensa / 2}, o pote termina na metade e o teto nunca dispara; o defeito
   * ficava invisível. Financiando a recompensa INTEIRA, o pote fica cheio na primeira chamada, e o
   * retry caía em {@code validarTeto} — {@code pote + tokens > recompensa} — respondendo <b>422</b>
   * em vez do replay. O cliente recebia erro para uma operação que já tinha dado certo, sem nenhuma
   * forma de distinguir isso de uma falha real.
   *
   * <p>A causa era de ORDEM: a sondagem de idempotência acontecia depois das validações de estado.
   * Agora é autorização → lock → sonda → valida → escreve, como em saque e transferência.
   */
  @Test
  void retryDeFinanciamentoQueCompletouOPoteDevolveReplayENao422() throws Exception {
    mockMvc
        .perform(financiar(financiador, recompensa, "financiar-pote-cheio"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.poteTokens").value(recompensa));

    mockMvc
        .perform(financiar(financiador, recompensa, "financiar-pote-cheio"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.replay").value(true))
        .andExpect(jsonPath("$.poteTokens").value(recompensa));

    assertThat(poteDaMissao()).as("pote creditado uma vez só").isEqualTo(recompensa);
    assertThat(saldoTokens(financiador)).isEqualTo(SALDO - recompensa);
    assertLedgerReconcilia(jdbcTemplate);
  }

  // ─── Becos sem saída (A4) ────────────────────────────────────────────────────────────────────

  /**
   * Executor abandona em EM_ANDAMENTO: a varredura expira e DEVOLVE o pote aos financiadores.
   *
   * <p>Antes, {@code EM_ANDAMENTO} tinha uma saída só — {@code CHECKIN} — e nenhum ator, nem ADMIN,
   * conseguia tirar a missão de lá. O pote ficava em custódia PARA SEMPRE, e a reconciliação
   * continuava respondendo íntegro: quem quebra é a conservação, que é outra invariante.
   */
  @Test
  void execucaoAbandonadaExpiraEEstornaOPote() throws Exception {
    long circulacaoInicial = tokensEmCirculacao(jdbcTemplate);
    levarAteEmAndamento();

    // Recua o marco para além do prazo de execução (48h). É `estado_desde` que a varredura lê —
    // janela_fim é o prazo da OFERTA e não diz nada sobre abandono depois do aceite.
    jdbcTemplate.update(
        "UPDATE missao SET estado_desde = now() - INTERVAL '72 hours' WHERE id = ?", missaoId);

    assertThat(expiracaoMissoesJob.varrer(50, 5000).expiradas()).isEqualTo(1);

    assertThat(statusDaMissao()).isEqualTo("EXPIRADA");
    assertThat(poteDaMissao()).as("pote devolvido, não preso").isZero();
    assertThat(saldoTokens(financiador)).as("financiador recuperou os tokens").isEqualTo(SALDO);
    assertThat(saldoTokens(executor)).as("sem check-in, sem pagamento").isZero();
    assertThat(tokensEmCirculacao(jdbcTemplate)).isEqualTo(circulacaoInicial);
    assertLedgerReconcilia(jdbcTemplate);
  }

  /**
   * Criador some depois do check-in: a varredura CONCLUI e PAGA o executor.
   *
   * <p>É a decisão de produto documentada em {@code EventoMissao.EXPIRAR_CONFIRMACAO}. Expirar
   * estornando puniria quem executou por uma omissão do outro lado, e o check-in geolocalizado
   * validado no servidor é a evidência que o sistema aceita como prova em todo outro caminho.
   *
   * <p>Passa por CONCLUIDA, então a regra "só CONCLUIDA credita" continua intacta e o pagamento
   * reusa o único caminho de crédito que existe.
   */
  @Test
  void confirmacaoOmitidaConcluiEPagaOExecutor() throws Exception {
    long circulacaoInicial = tokensEmCirculacao(jdbcTemplate);
    levarAteEmAndamento();
    jdbcTemplate.update(
        "UPDATE missao SET status = 'AGUARDANDO_CONFIRMACAO', estado_desde = now()"
            + " - INTERVAL '96 hours' WHERE id = ?",
        missaoId);

    assertThat(expiracaoMissoesJob.varrer(50, 5000).expiradas()).isEqualTo(1);

    assertThat(statusDaMissao()).isEqualTo("CONCLUIDA");
    assertThat(saldoTokens(executor)).as("executor recebeu do pote").isEqualTo(recompensa);
    assertThat(poteDaMissao()).as("pote consumido pelo pagamento").isZero();
    assertThat(tokensEmCirculacao(jdbcTemplate))
        .as("CONSERVAÇÃO: pagou do pote, não cunhou")
        .isEqualTo(circulacaoInicial);
    assertLedgerReconcilia(jdbcTemplate);
  }

  /** ADMIN destrava manualmente: CANCELADA com estorno, e a justificativa vai para a trilha. */
  @Test
  void adminDestravaMissaoParadaEEstornaOPote() throws Exception {
    levarAteEmAndamento();

    mockMvc
        .perform(
            post(MISSOES + "/{id}/destravar", missaoId)
                .header("Authorization", bearerAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"justificativa\":\"Executor avisou por telefone que não concluirá.\"}"))
        .andExpect(status().isOk());

    assertThat(statusDaMissao()).isEqualTo("CANCELADA");
    assertThat(saldoTokens(financiador)).isEqualTo(SALDO);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM missao_evento WHERE missao_id = ?"
                    + " AND tipo = 'DESTRAVADA_POR_ADMIN'",
                Long.class,
                missaoId))
        .isEqualTo(1L);
    assertLedgerReconcilia(jdbcTemplate);
  }

  /** Não-ADMIN não destrava — nem o criador, que é dono da missão. */
  @Test
  void destravarExigeAdmin() throws Exception {
    levarAteEmAndamento();

    mockMvc
        .perform(
            post(MISSOES + "/{id}/destravar", missaoId)
                .header("Authorization", bearer(criador))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"justificativa\":\"Quero cancelar do meu jeito.\"}"))
        .andExpect(status().isForbidden());

    assertThat(statusDaMissao()).isEqualTo("EM_ANDAMENTO");
  }

  // ─── Apoio ───────────────────────────────────────────────────────────────────────────────────

  /** Financia, publica, aceita e inicia — o cenário comum dos testes de beco sem saída. */
  private void levarAteEmAndamento() throws Exception {
    mockMvc
        .perform(financiar(financiador, recompensa, "financiar-travada-" + UUID.randomUUID()))
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            post(MISSOES + "/{id}/publicar", missaoId).header("Authorization", bearer(criador)))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post(MISSOES + "/{id}/aceitar", missaoId).header("Authorization", bearer(executor)))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post(MISSOES + "/{id}/iniciar", missaoId).header("Authorization", bearer(executor)))
        .andExpect(status().isOk());
  }

  private String statusDaMissao() {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM missao WHERE id = ?", String.class, missaoId);
  }

  /**
   * Admin do seed (V900). Precisa ser um usuário REAL: o filtro consulta a conta a cada request.
   */

  // ─── AJUDA paga do pote como TRIBO (ADR 0025) ───────────────────────────────────────────────

  /**
   * A regra que AJUDA passou a seguir, e o beco sem saída que ela fecha.
   *
   * <p>Antes do ADR 0025, AJUDA publicava sem pote e CUNHAVA a recompensa na conclusão. Agora ela é
   * `FontePote.COMUNIDADE` como TRIBO, então a guarda de publicação vale: sem pote cobrindo a
   * recompensa, a missão chegaria a AGUARDANDO_CONFIRMACAO e o `/confirmar` falharia com 422 para
   * sempre.
   */
  @Test
  @DisplayName("AJUDA sem pote não publica — 422, e o pote continua zerado")
  void publicarAjudaSemPoteDa422() throws Exception {
    missaoAjudaId = criarMissaoAjudaEmRascunho();
    long circulacaoInicial = tokensEmCirculacao(jdbcTemplate);

    mockMvc
        .perform(
            post(MISSOES + "/{id}/publicar", missaoAjudaId)
                .header("Authorization", bearer(criador)))
        .andExpect(status().isUnprocessableEntity())
        // O `type` era o 422 genérico e passou a ser um APERTADO, não relaxado: `pote-insuficiente`
        // (ADR 0035). A reação de UI é própria — a tela não pede para corrigir o pedido e tentar de
        // novo, ela oferece financiar ou editar para só XP —, e é isso que o ADR 0010 define como
        // critério de granularidade. As extensões existem para o app montar "faltam N" sem ler
        // número de dentro do `detail`.
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/pote-insuficiente"))
        .andExpect(jsonPath("$.poteTokens").value(0))
        .andExpect(jsonPath("$.recompensaTokens").value(greaterThan(0)));

    assertThat(poteDe(missaoAjudaId)).as("recusa sem efeito colateral").isZero();
    assertThat(statusDe(missaoAjudaId)).as("continua em RASCUNHO").isEqualTo("RASCUNHO");
    assertThat(tokensEmCirculacao(jdbcTemplate)).isEqualTo(circulacaoInicial);
    assertLedgerReconcilia(jdbcTemplate);
  }

  /**
   * O ciclo inteiro de uma AJUDA financiada por OUTRO membro.
   *
   * <p>Quem financia é o `financiador`, não o `criador` — é o que mantém o ADR 0009 ("quem cria a
   * missão NÃO paga") valendo depois da mudança. Em TRIBO já era assim; AJUDA passou a seguir a
   * mesma regra, e este teste é o que prova que a premissa não foi violada de lado.
   */
  @Test
  @DisplayName("AJUDA financiada por outro membro conserva a oferta no ciclo inteiro")
  void cicloAjudaFinanciadaConserva() throws Exception {
    missaoAjudaId = criarMissaoAjudaEmRascunho();
    long circulacaoInicial = tokensEmCirculacao(jdbcTemplate);
    long saldoCriadorAntes = saldoTokens(criador);

    mockMvc
        .perform(financiarMissao(financiador, missaoAjudaId, recompensaAjuda, "ajuda-ciclo"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.poteTokens").value(recompensaAjuda));

    assertThat(tokensEmCirculacao(jdbcTemplate))
        .as("financiar move token da carteira para o pote — não cria nem destrói")
        .isEqualTo(circulacaoInicial);

    mockMvc
        .perform(
            post(MISSOES + "/{id}/publicar", missaoAjudaId)
                .header("Authorization", bearer(criador)))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post(MISSOES + "/{id}/aceitar", missaoAjudaId)
                .header("Authorization", bearer(executor)))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post(MISSOES + "/{id}/iniciar", missaoAjudaId)
                .header("Authorization", bearer(executor)))
        .andExpect(status().isOk());
    jdbcTemplate.update(
        "UPDATE missao SET status = 'AGUARDANDO_CONFIRMACAO' WHERE id = ?", missaoAjudaId);

    mockMvc
        .perform(
            post(MISSOES + "/{id}/confirmar", missaoAjudaId)
                .header("Authorization", bearer(criador)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.poteTokens").value(0));

    assertThat(saldoTokens(executor))
        .as("executor pago DO POTE, não com token cunhado")
        .isEqualTo(recompensaAjuda);
    assertThat(saldoTokens(criador))
        .as("ADR 0009: quem cria a missão NÃO paga — o pote saiu do financiador")
        .isEqualTo(saldoCriadorAntes);
    assertThat(tokensEmCirculacao(jdbcTemplate))
        .as("CONSERVAÇÃO: Δ = 0 no ciclo inteiro de uma AJUDA")
        .isEqualTo(circulacaoInicial);

    assertLedgerReconcilia(jdbcTemplate);
  }

  @Test
  @DisplayName("cancelar AJUDA financiada estorna ao financiador")
  void cancelarAjudaEstorna() throws Exception {
    missaoAjudaId = criarMissaoAjudaEmRascunho();
    long circulacaoInicial = tokensEmCirculacao(jdbcTemplate);
    long saldoFinanciadorAntes = saldoTokens(financiador);

    mockMvc
        .perform(financiarMissao(financiador, missaoAjudaId, recompensaAjuda, "ajuda-cancelar"))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            post(MISSOES + "/{id}/cancelar", missaoAjudaId)
                .header("Authorization", bearer(criador))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"motivo\":\"Resolvi sozinho\"}"))
        .andExpect(status().isOk());

    assertThat(saldoTokens(financiador))
        .as("token de quem financiou volta inteiro")
        .isEqualTo(saldoFinanciadorAntes);
    assertThat(poteDe(missaoAjudaId)).as("pote zerado, não preso na missão morta").isZero();
    assertThat(tokensEmCirculacao(jdbcTemplate)).isEqualTo(circulacaoInicial);
    assertLedgerReconcilia(jdbcTemplate);
  }

  /**
   * O SEGUNDO ponto de estorno, que é o que o CLAUDE.md avisa que costuma ser esquecido.
   *
   * <p>`ExpiracaoMissoesService.expirarUma` é o ÚNICO caminho para EXPIRADA e NÃO passa por
   * `MissaoService.aplicar`. Os dois pontos chaveiam por `poteTokens > 0` e não por categoria,
   * então AJUDA já estava coberta — mas até agora nenhuma AJUDA tinha pote para estornar, então a
   * cobertura era teórica. Esta é a primeira vez que ela é exercitada.
   *
   * <p>Se o estorno faltasse aqui, o token ficaria preso numa missão morta e
   * `assertLedgerReconcilia` continuaria PASSANDO — ledger e projeção seguem batendo. Quem acusa é
   * a conservação, e é por isso que as duas asserções estão juntas.
   */
  @Test
  @DisplayName("expirar AJUDA financiada estorna pelo segundo ponto de chamada")
  void expirarAjudaEstorna() throws Exception {
    missaoAjudaId = criarMissaoAjudaEmRascunho();
    long circulacaoInicial = tokensEmCirculacao(jdbcTemplate);
    long saldoFinanciadorAntes = saldoTokens(financiador);

    mockMvc
        .perform(financiarMissao(financiador, missaoAjudaId, recompensaAjuda, "ajuda-expirar"))
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            post(MISSOES + "/{id}/publicar", missaoAjudaId)
                .header("Authorization", bearer(criador)))
        .andExpect(status().isOk());

    // Vence a janela de oferta e roda a varredura direto, sem @Scheduled.
    jdbcTemplate.update(
        "UPDATE missao SET janela_fim = NOW() - INTERVAL '1 hour' WHERE id = ?", missaoAjudaId);
    expiracaoMissoesJob.varrer(200, 5000);

    assertThat(statusDe(missaoAjudaId)).isEqualTo("EXPIRADA");
    assertThat(saldoTokens(financiador))
        .as("o segundo ponto de estorno devolveu ao financiador")
        .isEqualTo(saldoFinanciadorAntes);
    assertThat(poteDe(missaoAjudaId)).isZero();
    assertThat(tokensEmCirculacao(jdbcTemplate))
        .as("CONSERVAÇÃO: expirar não pode fazer token sumir")
        .isEqualTo(circulacaoInicial);

    assertLedgerReconcilia(jdbcTemplate);
  }

  private String bearerAdmin() {
    return "Bearer "
        + com.omnitribo.JwtTestConfig.gerarTokenValido(
            UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001"),
            "admin@omnitribo.dev",
            "ADMIN");
  }

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
          "complexidade": "MEDIA",
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
                post(MISSOES)
                    .header("Authorization", bearer(criador))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(corpo))
            .andExpect(status().isCreated())
            .andReturn();

    var corpoCriada = JSON.readTree(criacao.getResponse().getContentAsString());
    recompensa = corpoCriada.get("tokensRecompensa").asLong();
    return UUID.fromString(corpoCriada.get("id").asText());
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      financiarMissao(UUID quem, UUID missao, long tokens, String chave) {
    return post("/api/v1/tribos/{triboId}/financiamentos", tribo)
        .header("Authorization", bearer(quem))
        .header("Idempotency-Key", "teste-" + chave)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"missaoId\":\"" + missao + "\",\"tokens\":" + tokens + "}");
  }

  /** AJUDA declara a complexidade (não move objeto), ao contrário de ENTREGA e COLETA. */
  // ─── ADR 0035: missão comunitária que recompensa só em XP ────────────────────────────────────

  /**
   * O caso que motivou o ADR 0035, e o INVERSO exato de {@link #publicarAjudaSemPoteDa422()}.
   *
   * <p>Aquele teste continua verde e continua certo: AJUDA que promete token não publica sem pote.
   * Este diz a outra metade — AJUDA que não promete token nenhum publica na hora, sem financiador,
   * e sem que nada na conservação se mova. Os dois juntos são o contrato inteiro.
   */
  @Test
  @DisplayName("AJUDA só-XP publica sem pote, e a circulação não se move")
  void publicarAjudaSemTokenVaiAoArNaHora() throws Exception {
    long circulacaoInicial = tokensEmCirculacao(jdbcTemplate);
    missaoAjudaId = criarMissaoSemToken("AJUDA");

    assertThat(recompensaAjuda).as("recompensa em token é zerada na criação").isZero();
    assertThat(xpDe(missaoAjudaId))
        .as("XP é preservado: o esforço continua reconhecido, só a moeda é abrida mão")
        .isGreaterThan(0);
    assertThat(fonteDe(missaoAjudaId)).isEqualTo("SEM_TOKEN");

    mockMvc
        .perform(
            post(MISSOES + "/{id}/publicar", missaoAjudaId)
                .header("Authorization", bearer(criador)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ABERTA"))
        .andExpect(jsonPath("$.tokensRecompensa").value(0))
        .andExpect(jsonPath("$.fontePote").value("SEM_TOKEN"));

    assertThat(tokensEmCirculacao(jdbcTemplate)).isEqualTo(circulacaoInicial);
    assertLedgerReconcilia(jdbcTemplate);
  }

  /** TRIBO tem a mesma saída que AJUDA — a escolha é do criador, não da categoria. */
  @Test
  @DisplayName("TRIBO só-XP também publica sem pote")
  void publicarTriboSemTokenVaiAoArNaHora() throws Exception {
    missaoAjudaId = criarMissaoSemToken("TRIBO");

    mockMvc
        .perform(
            post(MISSOES + "/{id}/publicar", missaoAjudaId)
                .header("Authorization", bearer(criador)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ABERTA"));
  }

  /**
   * O ciclo inteiro de uma missão só-XP: conclui, paga XP, e NÃO escreve lançamento nenhum.
   *
   * <p>A ausência do lançamento é a assertion principal, não um detalhe. O compact constructor de
   * {@code Movimento} recusa mover 0 BRL e 0 token, então um crédito aqui seria 500 — e o caminho
   * que evita isso é justamente o que remove a missão do ledger. Δ na circulação é zero por não
   * participar da invariante, não por compensação.
   */
  @Test
  @DisplayName("Concluir missão só-XP paga XP, não escreve no ledger e não move a circulação")
  void concluirMissaoSemTokenNaoEscreveNoLedger() throws Exception {
    long circulacaoInicial = tokensEmCirculacao(jdbcTemplate);
    missaoAjudaId = criarMissaoSemToken("AJUDA");
    int xpDaMissao = xpDe(missaoAjudaId);
    int xpAntes = xpDoUsuario(executor);

    levarAteAguardandoConfirmacao(missaoAjudaId);

    mockMvc
        .perform(
            post(MISSOES + "/{id}/confirmar", missaoAjudaId)
                .header("Authorization", bearer(criador)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONCLUIDA"));

    assertThat(xpDoUsuario(executor))
        .as("o executor foi pago na moeda que a missão prometeu: reputação")
        .isEqualTo(xpAntes + xpDaMissao);
    assertThat(saldoTokens(executor)).as("nenhum token creditado").isZero();
    assertThat(lancamentosDaMissao(missaoAjudaId))
        .as("nenhum lançamento: 0 BRL e 0 token não é movimento, é uma chave gasta à toa")
        .isZero();
    assertThat(tokensEmCirculacao(jdbcTemplate)).isEqualTo(circulacaoInicial);
    assertLedgerReconcilia(jdbcTemplate);
  }

  /**
   * A regressão que a remoção do lançamento cria, e que só um teste pega.
   *
   * <p>A sondagem de replay da conclusão é feita PELO LANÇAMENTO. Sem lançamento ela é cega, e um
   * retry de {@code POST /confirmar} — que é a MESMA operação, de quem só perdeu a resposta na rede
   * — cairia em 409 "esta operação não é permitida no estado atual". Para a missão só-XP a
   * idempotência passa a ser por ESTADO, e é isto que este teste trava.
   */
  @Test
  @DisplayName("Retry de confirmar numa missão só-XP já concluída devolve 200, não 409")
  void confirmarDuasVezesMissaoSemTokenEhIdempotente() throws Exception {
    missaoAjudaId = criarMissaoSemToken("AJUDA");
    int xpAntes = xpDoUsuario(executor);
    int xpDaMissao = xpDe(missaoAjudaId);
    levarAteAguardandoConfirmacao(missaoAjudaId);

    mockMvc
        .perform(
            post(MISSOES + "/{id}/confirmar", missaoAjudaId)
                .header("Authorization", bearer(criador)))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post(MISSOES + "/{id}/confirmar", missaoAjudaId)
                .header("Authorization", bearer(criador)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONCLUIDA"));

    assertThat(xpDoUsuario(executor))
        .as("o replay não concede XP de novo")
        .isEqualTo(xpAntes + xpDaMissao);
  }

  /**
   * Financiar uma missão só-XP é recusado por {@code validarEstado}, com mensagem que diz o que
   * fazer.
   *
   * <p>Sem esse ramo a recusa ainda viria, mas de {@code validarTeto}, como "pote ficaria com 10
   * tokens, acima da recompensa de 0. Faltam apenas 0." — aritmética que não orienta ninguém.
   */
  @Test
  @DisplayName("Missão só-XP não aceita financiamento, e a recusa orienta")
  void financiarMissaoSemTokenDa422ComOrientacao() throws Exception {
    long circulacaoInicial = tokensEmCirculacao(jdbcTemplate);
    missaoAjudaId = criarMissaoSemToken("AJUDA");

    mockMvc
        .perform(financiarMissao(financiador, missaoAjudaId, 10L, "financiar-sem-token"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("só em XP")))
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("edite o")));

    assertThat(poteDe(missaoAjudaId)).isZero();
    assertThat(saldoTokens(financiador)).as("recusa sem efeito colateral").isEqualTo(SALDO);
    assertThat(tokensEmCirculacao(jdbcTemplate)).isEqualTo(circulacaoInicial);
  }

  /** Só TRIBO e AJUDA. ENTREGA e COLETA movem objeto e têm custo real — 400 no campo. */
  @Test
  @DisplayName("COLETA não pode recompensar só em XP — 400 apontando o campo")
  void coletaSoXpEh400() throws Exception {
    Instant inicio = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    String corpo =
        """
        {
          "categoria": "COLETA",
          "titulo": "Coleta de recicláveis da quadra",
          "descricao": "Missão de coleta que tenta se declarar sem recompensa em token.",
          "valorBrl": 0.00,
          "pesoKg": 8.00,
          "volumeL": 40.00,
          "origemLat": -23.5629,
          "origemLon": -46.6996,
          "cep": "05422030",
          "logradouro": "Rua dos Pinheiros",
          "bairro": "Pinheiros",
          "cidade": "São Paulo",
          "uf": "SP",
          "raioCheckinM": 50,
          "recompensaEmToken": false,
          "janelaInicio": "%s",
          "janelaFim": "%s"
        }
        """
            .formatted(inicio, inicio.plus(2, ChronoUnit.DAYS));

    mockMvc
        .perform(
            post(MISSOES)
                .header("Authorization", bearer(criador))
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.errors[*].campo")
                .value(org.hamcrest.Matchers.hasItem("recompensaEmToken")));
  }

  // ─── ADR 0036: recompensa de RASCUNHO acompanha os dados ─────────────────────────────────────

  /**
   * A edição que destrava quem criou missão comunitária e não conseguiu financiar o pote.
   *
   * <p>É o fluxo inteiro do problema relatado: cria com token, não consegue publicar, edita para só
   * XP, publica.
   */
  @Test
  @DisplayName("Editar o rascunho para só-XP destrava a publicação")
  void patchParaSoXpDestravaAPublicacao() throws Exception {
    missaoAjudaId = criarMissaoAjudaEmRascunho();
    assertThat(recompensaAjuda).isGreaterThan(0);

    mockMvc
        .perform(
            post(MISSOES + "/{id}/publicar", missaoAjudaId)
                .header("Authorization", bearer(criador)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/pote-insuficiente"));

    mockMvc
        .perform(
            patch(MISSOES + "/{id}", missaoAjudaId)
                .header("Authorization", bearer(criador))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recompensaEmToken\": false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tokensRecompensa").value(0))
        .andExpect(jsonPath("$.fontePote").value("SEM_TOKEN"));

    mockMvc
        .perform(
            post(MISSOES + "/{id}/publicar", missaoAjudaId)
                .header("Authorization", bearer(criador)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ABERTA"));
  }

  /** E o caminho de volta: quem mudou de ideia antes de publicar recupera a recompensa em token. */
  @Test
  @DisplayName("Editar de só-XP para com token recalcula a recompensa e volta a exigir pote")
  void patchDeVoltaParaTokenRecalcula() throws Exception {
    missaoAjudaId = criarMissaoSemToken("AJUDA");

    mockMvc
        .perform(
            patch(MISSOES + "/{id}", missaoAjudaId)
                .header("Authorization", bearer(criador))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recompensaEmToken\": true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tokensRecompensa").value(greaterThan(0)))
        .andExpect(jsonPath("$.fontePote").value("COMUNIDADE"));

    mockMvc
        .perform(
            post(MISSOES + "/{id}/publicar", missaoAjudaId)
                .header("Authorization", bearer(criador)))
        .andExpect(status().isUnprocessableEntity());
  }

  /**
   * A guarda que impede token preso: reduzir a recompensa abaixo do pote já financiado.
   *
   * <p>Sem ela a diferença ficaria imobilizada — a conclusão debita exatamente {@code
   * tokensRecompensa} e CONCLUIDA é terminal, então o resto nunca volta —, e a reconciliação
   * continuaria verde, porque ledger e projeção seguem batendo. É a perda que o ADR 0032 caça.
   */
  @Test
  @DisplayName("Editar para só-XP um rascunho já financiado é 422, e o pote fica intacto")
  void patchParaSoXpComPoteFinanciadoEhRecusado() throws Exception {
    missaoAjudaId = criarMissaoAjudaEmRascunho();
    mockMvc
        .perform(financiarMissao(financiador, missaoAjudaId, recompensaAjuda, "financiar-ajuda-1"))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            patch(MISSOES + "/{id}", missaoAjudaId)
                .header("Authorization", bearer(criador))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recompensaEmToken\": false}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/pote-insuficiente"))
        .andExpect(jsonPath("$.poteTokens").value(recompensaAjuda));

    assertThat(poteDe(missaoAjudaId))
        .as("o pote do financiador fica intacto")
        .isEqualTo(recompensaAjuda);
    assertThat(fonteDe(missaoAjudaId)).isEqualTo("COMUNIDADE");
    assertLedgerReconcilia(jdbcTemplate);
  }

  /** A partir de ABERTA a recompensa é promessa: trocar a forma dela é 409, não 422. */
  @Test
  @DisplayName("Trocar a forma de recompensa depois de publicada é 409")
  void patchDeRecompensaEmMissaoAbertaEh409() throws Exception {
    missaoAjudaId = criarMissaoSemToken("AJUDA");
    mockMvc
        .perform(
            post(MISSOES + "/{id}/publicar", missaoAjudaId)
                .header("Authorization", bearer(criador)))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            patch(MISSOES + "/{id}", missaoAjudaId)
                .header("Authorization", bearer(criador))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recompensaEmToken\": true}"))
        .andExpect(status().isConflict());

    assertThat(fonteDe(missaoAjudaId)).isEqualTo("SEM_TOKEN");
  }

  /**
   * Editar um rascunho SEM tocar na forma de recompensa recongela o valor a partir dos dados novos.
   *
   * <p>Antes do ADR 0036 o PATCH mudava peso e volume sem recalcular nada, e a missão passava a ter
   * insumos que não explicavam a própria recompensa — com {@code versaoFormula} afirmando que
   * explicavam.
   */
  @Test
  @DisplayName("Aumentar o peso de um rascunho ENTREGA aumenta a recompensa congelada")
  void patchDeInsumoRecongelaARecompensaDoRascunho() throws Exception {
    missaoAjudaId = criarMissaoEntregaEmRascunho();
    long antes = tokensDe(missaoAjudaId);

    mockMvc
        .perform(
            patch(MISSOES + "/{id}", missaoAjudaId)
                .header("Authorization", bearer(criador))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pesoKg\": 30.00, \"volumeL\": 120.00}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tokensRecompensa").value(greaterThan((int) antes)));

    assertThat(tokensDe(missaoAjudaId)).isGreaterThan(antes);
  }

  private UUID criarMissaoSemToken(String categoria) throws Exception {
    Instant inicio = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    String corpo =
        """
        {
          "categoria": "%s",
          "titulo": "Mutirão sem recompensa em token",
          "descricao": "Missão comunitária que recompensa só reputação, publicável na hora.",
          "valorBrl": 0.00,
          "complexidade": "MEDIA",
          "origemLat": -23.5629,
          "origemLon": -46.6996,
          "cep": "05422030",
          "logradouro": "Rua dos Pinheiros",
          "bairro": "Pinheiros",
          "cidade": "São Paulo",
          "uf": "SP",
          "raioCheckinM": 50,
          "recompensaEmToken": false,
          "janelaInicio": "%s",
          "janelaFim": "%s"
        }
        """
            .formatted(categoria, inicio, inicio.plus(2, ChronoUnit.DAYS));

    MvcResult criacao =
        mockMvc
            .perform(
                post(MISSOES)
                    .header("Authorization", bearer(criador))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(corpo))
            .andExpect(status().isCreated())
            .andReturn();

    var corpoCriada = JSON.readTree(criacao.getResponse().getContentAsString());
    recompensaAjuda = corpoCriada.get("tokensRecompensa").asLong();
    return UUID.fromString(corpoCriada.get("id").asText());
  }

  private UUID criarMissaoEntregaEmRascunho() throws Exception {
    Instant inicio = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    String corpo =
        """
        {
          "categoria": "ENTREGA",
          "titulo": "Levar encomenda até o vizinho",
          "descricao": "Missão de entrega criada por humano, usada para medir o recálculo.",
          "valorBrl": 0.00,
          "pesoKg": 2.00,
          "volumeL": 10.00,
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
                post(MISSOES)
                    .header("Authorization", bearer(criador))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(corpo))
            .andExpect(status().isCreated())
            .andReturn();

    return UUID.fromString(
        JSON.readTree(criacao.getResponse().getContentAsString()).get("id").asText());
  }

  /**
   * Publica, aceita, inicia e salta o check-in — que exige GPS e não é o que estes testes medem.
   */
  private void levarAteAguardandoConfirmacao(UUID missao) throws Exception {
    mockMvc
        .perform(post(MISSOES + "/{id}/publicar", missao).header("Authorization", bearer(criador)))
        .andExpect(status().isOk());
    mockMvc
        .perform(post(MISSOES + "/{id}/aceitar", missao).header("Authorization", bearer(executor)))
        .andExpect(status().isOk());
    mockMvc
        .perform(post(MISSOES + "/{id}/iniciar", missao).header("Authorization", bearer(executor)))
        .andExpect(status().isOk());
    jdbcTemplate.update("UPDATE missao SET status = 'AGUARDANDO_CONFIRMACAO' WHERE id = ?", missao);
  }

  private String fonteDe(UUID missao) {
    return jdbcTemplate.queryForObject(
        "SELECT fonte_pote FROM missao WHERE id = ?", String.class, missao);
  }

  private int xpDe(UUID missao) {
    return jdbcTemplate.queryForObject(
        "SELECT xp_recompensa FROM missao WHERE id = ?", Integer.class, missao);
  }

  private long tokensDe(UUID missao) {
    return jdbcTemplate.queryForObject(
        "SELECT tokens_recompensa FROM missao WHERE id = ?", Long.class, missao);
  }

  private int xpDoUsuario(UUID usuarioId) {
    return jdbcTemplate.queryForObject(
        "SELECT xp FROM usuario WHERE id = ?", Integer.class, usuarioId);
  }

  private int lancamentosDaMissao(UUID missao) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM lancamento WHERE missao_id = ?", Integer.class, missao);
  }

  private UUID criarMissaoAjudaEmRascunho() throws Exception {
    Instant inicio = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    String corpo =
        """
        {
          "categoria": "AJUDA",
          "titulo": "Ajudar a carregar um móvel",
          "descricao": "Missão entre vizinhos, paga com tokens financiados por outros membros.",
          "valorBrl": 0.00,
          "complexidade": "MEDIA",
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
                post(MISSOES)
                    .header("Authorization", bearer(criador))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(corpo))
            .andExpect(status().isCreated())
            .andReturn();

    var corpoCriada = JSON.readTree(criacao.getResponse().getContentAsString());
    recompensaAjuda = corpoCriada.get("tokensRecompensa").asLong();
    return UUID.fromString(corpoCriada.get("id").asText());
  }

  private long poteDe(UUID missao) {
    return jdbcTemplate.queryForObject(
        "SELECT pote_tokens FROM missao WHERE id = ?", Long.class, missao);
  }

  private String statusDe(UUID missao) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM missao WHERE id = ?", String.class, missao);
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

  private String bearer(UUID usuarioId) {
    return "Bearer "
        + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", "USUARIO");
  }
}
