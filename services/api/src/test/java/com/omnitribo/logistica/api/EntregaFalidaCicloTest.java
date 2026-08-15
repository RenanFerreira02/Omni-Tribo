package com.omnitribo.logistica.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import com.omnitribo.compartilhado.dominio.DrenadorOutboxService;
import com.omnitribo.missoes.dominio.MissaoService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Ciclo do "Fim da Entrega Falida" depois da borda: notificação, trava de reputação, baixa da
 * custódia e a disputa pela última vaga.
 *
 * <p>Separado de {@code WebhookEntregaFalidaTest} de propósito: lá o assunto é o que o filtro HMAC
 * recusa; aqui é o que o domínio faz com o que passou.
 */
@Import(JwtTestConfig.class)
@DisplayName("Ciclo da entrega falida")
class EntregaFalidaCicloTest extends TesteIntegracaoMvcBase {

  private static final String URL = "/api/v1/webhooks/transportadora";
  private static final String SLUG = "transportadora-teste";
  private static final String SEGREDO = "segredo-de-teste-nao-usar-em-producao";

  /** Leroy Merlin Pinheiros — capacidade 50, ocupação 3. Tribo Pinheiros. */
  private static final UUID PONTO_COM_VAGA =
      UUID.fromString("cccccccc-0000-0000-0000-000000000001");

  /** Portaria Ed. Aurora — capacidade 2. O teste de concorrência a deixa com UMA vaga. */
  private static final UUID PONTO_AURORA = UUID.fromString("cccccccc-0000-0000-0000-000000000904");

  /** Seed V904: Tribo Pinheiros, xp 400 → nível 3, com NOTIFICACAO e LOCALIZACAO concedidos. */
  private static final UUID FERNANDA = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000904");

  /** Seed V904: mesma tribo, mesmos consentimentos, xp 0 → nível 1. Abaixo do mínimo. */
  private static final UUID GUSTAVO = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000905");

  /** Seed V902: Tribo Pinheiros, mas REVOGOU NOTIFICACAO. */
  private static final UUID ALICE = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

  private static final String TIPO_ALERTA = "ENTREGA_FALIDA_DISPONIVEL";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired DrenadorOutboxService drenadorOutboxService;
  @Autowired MissaoService missaoService;
  @Autowired com.omnitribo.compartilhado.api.ConsultasGeoespaciais consultasGeoespaciais;
  @Autowired com.omnitribo.identidade.api.ConsultaConsentimento consultaConsentimento;

  @AfterEach
  void limpar() {
    List<UUID> missoes =
        jdbcTemplate.queryForList(
            "SELECT missao_id FROM entrega_falida WHERE transportadora = ? AND missao_id IS NOT NULL",
            UUID.class,
            SLUG);
    jdbcTemplate.update("DELETE FROM entrega_falida WHERE transportadora = ?", SLUG);
    for (UUID missaoId : missoes) {
      jdbcTemplate.update("DELETE FROM checkin WHERE missao_id = ?", missaoId);
      jdbcTemplate.update("DELETE FROM missao_evento WHERE missao_id = ?", missaoId);
      jdbcTemplate.update("DELETE FROM alerta WHERE missao_id = ?", missaoId);
      jdbcTemplate.update("DELETE FROM lancamento WHERE missao_id = ?", missaoId);
      jdbcTemplate.update("DELETE FROM missao WHERE id = ?", missaoId);
    }
    jdbcTemplate.update(
        "DELETE FROM alerta WHERE tipo IN (?, 'PONTO_CUSTODIA_LOTADO')", TIPO_ALERTA);
    jdbcTemplate.update("DELETE FROM outbox WHERE tipo_evento LIKE 'EntregaFalida%'");
    jdbcTemplate.update("UPDATE ponto_custodia SET ocupacao = 3 WHERE id = ?", PONTO_COM_VAGA);
    jdbcTemplate.update("UPDATE ponto_custodia SET ocupacao = 2 WHERE id = ?", PONTO_AURORA);
    // Saldos e XP que a conclusão possa ter mexido voltam ao seed.
    jdbcTemplate.update(
        "UPDATE carteira SET saldo_tokens = 0, versao = 0 WHERE usuario_id IN (?, ?)",
        FERNANDA,
        GUSTAVO);
    jdbcTemplate.update("UPDATE usuario SET xp = 400, nivel = 3 WHERE id = ?", FERNANDA);
    jdbcTemplate.update("UPDATE usuario SET xp = 0, nivel = 1 WHERE id = ?", GUSTAVO);
  }

  // ─── As duas portas que sustentam o fan-out ─────────────────────────────────────────────────

  @Test
  @DisplayName("tribosNoRaio encontra a tribo do ponto de custódia")
  void tribosNoRaioEncontraATribo() {
    var proximas =
        consultasGeoespaciais.tribosNoRaio(
            new java.math.BigDecimal("-23.5640"), new java.math.BigDecimal("-46.6934"), 3000, 50);

    assertThat(proximas)
        .as("o centro derivado da Tribo Pinheiros tem de cair no raio do Leroy Merlin Pinheiros")
        .extracting(com.omnitribo.compartilhado.api.ConsultasGeoespaciais.AlvoProximo::id)
        .contains(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"));
  }

  @Test
  @DisplayName("usuariosComConsentimento exige os DOIS consentimentos vigentes")
  void consultaDeConsentimentoResolveEstadoAtual() {
    List<UUID> comAmbos =
        consultaConsentimento.usuariosComConsentimento(
            List.of(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")),
            List.of("NOTIFICACAO", "LOCALIZACAO"));

    assertThat(comAmbos).as("Fernanda e Gustavo concederam os dois").contains(FERNANDA, GUSTAVO);
    assertThat(comAmbos)
        .as("Alice concedeu NOTIFICACAO e REVOGOU depois — a linha antiga continua na tabela")
        .doesNotContain(ALICE);
  }

  // ─── Notificação ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("notifica quem consentiu e tem nível; ignora quem revogou e quem não tem reputação")
  void notificaSomenteQuemPodeEQuer() throws Exception {
    UUID missaoId = converter(PONTO_COM_VAGA);
    drenar();

    assertThat(alertasDe(FERNANDA, missaoId))
        .as("consentiu NOTIFICACAO e LOCALIZACAO, nível 3 ≥ 2")
        .isEqualTo(1);

    assertThat(alertasDe(ALICE, missaoId))
        .as(
            "revogou NOTIFICACAO — a linha antiga de concessão continua na tabela append-only,"
                + " e uma consulta ingênua por concedido=true a notificaria")
        .isZero();

    assertThat(alertasDe(GUSTAVO, missaoId))
        .as("consentiu tudo, mas nível 1 < 2: anunciar levaria a um 422 no aceite")
        .isZero();
  }

  @Test
  @DisplayName("redespacho da outbox não duplica alerta")
  void redespachoNaoDuplica() throws Exception {
    UUID missaoId = converter(PONTO_COM_VAGA);
    drenar();
    assertThat(alertasDe(FERNANDA, missaoId)).isEqualTo(1);

    // Simula o at-least-once: a linha volta a pendente e é drenada de novo. Sem a checagem de
    // existência por (usuario, tipo, missao), o usuário receberia o alerta duas vezes E o
    // duplicado consumiria o teto por hora — uma falha transitória de infra silenciaria
    // notificações legítimas pela hora seguinte.
    jdbcTemplate.update(
        "UPDATE outbox SET publicado_em = NULL, tentativas = 0, proxima_tentativa_em = NOW()"
            + " WHERE tipo_evento = 'EntregaFalidaConvertida'");
    drenar();

    assertThat(alertasDe(FERNANDA, missaoId)).as("continua sendo um só").isEqualTo(1);
  }

  @Test
  @DisplayName("teto por hora corta o excesso")
  void tetoPorHoraCortaOExcesso() throws Exception {
    // app.notificacoes.alertas-por-hora = 5 em application-test.yml. Cinco alertas recentes de
    // qualquer tipo já consomem a cota.
    for (int i = 0; i < 5; i++) {
      jdbcTemplate.update(
          "INSERT INTO alerta (id, usuario_id, tipo, titulo, corpo, lido, criado_em)"
              + " VALUES (?, ?, 'RUIDO', 'x', 'y', FALSE, NOW())",
          UUID.randomUUID(),
          FERNANDA);
    }

    UUID missaoId = converter(PONTO_COM_VAGA);
    drenar();

    assertThat(alertasDe(FERNANDA, missaoId))
        .as("cota da hora esgotada: excesso de notificação destrói o canal que ela usa")
        .isZero();

    jdbcTemplate.update("DELETE FROM alerta WHERE tipo = 'RUIDO'");
  }

  // ─── Trava de reputação ─────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("aceite abaixo do nível mínimo → 422 com os números na resposta")
  void aceiteAbaixoDoNivelEh422() throws Exception {
    UUID missaoId = converter(PONTO_COM_VAGA);

    mockMvc
        .perform(autenticado(post("/api/v1/missoes/" + missaoId + "/aceitar"), GUSTAVO))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/nivel-insuficiente"))
        // Os números vêm como extensão, e não embutidos no detail: a tela monta a frase, e ler
        // número de dentro de texto acoplaria a UI à revisão de copy do servidor.
        .andExpect(jsonPath("$.nivelExigido").value(2))
        .andExpect(jsonPath("$.nivelAtual").value(1));

    assertThat(statusDaMissao(missaoId))
        .as("continua disponível para quem pode")
        .isEqualTo("ABERTA");
  }

  @Test
  @DisplayName("aceite com nível suficiente passa")
  void aceiteComNivelSuficientePassa() throws Exception {
    UUID missaoId = converter(PONTO_COM_VAGA);

    mockMvc
        .perform(autenticado(post("/api/v1/missoes/" + missaoId + "/aceitar"), FERNANDA))
        .andExpect(status().isOk());

    assertThat(statusDaMissao(missaoId)).isEqualTo("ACEITA");
  }

  // ─── Baixa da custódia ──────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("conclusão libera a vaga e carimba a saída da encomenda")
  void conclusaoDaBaixaNaCustodia() throws Exception {
    int ocupacaoInicial = ocupacao(PONTO_COM_VAGA);
    UUID missaoId = converter(PONTO_COM_VAGA);
    assertThat(ocupacao(PONTO_COM_VAGA)).isEqualTo(ocupacaoInicial + 1);

    mockMvc
        .perform(autenticado(post("/api/v1/missoes/" + missaoId + "/aceitar"), FERNANDA))
        .andExpect(status().isOk());
    mockMvc
        .perform(autenticado(post("/api/v1/missoes/" + missaoId + "/iniciar"), FERNANDA))
        .andExpect(status().isOk());
    checkin(missaoId, FERNANDA);

    // O criador desta missão é o usuário-sistema, que tem status INATIVO e NUNCA autentica — logo
    // CONFIRMAR, que exige AtorEsperado.CRIADOR, é inalcançável por requisição HTTP. A saída
    // projetada para esse caso é a varredura de prazo, que conclui PAGANDO o executor porque o
    // check-in geolocalizado é a evidência. Chamada direta, como ExpiracaoMissoesServiceTest faz.
    missaoService.concluirPorOmissaoDoCriador(missaoId, Instant.now());

    assertThat(statusDaMissao(missaoId)).isEqualTo("CONCLUIDA");
    assertThat(ocupacao(PONTO_COM_VAGA))
        .as("a encomenda saiu da custódia: a vaga volta a existir")
        .isEqualTo(ocupacaoInicial);

    Map<String, Object> entrega =
        jdbcTemplate.queryForMap("SELECT * FROM entrega_falida WHERE missao_id = ?", missaoId);
    assertThat(entrega.get("convertida_em")).as("carimbo da saída da custódia").isNotNull();

    Long tokens =
        jdbcTemplate.queryForObject(
            "SELECT saldo_tokens FROM carteira WHERE usuario_id = ?", Long.class, FERNANDA);
    assertThat(tokens).as("CONCLUIDA é o único estado que credita").isPositive();
  }

  // ─── Concorrência pela última vaga ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("20 webhooks simultâneos numa vaga: exatamente um converte")
  void disputaPelaUltimaVaga() throws Exception {
    final int threads = 20;
    // Capacidade 2, ocupação 1 → exatamente UMA vaga.
    jdbcTemplate.update("UPDATE ponto_custodia SET ocupacao = 1 WHERE id = ?", PONTO_AURORA);

    CountDownLatch largada = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    List<Future<String>> respostas = new java.util.ArrayList<>(threads);
    for (int i = 0; i < threads; i++) {
      respostas.add(pool.submit(enviarAoSinal(largada)));
    }
    largada.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

    long convertidas =
        respostas.stream().map(EntregaFalidaCicloTest::valor).filter("CONVERTIDA"::equals).count();
    long recusadas =
        respostas.stream().map(EntregaFalidaCicloTest::valor).filter("RECUSADA"::equals).count();
    long erros =
        respostas.stream().map(EntregaFalidaCicloTest::valor).filter("ERRO"::equals).count();

    assertThat(erros).as("nenhuma exceção de infraestrutura pode vazar").isZero();
    assertThat(convertidas).as("uma vaga, uma conversão").isEqualTo(1);
    assertThat(recusadas).isEqualTo(threads - 1L);

    assertThat(ocupacao(PONTO_AURORA))
        .as("sem o FOR UPDATE no ponto, N threads leem a mesma ocupação e todas incrementam")
        .isEqualTo(2);

    // As 19 recusadas também estão gravadas: a transportadora precisa saber de cada uma.
    Long linhas =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM entrega_falida WHERE ponto_custodia_id = ?"
                + " AND transportadora = ? AND recusada_em IS NOT NULL",
            Long.class,
            PONTO_AURORA,
            SLUG);
    assertThat(linhas).isEqualTo(threads - 1L);
  }

  private Callable<String> enviarAoSinal(CountDownLatch largada) {
    return () -> {
      try {
        largada.await();
        String json =
            enviar(PONTO_AURORA, rastreioUnico()).andReturn().getResponse().getContentAsString();
        return String.valueOf(JSON.readValue(json, Map.class).get("desfecho"));
      } catch (Exception e) {
        return "ERRO";
      }
    };
  }

  private static String valor(Future<String> f) {
    try {
      return f.get();
    } catch (Exception e) {
      return "ERRO";
    }
  }

  // ─── Auxiliares ─────────────────────────────────────────────────────────────────────────────

  /** Dispara o webhook e devolve o id da missão criada. */
  private UUID converter(UUID pontoCustodiaId) throws Exception {
    String json =
        enviar(pontoCustodiaId, rastreioUnico())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.desfecho").value("CONVERTIDA"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString((String) JSON.readValue(json, Map.class).get("missaoId"));
  }

  /**
   * Drena a outbox e FALHA se algum evento foi rejeitado.
   *
   * <p>{@code drenarLote} tem try/catch por evento — um payload envenenado não pode derrubar o lote
   * inteiro —, então um despachante quebrado deixa o teste silenciosamente sem alertas e a asserção
   * seguinte acusa "esperava 1, veio 0", que não diz nada sobre a causa. Ler {@code ultimo_erro}
   * transforma isso na mensagem real.
   */
  private void drenar() {
    drenadorOutboxService.drenarLote(50);
    List<String> erros =
        jdbcTemplate.queryForList(
            "SELECT ultimo_erro FROM outbox WHERE ultimo_erro IS NOT NULL"
                + " AND tipo_evento LIKE 'EntregaFalida%'",
            String.class);
    assertThat(erros).as("despacho de evento falhou").isEmpty();
  }

  private void checkin(UUID missaoId, UUID executor) throws Exception {
    // Coordenada do Leroy Merlin Pinheiros — o check-in valida contra missao.origem, que nesta
    // missão é o ponto de custódia (a coordenada que é NOSSA, e não a que a transportadora mandou).
    String corpo =
        """
        {"lat":-23.5640,"lon":-46.6934,"acuraciaM":10.0,"mockedOuFalso":false}
        """;
    mockMvc
        .perform(
            autenticado(post("/api/v1/missoes/" + missaoId + "/checkin"), executor)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content(corpo))
        .andExpect(status().isOk());
  }

  private MockHttpServletRequestBuilder autenticado(
      MockHttpServletRequestBuilder builder, UUID usuarioId) {
    return builder.header(
        "Authorization",
        "Bearer " + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", "USUARIO"));
  }

  private org.springframework.test.web.servlet.ResultActions enviar(UUID ponto, String rastreio)
      throws Exception {
    String corpo = corpo(ponto, rastreio);
    String ts = String.valueOf(Instant.now().getEpochSecond());
    return mockMvc.perform(
        post(URL)
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Transportadora", SLUG)
            .header("X-Timestamp", ts)
            .header("X-Assinatura", assinar(SEGREDO, ts, corpo))
            .content(corpo));
  }

  private static String corpo(UUID pontoCustodiaId, String rastreio) {
    return """
           {"codigoRastreio":"%s","motivo":"Destinatário ausente após 3 tentativas",
            "pontoCustodiaId":"%s","descricaoDoItem":"Caixa de porcelanato 60x60",
            "pesoKg":18.50,"volumeL":42.00,
            "destinoLat":-23.5695,"destinoLon":-46.6870,
            "cep":"05416000","logradouro":"Rua Teodoro Sampaio","bairro":"Pinheiros",
            "cidade":"São Paulo","uf":"SP"}
           """
        .formatted(rastreio, pontoCustodiaId);
  }

  private static String rastreioUnico() {
    return "TT" + UUID.randomUUID().toString().replace("-", "").substring(0, 18).toUpperCase();
  }

  private static String assinar(String segredo, String timestamp, String corpo) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of()
        .formatHex(mac.doFinal((timestamp + "." + corpo).getBytes(StandardCharsets.UTF_8)));
  }

  private int ocupacao(UUID pontoId) {
    return jdbcTemplate.queryForObject(
        "SELECT ocupacao FROM ponto_custodia WHERE id = ?", Integer.class, pontoId);
  }

  private String statusDaMissao(UUID missaoId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM missao WHERE id = ?", String.class, missaoId);
  }

  private int alertasDe(UUID usuarioId, UUID missaoId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM alerta WHERE usuario_id = ? AND tipo = ? AND missao_id = ?",
        Integer.class,
        usuarioId,
        TIPO_ALERTA,
        missaoId);
  }
}
