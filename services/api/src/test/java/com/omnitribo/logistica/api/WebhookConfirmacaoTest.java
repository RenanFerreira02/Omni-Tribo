package com.omnitribo.logistica.api;

import static com.omnitribo.carteira.SuporteCarteira.assertLedgerReconcilia;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code POST /api/v1/webhooks/transportadora/confirmacao}.
 *
 * <p>Fecha a lacuna que fazia o executor esperar 72 h para receber: a missão de retirada tem o
 * usuário-sistema como criador, e {@code AtorEsperado.CRIADOR} compara IDENTIDADE — nem um ADMIN
 * conseguia confirmar. Ver ADR 0026.
 *
 * <p><b>A assinatura é calculada aqui, à mão</b>, pela mesma razão de {@code
 * WebhookEntregaFalidaTest}: um teste que assina chamando o código sob teste concorda com qualquer
 * mudança no esquema de assinatura, inclusive uma que quebre todas as transportadoras integradas.
 */
@Import(JwtTestConfig.class)
@DisplayName("Webhook de confirmação de retirada")
class WebhookConfirmacaoTest extends TesteIntegracaoMvcBase {

  private static final String URL_REPORTE = "/api/v1/webhooks/transportadora";
  private static final String URL_CONFIRMACAO = "/api/v1/webhooks/transportadora/confirmacao";

  /** Configurados em {@code application-test.yml}. */
  private static final String SLUG = "transportadora-teste";

  private static final String SEGREDO = "segredo-de-teste-nao-usar-em-producao";

  /** Integrada por HMAC e SEM patrocinador — a V905 a deixa de fora de propósito. */
  private static final String SLUG_SEM_PATROCINIO = "outra-transportadora";

  private static final String SEGREDO_SEM_PATROCINIO = "outro-segredo-de-teste";

  /** LOJA Leroy Merlin Pinheiros — com vaga no seed. */
  private static final UUID PONTO_COM_VAGA =
      UUID.fromString("cccccccc-0000-0000-0000-000000000001");

  /**
   * Seed V904: Tribo Pinheiros, xp 400 → nível 3, acima do nivel-minimo 2 da missão de retirada.
   */
  private static final UUID FERNANDA = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000904");

  /** Carteira do patrocinador de `transportadora-teste`, semeada pela V905 com 5.000 tokens. */
  private static final UUID CARTEIRA_PATROCINADOR =
      UUID.fromString("eeeeeeee-0000-0000-0000-000000000951");

  private static final long SALDO_PATROCINADOR_SEED = 5000L;

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  @AfterEach
  void limpar() {
    List<UUID> missoes =
        jdbcTemplate.queryForList(
            "SELECT missao_id FROM entrega_falida WHERE transportadora IN (?, ?)"
                + " AND missao_id IS NOT NULL",
            UUID.class,
            SLUG,
            SLUG_SEM_PATROCINIO);

    jdbcTemplate.update(
        "DELETE FROM entrega_falida WHERE transportadora IN (?, ?)", SLUG, SLUG_SEM_PATROCINIO);

    for (UUID missaoId : missoes) {
      jdbcTemplate.update("DELETE FROM checkin WHERE missao_id = ?", missaoId);
      jdbcTemplate.update("DELETE FROM missao_evento WHERE missao_id = ?", missaoId);
      jdbcTemplate.update("DELETE FROM alerta WHERE missao_id = ?", missaoId);
      jdbcTemplate.update("DELETE FROM lancamento WHERE missao_id = ?", missaoId);
      jdbcTemplate.update("DELETE FROM missao WHERE id = ?", missaoId);
    }

    jdbcTemplate.update("UPDATE ponto_custodia SET ocupacao = 3 WHERE id = ?", PONTO_COM_VAGA);
    jdbcTemplate.update("DELETE FROM outbox WHERE tipo_evento LIKE 'EntregaFalida%'");
    jdbcTemplate.update("DELETE FROM outbox WHERE tipo_evento = 'MissaoConcluida'");
    jdbcTemplate.update(
        "DELETE FROM alerta WHERE tipo IN ('ENTREGA_FALIDA_DISPONIVEL','MISSAO_CONCLUIDA')");

    // Executor e patrocinador voltam ao seed. Apagar os lançamentos sem restaurar a projeção é
    // exatamente a divergência que assertLedgerReconcilia existe para achar — e ela reprovaria na
    // suíte SEGUINTE, num erro que não aponta para este arquivo.
    jdbcTemplate.update(
        "DELETE FROM lancamento WHERE carteira_id = ? AND motivo = 'FINANCIAMENTO_PATROCINADOR'",
        CARTEIRA_PATROCINADOR);
    jdbcTemplate.update(
        "UPDATE carteira SET saldo_tokens = ?, versao = 1 WHERE id = ?",
        SALDO_PATROCINADOR_SEED,
        CARTEIRA_PATROCINADOR);
    jdbcTemplate.update(
        "DELETE FROM lancamento WHERE carteira_id IN"
            + " (SELECT id FROM carteira WHERE usuario_id = ?)",
        FERNANDA);
    jdbcTemplate.update(
        "UPDATE carteira SET saldo_tokens = 0, versao = 0 WHERE usuario_id = ?", FERNANDA);
    jdbcTemplate.update("UPDATE usuario SET xp = 400, nivel = 3 WHERE id = ?", FERNANDA);
  }

  @Test
  @DisplayName("caminho feliz: confirma, credita o executor e libera a vaga")
  void caminhoFeliz() throws Exception {
    String rastreio = rastreioUnico();
    UUID missaoId = converterEExecutar(rastreio);

    long recompensa =
        jdbcTemplate.queryForObject(
            "SELECT tokens_recompensa FROM missao WHERE id = ?", Long.class, missaoId);
    int ocupacaoAntes = ocupacao();

    confirmar(SLUG, SEGREDO, rastreio)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.missaoId").value(missaoId.toString()))
        .andExpect(jsonPath("$.replay").value(false))
        .andExpect(jsonPath("$.tokensCreditados").value(recompensa));

    assertThat(statusDaMissao(missaoId))
        .as("o único caminho para CONCLUIDA era a varredura de 72 h")
        .isEqualTo("CONCLUIDA");
    assertThat(saldo(FERNANDA)).as("executor pago DO POTE, na hora").isEqualTo(recompensa);
    assertThat(ocupacao()).as("a encomenda saiu da custódia").isEqualTo(ocupacaoAntes - 1);

    assertLedgerReconcilia(jdbcTemplate);
  }

  @Test
  @DisplayName("replay: no-op, sem creditar de novo")
  void replayNaoCreditaDeNovo() throws Exception {
    String rastreio = rastreioUnico();
    UUID missaoId = converterEExecutar(rastreio);

    confirmar(SLUG, SEGREDO, rastreio).andExpect(status().isOk());
    long saldoDepoisDaPrimeira = saldo(FERNANDA);

    // Assinatura e timestamp NOVOS, corpo idêntico: é o retry real de quem não recebeu a resposta.
    confirmar(SLUG, SEGREDO, rastreio)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.replay").value(true))
        .andExpect(jsonPath("$.tokensCreditados").value(0))
        .andExpect(jsonPath("$.missaoId").value(missaoId.toString()));

    assertThat(saldo(FERNANDA))
        .as("segunda confirmação não move dinheiro")
        .isEqualTo(saldoDepoisDaPrimeira);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lancamento WHERE missao_id = ? AND motivo ="
                    + " 'RECOMPENSA_MISSAO'",
                Long.class,
                missaoId))
        .as("um crédito, não dois")
        .isEqualTo(1L);

    assertLedgerReconcilia(jdbcTemplate);
  }

  @Test
  @DisplayName("assinatura inválida → 401, e nada é confirmado")
  void assinaturaInvalidaEh401() throws Exception {
    String rastreio = rastreioUnico();
    UUID missaoId = converterEExecutar(rastreio);

    mockMvc
        .perform(
            post(URL_CONFIRMACAO)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Transportadora", SLUG)
                .header("X-Timestamp", agora())
                .header("X-Assinatura", "00".repeat(32))
                .content(corpoConfirmacao(rastreio)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/nao-autenticado"));

    assertThat(statusDaMissao(missaoId)).isEqualTo("AGUARDANDO_CONFIRMACAO");
    assertThat(saldo(FERNANDA)).isZero();
  }

  @Test
  @DisplayName("uma transportadora não confirma a entrega de outra")
  void transportadoraAlheiaNaoConfirma() throws Exception {
    String rastreio = rastreioUnico();
    UUID missaoId = converterEExecutar(rastreio);

    // Assinatura VÁLIDA, da transportadora errada. A idempotência é por (transportadora, rastreio),
    // então para a outra este código simplesmente não existe — e é 404, não 403: dizer "existe mas
    // não é sua" já entregaria a informação de que aquele rastreio está no sistema.
    confirmar(SLUG_SEM_PATROCINIO, SEGREDO_SEM_PATROCINIO, rastreio)
        .andExpect(status().isNotFound());

    assertThat(statusDaMissao(missaoId)).isEqualTo("AGUARDANDO_CONFIRMACAO");
  }

  @Test
  @DisplayName("rastreio desconhecido → 404")
  void rastreioDesconhecidoEh404() throws Exception {
    confirmar(SLUG, SEGREDO, "NAO-EXISTE-" + UUID.randomUUID())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/nao-encontrado"));
  }

  @Test
  @DisplayName("entrega que nunca virou missão → 404, não 200")
  void entregaSemMissaoEh404() throws Exception {
    // `outra-transportadora` não tem patrocinador (V905), então a conversão recusa e nenhuma missão
    // nasce. Confirmar isso é 404: não há o que concluir, e a recusa já está gravada. Um 200 diria
    // "confirmado" para algo que nunca existiu.
    String rastreio = rastreioUnico();
    enviarReporte(SLUG_SEM_PATROCINIO, SEGREDO_SEM_PATROCINIO, rastreio)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.desfecho").value("SEM_PATROCINIO"));

    confirmar(SLUG_SEM_PATROCINIO, SEGREDO_SEM_PATROCINIO, rastreio)
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("missão sem check-in ainda não aceita CONFIRMAR → 409")
  void missaoEmEstadoInvalidoEh409() throws Exception {
    // Convertida e ABERTA: ninguém aceitou, ninguém executou. CONFIRMAR não cabe neste estado, e o
    // contrato do projeto para isso é 409 — "não cabe aqui, caberia em outro estado".
    String rastreio = rastreioUnico();
    enviarReporte(SLUG, SEGREDO, rastreio).andExpect(status().isOk());

    confirmar(SLUG, SEGREDO, rastreio).andExpect(status().isConflict());
  }

  // ─── Auxiliares ─────────────────────────────────────────────────────────────────────────────

  /** Reporta a falha e leva a missão até AGUARDANDO_CONFIRMACAO, como a transportadora veria. */
  private UUID converterEExecutar(String rastreio) throws Exception {
    String json =
        enviarReporte(SLUG, SEGREDO, rastreio)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.desfecho").value("CONVERTIDA"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID missaoId = UUID.fromString((String) JSON.readValue(json, Map.class).get("missaoId"));

    acao(missaoId, "aceitar");
    acao(missaoId, "iniciar");
    mockMvc
        .perform(
            post("/api/v1/missoes/{id}/checkin", missaoId)
                .header("Authorization", bearer(FERNANDA))
                // O check-in exige Idempotency-Key: a chave gravada é
                // sha256(usuario|missao|chave_do_cliente), nunca a chave crua.
                .header("Idempotency-Key", "confirmacao-teste-" + missaoId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lat\":-23.5640,\"lon\":-46.6934,\"acuraciaM\":8.0,\"mocked\":false}"))
        .andExpect(status().isOk());

    return missaoId;
  }

  private void acao(UUID missaoId, String acao) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/missoes/{id}/" + acao, missaoId)
                .header("Authorization", bearer(FERNANDA)))
        .andExpect(status().isOk());
  }

  private ResultActions enviarReporte(String slug, String segredo, String rastreio)
      throws Exception {
    String corpo =
        """
        {"codigoRastreio":"%s","motivo":"Destinatário ausente após 3 tentativas",
         "pontoCustodiaId":"%s","descricaoDoItem":"Caixa de porcelanato 60x60",
         "pesoKg":18.50,"volumeL":42.00,"janelaHoraInicio":10,
         "destinoLat":-23.5695,"destinoLon":-46.6870,
         "cep":"05416000","logradouro":"Rua Teodoro Sampaio","bairro":"Pinheiros",
         "cidade":"São Paulo","uf":"SP"}
        """
            .formatted(rastreio, PONTO_COM_VAGA);
    return enviar(URL_REPORTE, slug, segredo, corpo);
  }

  private ResultActions confirmar(String slug, String segredo, String rastreio) throws Exception {
    return enviar(URL_CONFIRMACAO, slug, segredo, corpoConfirmacao(rastreio));
  }

  private static String corpoConfirmacao(String rastreio) {
    return "{\"codigoRastreio\":\"%s\"}".formatted(rastreio);
  }

  private ResultActions enviar(String url, String slug, String segredo, String corpo)
      throws Exception {
    String ts = agora();
    return mockMvc.perform(
        post(url)
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Transportadora", slug)
            .header("X-Timestamp", ts)
            .header("X-Assinatura", assinar(segredo, ts, corpo))
            .content(corpo));
  }

  /** {@code HMAC-SHA256(segredo, timestamp + "." + corpo)} em hex, escrito à mão de propósito. */
  private static String assinar(String segredo, String timestamp, String corpo) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] material = (timestamp + "." + corpo).getBytes(StandardCharsets.UTF_8);
    return HexFormat.of().formatHex(mac.doFinal(material));
  }

  private static String agora() {
    return String.valueOf(Instant.now().getEpochSecond());
  }

  private static String rastreioUnico() {
    return "CF" + UUID.randomUUID().toString().replace("-", "").substring(0, 18).toUpperCase();
  }

  private String statusDaMissao(UUID missaoId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM missao WHERE id = ?", String.class, missaoId);
  }

  private long saldo(UUID usuarioId) {
    return jdbcTemplate.queryForObject(
        "SELECT saldo_tokens FROM carteira WHERE usuario_id = ?", Long.class, usuarioId);
  }

  private int ocupacao() {
    return jdbcTemplate.queryForObject(
        "SELECT ocupacao FROM ponto_custodia WHERE id = ?", Integer.class, PONTO_COM_VAGA);
  }

  private String bearer(UUID usuarioId) {
    return "Bearer "
        + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", "USUARIO");
  }
}
