package com.omnitribo.logistica.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import com.omnitribo.compartilhado.dominio.DrenadorOutboxService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A rajada do §6, reproduzida — e a prova de que ela não grava mais 631 linhas.
 *
 * <p>Em 2026-08-25 o teste de carga ({@code docs/evidencias/f21-carga.md} §6) mediu <b>631 linhas
 * idênticas</b> em {@code alerta}, todas {@code PONTO_CUSTODIA_LOTADO}, todas do mesmo ponto, em
 * menos de 3 minutos. Nenhum teste cobria aquele caminho: {@code gravarPontoLotado} estava em
 * <b>0/35 instruções</b> no JaCoCo ({@code docs/auditoria/varredura-orfaos.md}, item 5.4). {@code
 * WebhookEntregaFalidaTest} chega a apagar {@code PONTO_CUSTODIA_LOTADO} no {@code @AfterEach} sem
 * nunca drenar a outbox — limpava um alerta que não chegava a existir.
 *
 * <p>Esta suíte é a primeira cobertura do caminho, e ela mede as DUAS metades do ADR 0033: a
 * deduplicação na escrita e a consulta que devolve a frequência que a deduplicação deixa de
 * guardar. Uma sem a outra não fecha a pendência — deduplicar sozinho trocaria 631 linhas não lidas
 * por 24 por dia não lidas.
 */
@Import(JwtTestConfig.class)
@DisplayName("Alerta operacional: rajada, deduplicação e a frequência preservada")
class DespachanteAlertaOperacionalTest extends TesteIntegracaoMvcBase {

  private static final String URL = "/api/v1/webhooks/transportadora";

  private static final String SLUG = "transportadora-teste";
  private static final String SEGREDO = "segredo-de-teste-nao-usar-em-producao";

  /** V905 deixa esta SEM patrocinador de propósito — é a fixture do desfecho SEM_PATROCINIO. */
  private static final String SLUG_SEM_PATROCINIO = "outra-transportadora";

  private static final String SEGREDO_SEM_PATROCINIO = "outro-segredo-de-teste";

  /** Portaria Ed. Aurora — capacidade 2, ocupação 2 no seed V904. Lotado por construção. */
  private static final UUID PONTO_LOTADO = UUID.fromString("cccccccc-0000-0000-0000-000000000904");

  /** Leroy Merlin Pinheiros — capacidade 50, ocupação 3. Tem vaga. */
  private static final UUID PONTO_COM_VAGA =
      UUID.fromString("cccccccc-0000-0000-0000-000000000001");

  /**
   * Portaria Ed. Solar Pinheiros — capacidade 5, ocupação 1. O segundo ponto da chave por ponto.
   */
  private static final UUID OUTRO_PONTO = UUID.fromString("cccccccc-0000-0000-0000-000000000003");

  private static final String TIPO_LOTADO = "PONTO_CUSTODIA_LOTADO";
  private static final String TIPO_SEM_PATROCINIO = "ENTREGA_SEM_PATROCINIO";

  private static final UUID ADMIN = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

  /**
   * Alice, USUARIO do seed V900.
   *
   * <p>Precisa ser um usuário que EXISTE: desde o ADR 0016 o {@code JwtAuthFilter} consulta {@code
   * ConsultaSessao} a cada requisição e monta o principal do BANCO, não dos claims. Um UUID
   * inventado com papel "USUARIO" no token não chega ao {@code @PreAuthorize} — morre antes, como
   * 401, e o teste mediria a autenticação em vez da autorização.
   */
  private static final UUID ALICE = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

  /** O tamanho da rajada. 50 e não 631 — o que se mede é o formato do resultado, não a escala. */
  private static final int RAJADA = 50;

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired DrenadorOutboxService drenadorOutboxService;

  @AfterEach
  void limpar() {
    jdbcTemplate.update(
        "DELETE FROM entrega_falida WHERE transportadora IN (?, ?)", SLUG, SLUG_SEM_PATROCINIO);
    jdbcTemplate.update(
        "DELETE FROM alerta WHERE tipo IN (?, ?)", TIPO_LOTADO, TIPO_SEM_PATROCINIO);
    jdbcTemplate.update("DELETE FROM outbox WHERE tipo_evento LIKE 'EntregaFalida%'");
    jdbcTemplate.update("UPDATE ponto_custodia SET ocupacao = 2 WHERE id = ?", PONTO_LOTADO);
  }

  // ─── A rajada ───────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("rajada contra ponto cheio na mesma janela grava UMA linha de alerta")
  void rajadaNaMesmaJanelaGravaUmaLinhaSo() throws Exception {
    for (int i = 0; i < RAJADA; i++) {
      recusar(SLUG, SEGREDO, PONTO_LOTADO);
    }
    drenar();

    assertThat(alertas(TIPO_LOTADO))
        .as("era uma linha por evento: a medição de 2026-08-25 gravou 631 para o mesmo ponto")
        .isEqualTo(1);

    // As duas metades da correção. O FATO continua gravado uma vez por recusa — é ele que a
    // transportadora precisa poder conferir, e é de onde a frequência é derivada.
    assertThat(recusasGravadas())
        .as("deduplicar o ALERTA não pode deduplicar o FATO")
        .isEqualTo(RAJADA);

    assertThat(ocupacao(PONTO_LOTADO))
        .as("recusa não ocupa vaga: o ponto continua com 2 de 2")
        .isEqualTo(2);
  }

  @Test
  @DisplayName("a linha deduplicada carrega a chave que a dedup usa")
  void aLinhaGravadaCarregaReferenciaEJanela() throws Exception {
    recusar(SLUG, SEGREDO, PONTO_LOTADO);
    drenar();

    var linha =
        jdbcTemplate.queryForMap(
            "SELECT referencia, janela_inicio, criado_em, usuario_id, missao_id, corpo FROM alerta"
                + " WHERE tipo = ?",
            TIPO_LOTADO);

    assertThat(linha.get("referencia"))
        .as("sem o ponto na linha, a dedup teria de parsear a frase do corpo")
        .isEqualTo(PONTO_LOTADO.toString());
    // A janela é DERIVADA do relógio, e é preciso afirmar isso aqui. O teste da janela seguinte
    // envelhece janela_inicio no banco, então ele passaria com uma janelaDe() que devolvesse
    // sempre a mesma constante — foi a sabotagem que escapou na primeira passada. Comparar com o
    // criado_em da PRÓPRIA linha é determinístico: os dois saem do mesmo `agora`, então nem uma
    // execução que cruze a virada da hora produz divergência.
    Instant janela = ((java.sql.Timestamp) linha.get("janela_inicio")).toInstant();
    Instant criadoEm = ((java.sql.Timestamp) linha.get("criado_em")).toInstant();
    assertThat(janela)
        .as("janela-alerta-operacional é PT1H: o marco é o topo da hora do próprio alerta")
        .isEqualTo(criadoEm.truncatedTo(ChronoUnit.HOURS));
    assertThat(linha.get("usuario_id")).as("alerta de operação é global").isNull();
    assertThat(linha.get("missao_id"))
        .as("não houve missão — é essa ausência que ele relata")
        .isNull();

    // A linha passou a representar a JANELA e não um evento: dentro dela cabem recusas de
    // transportadoras diferentes, então nomear uma delas seria afirmação falsa sobre as outras.
    assertThat((String) linha.get("corpo"))
        .as("o corpo não pode atribuir a janela inteira a uma transportadora")
        .doesNotContain(SLUG);
  }

  @Test
  @DisplayName("janela seguinte volta a gravar: a dedup não é uma vez para sempre")
  void janelaSeguinteGravaLinhaNova() throws Exception {
    recusar(SLUG, SEGREDO, PONTO_LOTADO);
    drenar();
    assertThat(alertas(TIPO_LOTADO)).isEqualTo(1);

    // Envelhece a janela em vez de dormir uma hora. Determinístico, e é por isso que
    // application-test.yml mantém PT1H igual à produção em vez de encurtar a janela: com PT1S a
    // rajada acima flakaria ao cruzar a borda do segundo.
    jdbcTemplate.update(
        "UPDATE alerta SET janela_inicio = janela_inicio - INTERVAL '1 hour' WHERE tipo = ?",
        TIPO_LOTADO);

    recusar(SLUG, SEGREDO, PONTO_LOTADO);
    drenar();

    assertThat(alertas(TIPO_LOTADO))
        .as("um ponto que continua cheio na hora seguinte é fato novo, e precisa ser dito de novo")
        .isEqualTo(2);
  }

  @Test
  @DisplayName("pontos diferentes na mesma janela não se deduplicam entre si")
  void pontosDiferentesNaMesmaJanelaNaoSeDeduplicam() throws Exception {
    // Deixa o segundo ponto sem vaga também.
    jdbcTemplate.update(
        "UPDATE ponto_custodia SET ocupacao = capacidade WHERE id = ?", OUTRO_PONTO);
    try {
      recusar(SLUG, SEGREDO, PONTO_LOTADO);
      recusar(SLUG, SEGREDO, PONTO_LOTADO);
      recusar(SLUG, SEGREDO, OUTRO_PONTO);
      recusar(SLUG, SEGREDO, OUTRO_PONTO);
      drenar();

      assertThat(alertas(TIPO_LOTADO))
          .as("a chave é (tipo, ponto, janela) — deduplicar só por tipo calaria o segundo ponto")
          .isEqualTo(2);
    } finally {
      jdbcTemplate.update("UPDATE ponto_custodia SET ocupacao = 1 WHERE id = ?", OUTRO_PONTO);
    }
  }

  @Test
  @DisplayName("entrega sem patrocínio deduplica pela transportadora, e não ficou de fora")
  void semPatrocinioDeduplicaPelaTransportadora() throws Exception {
    // Mesmo defeito, mesma forma: save incondicional por evento. Só não apareceu na medição de §6
    // porque a rajada foi contra um ponto cheio, e não contra uma transportadora sem patrocinador.
    // Ponto COM vaga de propósito: a checagem de lotação vem antes e roubaria o desfecho.
    for (int i = 0; i < RAJADA; i++) {
      enviar(SLUG_SEM_PATROCINIO, SEGREDO_SEM_PATROCINIO, PONTO_COM_VAGA, rastreioUnico())
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.desfecho").value("SEM_PATROCINIO"));
    }
    drenar();

    assertThat(alertas(TIPO_SEM_PATROCINIO))
        .as("corrigir só o ponto lotado reabriria a mesma pendência com outro nome")
        .isEqualTo(1);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT referencia FROM alerta WHERE tipo = ?", String.class, TIPO_SEM_PATROCINIO))
        .as("SEM_PATROCINIO colapsa três causas e na primeira não existe patrocinador para citar")
        .isEqualTo(SLUG_SEM_PATROCINIO);
  }

  // ─── Concorrência ───────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("drenadores concorrentes na mesma janela não duplicam o alerta")
  void rajadaConcorrenteNaoDuplica() throws Exception {
    for (int i = 0; i < RAJADA; i++) {
      recusar(SLUG, SEGREDO, PONTO_LOTADO);
    }

    // Dois drenadores ao mesmo tempo é o cenário REAL: buscarPendentesParaPublicar usa SKIP LOCKED
    // justamente para que lotes disjuntos corram em paralelo. Os dois tentam inserir a MESMA linha
    // inexistente, e não há linha para travar antes — quem resolve é o ON CONFLICT, no índice.
    final int drenadores = 4;
    CountDownLatch largada = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(drenadores);
    List<Future<?>> futuros = new ArrayList<>(drenadores);
    AtomicReference<Throwable> falha = new AtomicReference<>();

    for (int i = 0; i < drenadores; i++) {
      futuros.add(
          pool.submit(
              () -> {
                try {
                  largada.await();
                  drenadorOutboxService.drenarLote(RAJADA);
                } catch (Throwable t) {
                  // Throwable, e não Exception: AssertionError NÃO é Exception, e foi exatamente
                  // assim que o teste de concorrência da carta-morta (ADR 0031) passou a não poder
                  // falhar — o erro sumia e a asserção "nenhuma requisição pode falhar" virava
                  // decoração.
                  falha.compareAndSet(null, t);
                }
              }));
    }
    largada.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

    // Cada Future é inspecionado: submit() engole o que a tarefa lançar até alguém chamar get().
    for (Future<?> f : futuros) {
      f.get();
    }
    assertThat(falha.get()).as("nenhum drenador pode falhar").isNull();

    assertThat(alertas(TIPO_LOTADO))
        .as("o ON CONFLICT é o que fecha a corrida entre dois INSERTs da mesma linha inexistente")
        .isEqualTo(1);
    assertThat(eventosComErro()).as("dedup não pode virar falha de despacho na outbox").isEmpty();
  }

  // ─── A outra metade: a frequência sobreviveu? ───────────────────────────────────────────────

  @Test
  @DisplayName("o painel de recusas devolve a contagem real, que o alerta deixou de guardar")
  void consultaDeRecusasDevolveAContagemReal() throws Exception {
    for (int i = 0; i < RAJADA; i++) {
      recusar(SLUG, SEGREDO, PONTO_LOTADO);
    }
    drenar();

    assertThat(alertas(TIPO_LOTADO)).isEqualTo(1);

    mockMvc
        .perform(admin(get("/api/v1/admin/pontos-custodia/recusas")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.conteudo[0].pontoCustodiaId").value(PONTO_LOTADO.toString()))
        .andExpect(jsonPath("$.conteudo[0].motivo").value("PONTO_LOTADO"))
        // É a asserção que impede a correção de virar perda de informação: uma linha de alerta,
        // cinquenta recusas contadas.
        .andExpect(jsonPath("$.conteudo[0].recusas").value(RAJADA))
        .andExpect(jsonPath("$.conteudo[0].capacidade").value(2))
        .andExpect(jsonPath("$.conteudo[0].primeiraRecusa").isNotEmpty())
        .andExpect(jsonPath("$.conteudo[0].ultimaRecusa").isNotEmpty());
  }

  @Test
  @DisplayName("a janela do painel exclui o que é velho demais para ser acionável")
  void janelaDoPainelRecortaPorTempo() throws Exception {
    recusar(SLUG, SEGREDO, PONTO_LOTADO);
    drenar();

    mockMvc
        .perform(admin(get("/api/v1/admin/pontos-custodia/recusas?horas=24")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElementos").value(1));

    jdbcTemplate.update(
        "UPDATE entrega_falida SET recusada_em = recusada_em - INTERVAL '3 days'"
            + " WHERE transportadora = ?",
        SLUG);

    mockMvc
        .perform(admin(get("/api/v1/admin/pontos-custodia/recusas?horas=24")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElementos").value(0));

    mockMvc
        .perform(admin(get("/api/v1/admin/pontos-custodia/recusas?horas=168")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElementos").value(1));
  }

  @Test
  @DisplayName("o painel de recusas é só de ADMIN")
  void painelDeRecusasExigeAdmin() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/pontos-custodia/recusas")
                .header(
                    "Authorization",
                    "Bearer "
                        + JwtTestConfig.gerarTokenValido(ALICE, "alice@omnitribo.dev", "USUARIO")))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(get("/api/v1/admin/pontos-custodia/recusas"))
        .andExpect(status().isUnauthorized());
  }

  // ─── Auxiliares ─────────────────────────────────────────────────────────────────────────────

  private void recusar(String slug, String segredo, UUID ponto) throws Exception {
    enviar(slug, segredo, ponto, rastreioUnico())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.desfecho").value("RECUSADA"));
  }

  private org.springframework.test.web.servlet.ResultActions enviar(
      String slug, String segredo, UUID ponto, String rastreio) throws Exception {
    String corpo = corpo(ponto, rastreio);
    String ts = String.valueOf(Instant.now().getEpochSecond());
    return mockMvc.perform(
        post(URL)
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Transportadora", slug)
            .header("X-Timestamp", ts)
            .header("X-Assinatura", assinar(segredo, ts, corpo))
            .content(corpo));
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder admin(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
    return builder.header(
        "Authorization",
        "Bearer " + JwtTestConfig.gerarTokenValido(ADMIN, "admin@omnitribo.dev", "ADMIN"));
  }

  /** Drena e FALHA se algum evento foi rejeitado — ver {@code EntregaFalidaCicloTest.drenar}. */
  private void drenar() {
    drenadorOutboxService.drenarLote(RAJADA * 2);
    assertThat(eventosComErro()).as("despacho de evento falhou").isEmpty();
  }

  private List<String> eventosComErro() {
    return jdbcTemplate.queryForList(
        "SELECT ultimo_erro FROM outbox WHERE ultimo_erro IS NOT NULL"
            + " AND tipo_evento LIKE 'EntregaFalida%'",
        String.class);
  }

  private int alertas(String tipo) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM alerta WHERE tipo = ?", Integer.class, tipo);
  }

  private int recusasGravadas() {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM entrega_falida WHERE transportadora = ? AND recusada_em IS NOT NULL",
        Integer.class,
        SLUG);
  }

  private int ocupacao(UUID pontoId) {
    return jdbcTemplate.queryForObject(
        "SELECT ocupacao FROM ponto_custodia WHERE id = ?", Integer.class, pontoId);
  }

  /** {@code janelaHoraInicio} fixo — ver a nota em {@code EntregaFalidaCicloTest.corpo}. */
  private static String corpo(UUID pontoCustodiaId, String rastreio) {
    return """
           {"codigoRastreio":"%s","motivo":"Destinatário ausente após 3 tentativas",
            "pontoCustodiaId":"%s","descricaoDoItem":"Caixa de porcelanato 60x60",
            "pesoKg":18.50,"volumeL":42.00,"janelaHoraInicio":10,
            "destinoLat":-23.5695,"destinoLon":-46.6870,
            "cep":"05416000","logradouro":"Rua Teodoro Sampaio","bairro":"Pinheiros",
            "cidade":"São Paulo","uf":"SP"}
           """
        .formatted(rastreio, pontoCustodiaId);
  }

  private static String rastreioUnico() {
    return "AO" + UUID.randomUUID().toString().replace("-", "").substring(0, 18).toUpperCase();
  }

  private static String assinar(String segredo, String timestamp, String corpo) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of()
        .formatHex(mac.doFinal((timestamp + "." + corpo).getBytes(StandardCharsets.UTF_8)));
  }
}
