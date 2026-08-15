package com.omnitribo.logistica.api;

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

/**
 * Borda do "Fim da Entrega Falida": {@code POST /api/v1/webhooks/transportadora}.
 *
 * <p>É o único endpoint de escrita da API sem JWT, então a maior parte destes testes é sobre o que
 * ele RECUSA. Cada caminho de recusa do {@code HmacWebhookFilter} tem um teste próprio, porque
 * todos respondem o mesmo 401 — e um filtro que aceitasse assinatura errada continuaria passando em
 * qualquer teste que só olhasse o caminho feliz.
 *
 * <p><b>A assinatura é calculada aqui, e não por {@code HmacWebhookFilter.calcular}.</b> Duplicação
 * deliberada, pela mesma razão do teste dourado da calculadora duplicar o YAML: um teste que assina
 * chamando o código sob teste concorda com qualquer mudança no esquema de assinatura, inclusive uma
 * que quebre todas as transportadoras integradas. Aqui o esquema — {@code HMAC-SHA256(segredo,
 * timestamp + "." + corpo)}, hex — está escrito à mão e mudá-lo no filtro deixa esta suíte
 * vermelha.
 */
@Import(JwtTestConfig.class)
@DisplayName("Webhook de entrega falida")
class WebhookEntregaFalidaTest extends TesteIntegracaoMvcBase {

  private static final String URL = "/api/v1/webhooks/transportadora";

  /** Configurados em {@code application-test.yml}. */
  private static final String SLUG = "transportadora-teste";

  private static final String SEGREDO = "segredo-de-teste-nao-usar-em-producao";
  private static final String OUTRO_SLUG = "outra-transportadora";
  private static final String OUTRO_SEGREDO = "outro-segredo-de-teste";

  /** LOJA Leroy Merlin Pinheiros — capacidade 50, ocupação 3 no seed. Tem vaga de sobra. */
  private static final UUID PONTO_COM_VAGA =
      UUID.fromString("cccccccc-0000-0000-0000-000000000001");

  /** Portaria Ed. Aurora — capacidade 2, ocupação 2 no seed V904. Lotado por construção. */
  private static final UUID PONTO_LOTADO = UUID.fromString("cccccccc-0000-0000-0000-000000000904");

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  /**
   * Devolve o banco ao estado do seed.
   *
   * <p>O contêiner é singleton para a JVM inteira e NÃO é truncado entre classes, então sujeira
   * aqui vaza para {@code MigracaoTest}, que confere a invariante {@code ocupacao == encomendas
   * fisicamente no ponto}. Limpar só as linhas desta suíte — e não a tabela — é o que permite as
   * duas conviverem em qualquer ordem de execução.
   */
  @AfterEach
  void limpar() {
    List<UUID> missoes =
        jdbcTemplate.queryForList(
            "SELECT missao_id FROM entrega_falida WHERE transportadora IN (?, ?)"
                + " AND missao_id IS NOT NULL",
            UUID.class,
            SLUG,
            OUTRO_SLUG);

    jdbcTemplate.update(
        "DELETE FROM entrega_falida WHERE transportadora IN (?, ?)", SLUG, OUTRO_SLUG);

    for (UUID missaoId : missoes) {
      jdbcTemplate.update("DELETE FROM missao_evento WHERE missao_id = ?", missaoId);
      jdbcTemplate.update("DELETE FROM alerta WHERE missao_id = ?", missaoId);
      jdbcTemplate.update("DELETE FROM missao WHERE id = ?", missaoId);
    }

    // Ocupação volta ao valor do seed. Não é "restaurar backup": são os dois únicos pontos que
    // estes
    // testes tocam, e o número é o do seed, conferido pelo MigracaoTest.
    jdbcTemplate.update("UPDATE ponto_custodia SET ocupacao = 3 WHERE id = ?", PONTO_COM_VAGA);
    jdbcTemplate.update("UPDATE ponto_custodia SET ocupacao = 2 WHERE id = ?", PONTO_LOTADO);
    jdbcTemplate.update("DELETE FROM outbox WHERE tipo_evento LIKE 'EntregaFalida%'");
    jdbcTemplate.update("DELETE FROM alerta WHERE tipo = 'PONTO_CUSTODIA_LOTADO'");
  }

  // ─── Autenticação: tudo que o filtro tem de recusar ─────────────────────────────────────────

  @Test
  @DisplayName("assinatura inválida → 401")
  void assinaturaInvalidaEh401() throws Exception {
    String corpo = corpo(PONTO_COM_VAGA, rastreioUnico());
    String ts = agora();

    mockMvc
        .perform(
            post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Transportadora", SLUG)
                .header("X-Timestamp", ts)
                .header("X-Assinatura", "00".repeat(32)) // hex bem formado, conteúdo errado
                .content(corpo))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/nao-autenticado"));

    assertThat(quantasEntregas()).as("nada foi gravado").isZero();
  }

  @Test
  @DisplayName("corpo alterado depois de assinado → 401")
  void corpoAdulteradoEh401() throws Exception {
    String corpoAssinado = corpo(PONTO_COM_VAGA, rastreioUnico());
    String corpoEnviado = corpo(PONTO_COM_VAGA, rastreioUnico()); // outro rastreio
    String ts = agora();

    // Assina um corpo e envia outro: é exatamente o ataque que o HMAC sobre o CORPO BRUTO existe
    // para pegar, e o que um HMAC sobre o objeto desserializado deixaria passar se a diferença
    // estivesse num campo que o DTO ignora.
    mockMvc
        .perform(
            post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Transportadora", SLUG)
                .header("X-Timestamp", ts)
                .header("X-Assinatura", assinar(SEGREDO, ts, corpoAssinado))
                .content(corpoEnviado))
        .andExpect(status().isUnauthorized());

    assertThat(quantasEntregas()).isZero();
  }

  @Test
  @DisplayName("timestamp fora da janela de 5 min → 401, mesmo com assinatura correta")
  void timestampVelhoEh401() throws Exception {
    String corpo = corpo(PONTO_COM_VAGA, rastreioUnico());
    // 10 minutos atrás: assinatura íntegra, janela vencida. Sem o carimbo DENTRO do material
    // assinado, este seria o replay trivial — capturar uma requisição e reenviá-la depois.
    String ts = String.valueOf(Instant.now().minusSeconds(600).getEpochSecond());

    mockMvc
        .perform(
            post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Transportadora", SLUG)
                .header("X-Timestamp", ts)
                .header("X-Assinatura", assinar(SEGREDO, ts, corpo))
                .content(corpo))
        .andExpect(status().isUnauthorized());

    assertThat(quantasEntregas()).isZero();
  }

  @Test
  @DisplayName("timestamp no FUTURO fora da janela → 401")
  void timestampFuturoEh401() throws Exception {
    String corpo = corpo(PONTO_COM_VAGA, rastreioUnico());
    String ts = String.valueOf(Instant.now().plusSeconds(600).getEpochSecond());

    mockMvc
        .perform(
            post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Transportadora", SLUG)
                .header("X-Timestamp", ts)
                .header("X-Assinatura", assinar(SEGREDO, ts, corpo))
                .content(corpo))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("sem cabeçalho de assinatura → 401")
  void semAssinaturaEh401() throws Exception {
    String corpo = corpo(PONTO_COM_VAGA, rastreioUnico());

    mockMvc
        .perform(
            post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Transportadora", SLUG)
                .header("X-Timestamp", agora())
                .content(corpo))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("transportadora não integrada → 401")
  void transportadoraDesconhecidaEh401() throws Exception {
    String corpo = corpo(PONTO_COM_VAGA, rastreioUnico());
    String ts = agora();

    mockMvc
        .perform(
            post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Transportadora", "transportadora-que-nao-existe")
                .header("X-Timestamp", ts)
                .header("X-Assinatura", assinar(SEGREDO, ts, corpo))
                .content(corpo))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("segredo de OUTRA transportadora → 401")
  void segredoTrocadoEh401() throws Exception {
    String corpo = corpo(PONTO_COM_VAGA, rastreioUnico());
    String ts = agora();

    // Assina com o segredo válido da OUTRA transportadora e se declara como a primeira. Prova que o
    // segredo é por transportadora: um único segredo global aceitaria isto, e qualquer parceiro
    // integrado poderia gravar encomendas em nome de todos os outros.
    mockMvc
        .perform(
            post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Transportadora", SLUG)
                .header("X-Timestamp", ts)
                .header("X-Assinatura", assinar(OUTRO_SEGREDO, ts, corpo))
                .content(corpo))
        .andExpect(status().isUnauthorized());

    assertThat(quantasEntregas()).isZero();
  }

  @Test
  @DisplayName("sem JWT o endpoint continua acessível — quem autentica é o HMAC")
  void naoExigeJwt() throws Exception {
    // Nenhum Authorization: Bearer em nenhum teste desta classe. Este existe para deixar explícito
    // que isso é a regra, e não esquecimento: se alguém remover /api/v1/webhooks/** do permitAll,
    // todos os testes acima passariam a dar 401 pelo motivo ERRADO e ninguém perceberia.
    enviarValido(PONTO_COM_VAGA, rastreioUnico()).andExpect(status().isOk());
  }

  // ─── Processamento ──────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("caminho feliz: missão ABERTA, sem BRL, ocupação +1")
  void caminhoFeliz() throws Exception {
    String rastreio = rastreioUnico();
    int ocupacaoAntes = ocupacao(PONTO_COM_VAGA);

    String json =
        enviarValido(PONTO_COM_VAGA, rastreio)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.desfecho").value("CONVERTIDA"))
            .andExpect(jsonPath("$.replay").value(false))
            .andExpect(jsonPath("$.missaoId").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();

    UUID missaoId = UUID.fromString((String) JSON.readValue(json, Map.class).get("missaoId"));

    Map<String, Object> missao =
        jdbcTemplate.queryForMap("SELECT * FROM missao WHERE id = ?", missaoId);

    assertThat(missao.get("status")).as("nasce ABERTA, não RASCUNHO").isEqualTo("ABERTA");
    assertThat(missao.get("categoria")).isEqualTo("ENTREGA");
    assertThat((java.math.BigDecimal) missao.get("valor_brl"))
        .as("ck_missao_economia e o ADR 0009: nenhuma missão paga em reais")
        .isEqualByComparingTo("0.00");
    assertThat((Integer) missao.get("nivel_minimo"))
        .as("Regra de Elegibilidade por Reputação")
        .isEqualTo(2);
    assertThat((Long) missao.get("tokens_recompensa")).isPositive();
    assertThat((Integer) missao.get("xp_recompensa")).isPositive();
    assertThat(missao.get("versao_formula")).as("recompensa congelada com a versão").isEqualTo(2);
    assertThat(missao.get("ponto_custodia_id")).isEqualTo(PONTO_COM_VAGA);
    assertThat((Long) missao.get("pote_tokens"))
        .as("ENTREGA não exige pote da comunidade — Pendência #1")
        .isZero();

    assertThat(ocupacao(PONTO_COM_VAGA)).isEqualTo(ocupacaoAntes + 1);

    // A trilha existe porque a publicação passou pela MissaoStateMachine, e não por um
    // StatusMissao.ABERTA no construtor.
    Long publicadas =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM missao_evento WHERE missao_id = ? AND tipo = 'PUBLICADA'",
            Long.class,
            missaoId);
    assertThat(publicadas).isEqualTo(1L);

    // O criador é o usuário-sistema da V21 — não há humano no caminho.
    assertThat(missao.get("criador_id"))
        .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"));
  }

  @Test
  @DisplayName("valor ofertado vira TOKEN, nunca valor_brl")
  void valorOfertadoNaoViraReais() throws Exception {
    String semValor = rastreioUnico();
    String comValor = rastreioUnico();

    enviarValido(PONTO_COM_VAGA, semValor).andExpect(status().isOk());
    enviar(SLUG, SEGREDO, corpo(PONTO_COM_VAGA, comValor, "250.00")).andExpect(status().isOk());

    long tokensSem = tokensDaMissaoDe(semValor);
    long tokensCom = tokensDaMissaoDe(comValor);

    assertThat(tokensCom)
        .as("valor ofertado aumenta a recompensa em TOKEN")
        .isGreaterThan(tokensSem);

    // E o valor_brl das duas continua zero: o real ofertado é insumo do cálculo, não pagamento.
    List<java.math.BigDecimal> valores =
        jdbcTemplate.queryForList(
            "SELECT m.valor_brl FROM missao m"
                + " JOIN entrega_falida ef ON ef.missao_id = m.id"
                + " WHERE ef.transportadora = ?",
            java.math.BigDecimal.class,
            SLUG);
    assertThat(valores).isNotEmpty().allSatisfy(v -> assertThat(v).isEqualByComparingTo("0.00"));
  }

  @Test
  @DisplayName("replay: mesmo corpo duas vezes → 200, uma missão só")
  void replayNaoCriaSegundaMissao() throws Exception {
    String rastreio = rastreioUnico();
    int ocupacaoAntes = ocupacao(PONTO_COM_VAGA);

    String primeira =
        enviarValido(PONTO_COM_VAGA, rastreio)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.replay").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();

    String segunda =
        enviarValido(PONTO_COM_VAGA, rastreio)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.replay").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();

    Map<?, ?> a = JSON.readValue(primeira, Map.class);
    Map<?, ?> b = JSON.readValue(segunda, Map.class);
    assertThat(b.get("missaoId")).as("mesma missão, não uma nova").isEqualTo(a.get("missaoId"));
    assertThat(b.get("entregaFalidaId")).isEqualTo(a.get("entregaFalidaId"));

    assertThat(quantasEntregas()).as("uma linha só").isEqualTo(1);
    assertThat(ocupacao(PONTO_COM_VAGA))
        .as("o retry da transportadora não pode consumir uma segunda vaga")
        .isEqualTo(ocupacaoAntes + 1);
  }

  @Test
  @DisplayName("ponto lotado: registra a recusa, não cria missão, não ocupa vaga")
  void pontoLotadoRegistraSemMissao() throws Exception {
    String rastreio = rastreioUnico();

    enviarValido(PONTO_LOTADO, rastreio)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.desfecho").value("RECUSADA"))
        .andExpect(jsonPath("$.missaoId").doesNotExist());

    Map<String, Object> linha =
        jdbcTemplate.queryForMap(
            "SELECT * FROM entrega_falida WHERE transportadora = ? AND codigo_rastreio = ?",
            SLUG,
            rastreio);

    assertThat(linha.get("recusada_em"))
        .as("o fato é gravado — a transportadora tem de saber")
        .isNotNull();
    assertThat(linha.get("missao_id")).isNull();
    assertThat(ocupacao(PONTO_LOTADO))
        .as("recusada não entrou no ponto, logo não ocupa vaga")
        .isEqualTo(2);
  }

  @Test
  @DisplayName("ponto inexistente → 404")
  void pontoInexistenteEh404() throws Exception {
    enviarValido(UUID.fromString("cccccccc-9999-9999-9999-999999999999"), rastreioUnico())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/nao-encontrado"));

    assertThat(quantasEntregas()).isZero();
  }

  @Test
  @DisplayName("peso ausente → 400 com o campo apontado")
  void pesoAusenteEh400() throws Exception {
    String corpo =
        """
        {"codigoRastreio":"%s","motivo":"Destinatário ausente","pontoCustodiaId":"%s",
         "descricaoDoItem":"Caixa de porcelanato 60x60","volumeL":40.0,
         "cep":"05416000","logradouro":"Rua Teodoro Sampaio","bairro":"Pinheiros",
         "cidade":"São Paulo","uf":"SP"}
        """
            .formatted(rastreioUnico(), PONTO_COM_VAGA);

    // Peso e volume são o dado que SÓ a transportadora tem, e a missão de ENTREGA não existe sem
    // eles. Recusar na borda com 400 é melhor que deixar estourar como 500 lá no INSERT.
    enviar(SLUG, SEGREDO, corpo)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/requisicao-invalida"));
  }

  // ─── Auxiliares ─────────────────────────────────────────────────────────────────────────────

  private org.springframework.test.web.servlet.ResultActions enviarValido(
      UUID pontoCustodiaId, String rastreio) throws Exception {
    return enviar(SLUG, SEGREDO, corpo(pontoCustodiaId, rastreio));
  }

  private org.springframework.test.web.servlet.ResultActions enviar(
      String slug, String segredo, String corpo) throws Exception {
    String ts = agora();
    return mockMvc.perform(
        post(URL)
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Transportadora", slug)
            .header("X-Timestamp", ts)
            .header("X-Assinatura", assinar(segredo, ts, corpo))
            .content(corpo));
  }

  private static String corpo(UUID pontoCustodiaId, String rastreio) {
    return corpo(pontoCustodiaId, rastreio, null);
  }

  /** Destino em Pinheiros, a ~1 km do Leroy: a distância entra na recompensa. */
  private static String corpo(UUID pontoCustodiaId, String rastreio, String valorOfertado) {
    String valor = valorOfertado == null ? "" : "\"valorOfertadoBrl\":" + valorOfertado + ",";
    return """
           {"codigoRastreio":"%s","motivo":"Destinatário ausente após 3 tentativas",
            "pontoCustodiaId":"%s","descricaoDoItem":"Caixa de porcelanato 60x60",
            "pesoKg":18.50,"volumeL":42.00,%s
            "destinoLat":-23.5695,"destinoLon":-46.6870,
            "cep":"05416000","logradouro":"Rua Teodoro Sampaio","bairro":"Pinheiros",
            "cidade":"São Paulo","uf":"SP"}
           """
        .formatted(rastreio, pontoCustodiaId, valor);
  }

  private static String agora() {
    return String.valueOf(Instant.now().getEpochSecond());
  }

  private static String rastreioUnico() {
    return "TT" + UUID.randomUUID().toString().replace("-", "").substring(0, 18).toUpperCase();
  }

  /**
   * {@code HMAC-SHA256(segredo, timestamp + "." + corpo)} em hex.
   *
   * <p>Escrito à mão, e não delegado ao filtro — ver o javadoc da classe.
   */
  private static String assinar(String segredo, String timestamp, String corpo) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] material = (timestamp + "." + corpo).getBytes(StandardCharsets.UTF_8);
    return HexFormat.of().formatHex(mac.doFinal(material));
  }

  private int ocupacao(UUID pontoId) {
    return jdbcTemplate.queryForObject(
        "SELECT ocupacao FROM ponto_custodia WHERE id = ?", Integer.class, pontoId);
  }

  private long quantasEntregas() {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM entrega_falida WHERE transportadora IN (?, ?)",
        Long.class,
        SLUG,
        OUTRO_SLUG);
  }

  private long tokensDaMissaoDe(String rastreio) {
    return jdbcTemplate.queryForObject(
        "SELECT m.tokens_recompensa FROM missao m"
            + " JOIN entrega_falida ef ON ef.missao_id = m.id"
            + " WHERE ef.codigo_rastreio = ?",
        Long.class,
        rastreio);
  }
}
