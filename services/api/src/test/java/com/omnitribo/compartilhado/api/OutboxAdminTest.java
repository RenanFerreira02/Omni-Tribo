package com.omnitribo.compartilhado.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import com.omnitribo.compartilhado.dominio.DrenadorOutboxService;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * A carta-morta da outbox: {@code GET /admin/outbox/esgotados} e {@code POST
 * /admin/outbox/{id}/reenfileirar} (ADR 0031).
 *
 * <p>O teste que carrega o peso é {@link #reenfileirarDevolveOEventoAoMesmoCaminhoDePublicacao}:
 * ele é o que prova que o endpoint NÃO despacha por um atalho próprio. Reenfileirar só torna a
 * linha elegível; a entrega só acontece quando o {@code DrenadorOutboxService} — o mesmo de sempre
 * — roda. Se alguém um dia trocar isso por um despacho em linha, é este teste que fica vermelho,
 * porque o alerta apareceria antes do {@code drenarLote}.
 *
 * <p>O drenador é chamado à mão porque {@code app.agendamento.habilitado} é {@code false} no perfil
 * de teste — o job não roda por trás do arrange, e é isso que torna a ordem observável.
 */
@Import(JwtTestConfig.class)
@DisplayName("Carta-morta da outbox (ADMIN)")
class OutboxAdminTest extends TesteIntegracaoMvcBase {

  private static final String URL = "/api/v1/admin/outbox";

  /** Seed V900. */
  private static final UUID ADMIN = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

  private static final UUID ALICE = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

  /**
   * Lido da MESMA chave que o drenador e o serviço leem, e não escrito à mão.
   *
   * <p>Um {@code 5} literal aqui faria a suíte concordar com um número em vez de com o sistema:
   * baixar o teto no YAML deixaria os dois lados coerentes entre si e o teste coerente com nada. O
   * que trava o ALINHAMENTO entre o teto do drenador e o da consulta, porém, não é este campo — é
   * {@link #eventoQueEsgotaNoDrenadorApareceNaCartaMorta}, que não menciona número nenhum.
   */
  @Value("${app.outbox.maximo-tentativas}")
  int maximoTentativas;

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired DrenadorOutboxService drenadorOutboxService;
  @Autowired DataSource dataSource;

  private final List<UUID> eventos = new ArrayList<>();
  private final List<UUID> usuarios = new ArrayList<>();

  @AfterEach
  void limpar() {
    eventos.forEach(id -> jdbcTemplate.update("DELETE FROM outbox WHERE id = ?", id));
    eventos.forEach(
        id -> jdbcTemplate.update("DELETE FROM auditoria WHERE entidade_id = ?", id.toString()));
    usuarios.forEach(
        id -> {
          jdbcTemplate.update("DELETE FROM alerta WHERE usuario_id = ?", id);
          jdbcTemplate.update("DELETE FROM usuario WHERE id = ?", id);
        });
    eventos.clear();
    usuarios.clear();
  }

  // ─── Autorização ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("sem token é 401 nos dois endpoints")
  void semTokenEh401() throws Exception {
    mockMvc.perform(get(URL + "/esgotados")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(post(URL + "/" + UUID.randomUUID() + "/reenfileirar"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("usuário comum é 403 e o corpo não traz a lista")
  void naoAdminEh403() throws Exception {
    UUID esgotado = inserirEsgotado();

    mockMvc
        .perform(get(URL + "/esgotados").header("Authorization", bearer(ALICE, "USUARIO")))
        .andExpect(status().isForbidden())
        // ultimo_erro carrega mensagem de exceção Java, que é exatamente o que o projeto mantém
        // fora de toda resposta de erro. O corpo do 403 não pode trazer de brinde o que o status
        // recusou.
        .andExpect(jsonPath("$.conteudo").doesNotExist())
        .andExpect(jsonPath("$.totalElementos").doesNotExist());

    mockMvc
        .perform(
            post(URL + "/" + esgotado + "/reenfileirar")
                .header("Authorization", bearer(ALICE, "USUARIO")))
        .andExpect(status().isForbidden());

    assertThat(tentativas(esgotado))
        .as("403 não pode ter efeito colateral nenhum")
        .isEqualTo(maximoTentativas);
  }

  // ─── Consulta ───────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("evento que esgotou as tentativas aparece na consulta, com a causa")
  void eventoEsgotadoApareceNaConsulta() throws Exception {
    UUID esgotado = inserirEsgotado();

    mockMvc
        .perform(get(URL + "/esgotados").header("Authorization", bearer(ADMIN, "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.conteudo[?(@.id == '" + esgotado + "')]").exists())
        .andExpect(
            jsonPath("$.conteudo[?(@.id == '" + esgotado + "')].tentativas")
                .value(maximoTentativas))
        .andExpect(
            jsonPath("$.conteudo[?(@.id == '" + esgotado + "')].ultimoErro")
                .value("IllegalStateException: nenhum despachante"))
        // Os demais campos conferidos um a um: trocar dois deles no construtor de
        // EventoEsgotadoResponse.de passaria despercebido se só `id` e `tentativas` fossem olhados.
        .andExpect(
            jsonPath("$.conteudo[?(@.id == '" + esgotado + "')].tipoEvento")
                .value("EventoDesconhecido"))
        .andExpect(
            jsonPath("$.conteudo[?(@.id == '" + esgotado + "')].agregadoId")
                .value(agregadoDe(esgotado).toString()))
        .andExpect(jsonPath("$.conteudo[?(@.id == '" + esgotado + "')].criadoEm").isNotEmpty())
        .andExpect(
            jsonPath("$.conteudo[?(@.id == '" + esgotado + "')].proximaTentativaEm").isNotEmpty())
        // O payload é o que NÃO vem: ele carrega coordenada residencial e valor creditado.
        .andExpect(jsonPath("$.conteudo[?(@.id == '" + esgotado + "')].payload").doesNotExist());
  }

  @Test
  @DisplayName("publicado e pendente-em-backoff ficam de fora: a consulta é de esgotados")
  void eventoPublicadoNaoApareceNaConsulta() throws Exception {
    // Os TRÊS na mesma resposta, e o esgotado é o controle positivo. Sem ele, `doesNotContain`
    // sozinho passaria com a consulta devolvendo página vazia — asserção de ausência não prova
    // nada se a lista puder ser sempre vazia. Também prende `totalElementos`, que o ADR chama de
    // "o número que antes não existia em lugar nenhum" e que não tinha uma única asserção.
    UUID esgotado = inserirEsgotado();
    UUID publicado = inserirEvento(maximoTentativas, Instant.now(), "erro antigo");
    UUID emBackoff = inserirEvento(2, null, "falhou duas vezes");

    long esgotadosNoBanco =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox WHERE publicado_em IS NULL AND tentativas >= ?",
            Long.class,
            maximoTentativas);

    String corpo =
        mockMvc
            .perform(
                get(URL + "/esgotados?tamanho=100").header("Authorization", bearer(ADMIN, "ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElementos").value((int) esgotadosNoBanco))
            .andExpect(jsonPath("$.conteudo[?(@.id == '" + esgotado + "')]").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(corpo)
        .as("publicado_em preenchido tira a linha da carta-morta, mesmo com tentativas no teto")
        .doesNotContain(publicado.toString())
        .as("pendente abaixo do teto ainda vai ser tentado — não é carta-morta")
        .doesNotContain(emBackoff.toString());
  }

  // ─── Reenfileiramento ───────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("reenfileirar devolve o evento ao MESMO caminho de publicação")
  void reenfileirarDevolveOEventoAoMesmoCaminhoDePublicacao() throws Exception {
    UUID executor = criarUsuario();
    UUID esgotado = inserirEsgotadoDeConclusao(executor);

    mockMvc
        .perform(
            post(URL + "/" + esgotado + "/reenfileirar")
                .header("Authorization", bearer(ADMIN, "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reenfileirado").value(true))
        .andExpect(jsonPath("$.tentativas").value(0));

    assertThat(publicadoEm(esgotado))
        .as("o endpoint torna elegível, mas NÃO despacha: quem entrega é o drenador")
        .isNull();
    assertThat(contarAlertas(executor)).as("nenhum alerta antes de drenar").isZero();
    assertThat(ultimoErro(esgotado))
        .as("a causa da parada é preservada — é o diagnóstico da próxima rodada")
        .isEqualTo("IllegalStateException: nenhum despachante");

    drenadorOutboxService.drenarLote(50);

    assertThat(publicadoEm(esgotado)).as("agora sim, pelo caminho de sempre").isNotNull();
    assertThat(contarAlertas(executor)).isEqualTo(1L);
  }

  @Test
  @DisplayName("reenfileirar repetido não duplica efeito")
  void reenfileirarRepetidoNaoDuplicaEfeito() throws Exception {
    UUID executor = criarUsuario();
    UUID esgotado = inserirEsgotadoDeConclusao(executor);

    for (int i = 0; i < 3; i++) {
      mockMvc
          .perform(
              post(URL + "/" + esgotado + "/reenfileirar")
                  .header("Authorization", bearer(ADMIN, "ADMIN")))
          .andExpect(status().isOk())
          // Só a primeira escreve. As demais caem no no-op da sondagem: a linha já está na fila.
          .andExpect(jsonPath("$.reenfileirado").value(i == 0))
          .andExpect(jsonPath("$.tentativas").value(0));
    }

    drenadorOutboxService.drenarLote(50);

    assertThat(contarAlertas(executor))
        .as("três POSTs, uma entrega — o efeito é o estado da linha, não a contagem de chamadas")
        .isEqualTo(1L);

    // Trava a consequência negativa que o ADR declara: o AuditoriaAspecto é @AfterReturning e grava
    // também no no-op. TRÊS linhas para UM reenfileiramento real — contar `auditoria` superestima,
    // e
    // é melhor esse fato estar preso num teste do que só afirmado num javadoc.
    Long linhasDeAuditoria =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM auditoria WHERE entidade = 'outbox' AND entidade_id = ?",
            Long.class,
            esgotado.toString());
    assertThat(linhasDeAuditoria)
        .as("a trilha registra a REQUISIÇÃO, não a mudança de estado")
        .isEqualTo(3L);
  }

  @Test
  @DisplayName("evento ainda em backoff é no-op: o endpoint não atropela o backoff")
  void eventoEmBackoffEhNoOp() throws Exception {
    UUID emBackoff = inserirEvento(2, null, "falhou duas vezes");
    Instant antes = proximaTentativaEm(emBackoff);

    mockMvc
        .perform(
            post(URL + "/" + emBackoff + "/reenfileirar")
                .header("Authorization", bearer(ADMIN, "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reenfileirado").value(false))
        .andExpect(jsonPath("$.tentativas").value(2));

    assertThat(proximaTentativaEm(emBackoff))
        .as("o backoff existe para não virar rajada contra destino fora do ar")
        .isEqualTo(antes);
  }

  @Test
  @DisplayName("evento já entregue é 409, não replay")
  void eventoJaPublicadoEh409() throws Exception {
    UUID publicado = inserirEvento(maximoTentativas, Instant.now(), null);

    mockMvc
        .perform(
            post(URL + "/" + publicado + "/reenfileirar")
                .header("Authorization", bearer(ADMIN, "ADMIN")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/transicao-invalida"));

    assertThat(tentativas(publicado)).as("409 sem efeito colateral").isEqualTo(maximoTentativas);
  }

  @Test
  @DisplayName("evento inexistente é 404")
  void eventoInexistenteEh404() throws Exception {
    mockMvc
        .perform(
            post(URL + "/" + UUID.randomUUID() + "/reenfileirar")
                .header("Authorization", bearer(ADMIN, "ADMIN")))
        .andExpect(status().isNotFound())
        // O `type` é o contrato (ADR 0010); o status sozinho é ambíguo para o cliente.
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/nao-encontrado"));
  }

  @Test
  @DisplayName("reenfileirar grava auditoria COM entidade_id — a metade que o compilador não cobra")
  void reenfileirarGravaAuditoria() throws Exception {
    UUID esgotado = inserirEsgotado();

    mockMvc
        .perform(
            post(URL + "/" + esgotado + "/reenfileirar")
                .header("Authorization", bearer(ADMIN, "ADMIN")))
        .andExpect(status().isOk());

    // O ArchUnit garante que o tipo de retorno implementa RecursoAuditavel; só um teste de
    // integração garante que o entidade_id chegou PREENCHIDO até a tabela. Foi assim que o
    // check-in passou a gravar auditoria com entidade_id nulo sem nenhum teste acusar.
    Long linhas =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM auditoria
            WHERE acao = 'OUTBOX_REENFILEIRADA' AND entidade = 'outbox'
              AND entidade_id = ? AND ator_id = ?
            """,
            Long.class,
            esgotado.toString(),
            ADMIN);
    assertThat(linhas).isEqualTo(1L);
  }

  @Test
  @DisplayName("dez POSTs concorrentes: uma escrita só, e as dez respostas são 200")
  void reenfileiramentoConcorrenteNaoDuplica() throws Exception {
    UUID executor = criarUsuario();
    UUID esgotado = inserirEsgotadoDeConclusao(executor);

    int threads = 10;
    var largada = new CountDownLatch(1);
    var fim = new CountDownLatch(threads);
    var reenfileirados = new AtomicInteger();
    // Throwable, e NÃO Exception: `andExpect(status().isOk())` falha com AssertionError, que não é
    // Exception. Com `catch (Exception e)` o erro sumia junto com o Future descartado pelo submit,
    // e
    // este teste ficava VERDE mesmo com nove threads recebendo 404 — foi o que uma revisão provou
    // trocando o FOR UPDATE por SKIP LOCKED. A asserção "nenhuma requisição pode falhar" existia e
    // não podia falhar.
    var falhas = new CopyOnWriteArrayList<Throwable>();
    var statuses = new CopyOnWriteArrayList<Integer>();

    try (var pool = Executors.newFixedThreadPool(threads)) {
      for (int i = 0; i < threads; i++) {
        pool.submit(
            () -> {
              try {
                largada.await();
                var resposta =
                    mockMvc
                        .perform(
                            post(URL + "/" + esgotado + "/reenfileirar")
                                .header("Authorization", bearer(ADMIN, "ADMIN")))
                        .andReturn()
                        .getResponse();
                statuses.add(resposta.getStatus());
                if (resposta.getStatus() == 200
                    && JSON.readTree(resposta.getContentAsString())
                        .get("reenfileirado")
                        .asBoolean()) {
                  reenfileirados.incrementAndGet();
                }
              } catch (Throwable t) {
                falhas.add(t);
              } finally {
                fim.countDown();
              }
            });
      }
      largada.countDown();
      assertThat(fim.await(60, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(falhas).as("nenhuma thread pode estourar").isEmpty();
    // O DESFECHO das perdedoras, e não só o da vencedora. Sem FOR UPDATE — ou com SKIP LOCKED, que
    // faria a linha travada sumir da consulta — as nove perdedoras receberiam 404 num evento que
    // existe, e a asserção de baixo continuaria verde.
    assertThat(statuses)
        .as("as dez requisições respondem 200: ninguém leva 404 num evento que existe")
        .hasSize(threads)
        .containsOnly(200);
    // É o FOR UPDATE que serializa: sem ele, as N threads leem tentativas no teto juntas e todas
    // escrevem.
    assertThat(reenfileirados).as("exatamente uma viu o evento esgotado").hasValue(1);

    drenadorOutboxService.drenarLote(50);
    assertThat(contarAlertas(executor)).isEqualTo(1L);
  }

  @Test
  @DisplayName("POST enquanto o drenador segura a linha espera o commit e vê o estado real")
  void reenfileirarEsperaODrenadorEVeOEventoJaPublicado() throws Exception {
    UUID executor = criarUsuario();
    UUID esgotado = inserirEsgotadoDeConclusao(executor);

    // Simula o drenador: transação aberta segurando a MESMA linha com FOR UPDATE e marcando-a
    // publicada. É o cenário que o javadoc de OutboxRepository.buscarParaAtualizar dá como motivo
    // para o endpoint NÃO usar SKIP LOCKED — com skip, o POST pularia a linha travada e responderia
    // 404 para um evento que existe. Aqui ele tem de ESPERAR e então enxergar o estado real.
    var respostaDoPost = new java.util.concurrent.atomic.AtomicReference<Integer>();
    var corpoDoPost = new java.util.concurrent.atomic.AtomicReference<String>();
    var falha = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var postDisparado = new CountDownLatch(1);
    var postTerminou = new CountDownLatch(1);

    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(false);
      try (var st = conn.prepareStatement("SELECT id FROM outbox WHERE id = ? FOR UPDATE")) {
        st.setObject(1, esgotado);
        st.executeQuery().next();
      }
      try (var st = conn.prepareStatement("UPDATE outbox SET publicado_em = NOW() WHERE id = ?")) {
        st.setObject(1, esgotado);
        st.executeUpdate();
      }

      Thread t =
          new Thread(
              () -> {
                try {
                  postDisparado.countDown();
                  var r =
                      mockMvc
                          .perform(
                              post(URL + "/" + esgotado + "/reenfileirar")
                                  .header("Authorization", bearer(ADMIN, "ADMIN")))
                          .andReturn()
                          .getResponse();
                  respostaDoPost.set(r.getStatus());
                  corpoDoPost.set(r.getContentAsString());
                } catch (Throwable e) {
                  falha.set(e);
                } finally {
                  postTerminou.countDown();
                }
              });
      t.start();

      assertThat(postDisparado.await(10, TimeUnit.SECONDS)).isTrue();
      // Dá tempo de o POST chegar ao FOR UPDATE e bloquear. Se ele NÃO bloquear — SKIP LOCKED, por
      // exemplo — ele termina aqui, com 404, e a asserção lá embaixo pega.
      assertThat(postTerminou.await(2, TimeUnit.SECONDS))
          .as("o POST tem de BLOQUEAR no lock, não responder por cima da linha travada")
          .isFalse();

      conn.commit();
      assertThat(postTerminou.await(30, TimeUnit.SECONDS)).isTrue();
      t.join(5_000);
    }

    assertThat(falha.get()).isNull();
    assertThat(respostaDoPost.get())
        .as("depois do commit do drenador o evento está publicado: 409, nunca 404")
        .isEqualTo(409);
    assertThat(corpoDoPost.get()).contains("transicao-invalida");
  }

  // ─── O alinhamento entre os dois tetos ──────────────────────────────────────────────────────

  @Test
  @DisplayName("evento que esgota PELO DRENADOR aparece na carta-morta")
  void eventoQueEsgotaNoDrenadorApareceNaCartaMorta() throws Exception {
    // Nenhum outro teste desta suíte faz um evento esgotar de verdade: todos fabricam a fixture com
    // INSERT ... tentativas = <teto>. Isso deixa os dois lados afirmando o mesmo número mágico sem
    // que nenhum observe o outro — se o drenador parasse na quarta falha e a consulta continuasse
    // procurando a quinta, tudo seguiria verde e a carta-morta ficaria eternamente vazia.
    //
    // Este teste não menciona número nenhum: drena até o DRENADOR parar de tocar a linha, e então
    // exige que a CONSULTA a enxergue. Desalinhar os dois predicados o deixa vermelho.
    UUID envenenado = inserirEvento("EventoDesconhecido", Map.of("x", 1), 0, null, null);
    jdbcTemplate.update("UPDATE outbox SET proxima_tentativa_em = NOW() WHERE id = ?", envenenado);

    int anterior = -1;
    int rodadas = 0;
    while (rodadas++ < 30) {
      drenadorOutboxService.drenarLote(50);
      int agora = tentativas(envenenado);
      if (agora == anterior) {
        break;
      }
      anterior = agora;
      // Só ADIANTA O RELÓGIO do backoff — não toca em `tentativas`, que é o que está sob teste.
      jdbcTemplate.update(
          "UPDATE outbox SET proxima_tentativa_em = NOW() WHERE id = ?", envenenado);
    }

    assertThat(rodadas)
        .as("o drenador tem de parar sozinho, não por esgotar o laço")
        .isLessThan(30);
    assertThat(publicadoEm(envenenado)).as("nunca foi entregue").isNull();
    assertThat(tentativas(envenenado))
        .as("quem incrementou foi o drenador, não o teste")
        .isEqualTo(maximoTentativas);
    assertThat(ultimoErro(envenenado)).contains("Nenhum despachante");

    mockMvc
        .perform(get(URL + "/esgotados").header("Authorization", bearer(ADMIN, "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.conteudo[?(@.id == '" + envenenado + "')]").exists());
  }

  @Test
  @DisplayName("uma tentativa ABAIXO do teto não é carta-morta e não é reenfileirável")
  void eventoUmaTentativaAbaixoDoTetoNaoEhCartaMorta() throws Exception {
    // A fronteira exata. A suíte testava 2 (abaixo) e o teto (dentro), nunca teto-1 — que é o único
    // valor que distingue `>=` de `>` deslocado por um, dos DOIS lados. Com `>= teto - 1` na
    // consulta, um evento que o drenador ainda vai tentar apareceria como fato perdido e um ADMIN o
    // "recuperaria"; com `< teto - 1` na guarda, o endpoint escreveria por cima de um backoff
    // legítimo. JaCoCo dá 100% de branch aqui e não vê nenhum dos dois.
    UUID quaseEsgotado = inserirEvento(maximoTentativas - 1, null, "penúltima falha");
    Instant antes = proximaTentativaEm(quaseEsgotado);

    String corpo =
        mockMvc
            .perform(get(URL + "/esgotados").header("Authorization", bearer(ADMIN, "ADMIN")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(corpo)
        .as("ainda vai ser tentado pelo drenador: não é carta-morta")
        .doesNotContain(quaseEsgotado.toString());

    mockMvc
        .perform(
            post(URL + "/" + quaseEsgotado + "/reenfileirar")
                .header("Authorization", bearer(ADMIN, "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reenfileirado").value(false))
        .andExpect(jsonPath("$.tentativas").value(maximoTentativas - 1));

    assertThat(proximaTentativaEm(quaseEsgotado)).isEqualTo(antes);
  }

  @Test
  @DisplayName("payload envenenado reenfileirado torna a falhar e volta para a carta-morta")
  void eventoEnvenenadoReenfileiradoVoltaAFalhar() throws Exception {
    // O ADR lista como consequência negativa que reenfileirar um defeito PERMANENTE gasta mais um
    // ciclo de despachos e volta ao mesmo lugar. Isso estava escrito e não estava executado.
    UUID envenenado = inserirEsgotado();

    mockMvc
        .perform(
            post(URL + "/" + envenenado + "/reenfileirar")
                .header("Authorization", bearer(ADMIN, "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reenfileirado").value(true));

    drenadorOutboxService.drenarLote(50);

    assertThat(publicadoEm(envenenado)).as("o payload continua sem despachante").isNull();
    assertThat(tentativas(envenenado)).as("gastou uma tentativa do ciclo novo").isEqualTo(1);
    assertThat(ultimoErro(envenenado)).contains("Nenhum despachante");
  }

  // ─── Validação de entrada ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("paginação fora dos limites é 400 — a carta-morta não lista sem teto")
  void paginacaoInvalidaEh400() throws Exception {
    for (String query : new String[] {"?tamanho=5000", "?tamanho=0", "?pagina=-3"}) {
      mockMvc
          .perform(get(URL + "/esgotados" + query).header("Authorization", bearer(ADMIN, "ADMIN")))
          .andExpect(status().isBadRequest())
          .andExpect(
              jsonPath("$.type").value("https://omnitribo.dev/problemas/requisicao-invalida"));
    }
  }

  // ─── Fixtures ───────────────────────────────────────────────────────────────────────────────

  /** Esgotado com payload que o despachante NÃO sabe tratar — o caso real de carta-morta. */
  private UUID inserirEsgotado() {
    return inserirEvento(maximoTentativas, null, "IllegalStateException: nenhum despachante");
  }

  /**
   * Esgotado que o despachante consegue tratar, para provar a entrega depois do reenfileiramento.
   */
  private UUID inserirEsgotadoDeConclusao(UUID executorId) {
    return inserirEvento(
        "MissaoConcluida",
        Map.of(
            "missaoId",
            UUID.randomUUID().toString(),
            "executorId",
            executorId.toString(),
            "tokens",
            10,
            "nivelAtual",
            2,
            "subiuDeNivel",
            false),
        maximoTentativas,
        null,
        "IllegalStateException: nenhum despachante");
  }

  private UUID inserirEvento(int tentativas, Instant publicadoEm, String ultimoErro) {
    return inserirEvento("EventoDesconhecido", Map.of("x", 1), tentativas, publicadoEm, ultimoErro);
  }

  private UUID inserirEvento(
      String tipo,
      Map<String, Object> payload,
      int tentativas,
      Instant publicadoEm,
      String ultimoErro) {

    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO outbox (id, tipo_evento, agregado_id, payload, criado_em, publicado_em,
                            tentativas, proxima_tentativa_em, ultimo_erro)
        VALUES (?, ?, ?, ?::jsonb, NOW(), ?, ?, NOW() + INTERVAL '8 minutes', ?)
        """,
        id,
        tipo,
        UUID.randomUUID(),
        JsonMapper.builder().build().writeValueAsString(payload),
        publicadoEm == null ? null : Timestamp.from(publicadoEm),
        tentativas,
        ultimoErro);
    eventos.add(id);
    return id;
  }

  private UUID criarUsuario() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO usuario (id, nome, email, senha_hash, handle, xp, nivel, streak, rating,
                             papel, status, criado_em, atualizado_em, versao)
        VALUES (?, 'Destinatario', ?, '{bcrypt}$2a$10$naoUsadoNesteTeste', ?, 0, 1, 0, 0.0,
                'USUARIO', 'ATIVO', NOW(), NOW(), 0)
        """,
        id,
        "carta-morta-" + id + "@teste.dev",
        "c" + id.toString().substring(0, 10));
    usuarios.add(id);
    return id;
  }

  private Instant publicadoEm(UUID eventoId) {
    Timestamp t =
        jdbcTemplate.queryForObject(
            "SELECT publicado_em FROM outbox WHERE id = ?", Timestamp.class, eventoId);
    return t == null ? null : t.toInstant();
  }

  private Instant proximaTentativaEm(UUID eventoId) {
    return jdbcTemplate
        .queryForObject(
            "SELECT proxima_tentativa_em FROM outbox WHERE id = ?", Timestamp.class, eventoId)
        .toInstant();
  }

  private UUID agregadoDe(UUID eventoId) {
    return jdbcTemplate.queryForObject(
        "SELECT agregado_id FROM outbox WHERE id = ?", UUID.class, eventoId);
  }

  private int tentativas(UUID eventoId) {
    return jdbcTemplate.queryForObject(
        "SELECT tentativas FROM outbox WHERE id = ?", Integer.class, eventoId);
  }

  private String ultimoErro(UUID eventoId) {
    return jdbcTemplate.queryForObject(
        "SELECT ultimo_erro FROM outbox WHERE id = ?", String.class, eventoId);
  }

  private long contarAlertas(UUID usuarioId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM alerta WHERE usuario_id = ?", Long.class, usuarioId);
  }

  private String bearer(UUID usuarioId, String papel) {
    return "Bearer " + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", papel);
  }
}
