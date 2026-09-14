package com.omnitribo.missoes.api;

import static com.omnitribo.carteira.SuporteCarteira.assertLedgerReconcilia;
import static com.omnitribo.carteira.SuporteCarteira.limparMissao;
import static com.omnitribo.carteira.SuporteCarteira.tokensEmCirculacao;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * O diagnóstico de pote imobilizado, e a coexistência que ele existe para tornar observável.
 *
 * <h2>Por que este teste é a entrega, e não um acessório dela</h2>
 *
 * <p>A pendência que o ADR 0032 fecha não era "falta uma consulta". Era que <b>duas invariantes
 * diferentes vinham sendo lidas como uma</b>: a RECONCILIAÇÃO (saldo de cada carteira == soma do
 * ledger dela) e a CONSERVAÇÃO ({@code SUM(carteiras) + SUM(potes)} ao longo do ciclo). Token preso
 * numa missão parada viola a segunda e deixa a primeira intacta — o lançamento e a projeção do
 * financiamento foram escritos na mesma transação, e continuam batendo.
 *
 * <p>{@link #reconciliacaoContinuaIntegraEnquantoODiagnosticoAcusa} afirma as duas coisas na mesma
 * execução: {@code integro=true} E o diagnóstico acusando. Enquanto ele passar, ninguém pode
 * argumentar que a reconciliação já cobre isto — e se alguém "consertar" o endpoint fazendo {@code
 * integro} virar {@code false} na presença de pote imobilizado, este teste fica vermelho, que é
 * exatamente o que deve acontecer.
 *
 * <h2>Toda assertion é por DELTA ou por id</h2>
 *
 * <p>O banco de teste carrega os seeds V900+ e a V20 backfillou {@code estado_desde} com {@code
 * criada_em}, então a contagem global não é zero e não é estável entre execuções. Afirmar {@code
 * totalElementos == 1} daria um teste que passa hoje e quebra quando alguém acrescentar uma missão
 * ao seed, por um motivo que não tem nada a ver com o que está sob teste.
 */
@Import(JwtTestConfig.class)
@DisplayName("Diagnóstico de pote imobilizado (ADMIN)")
class PoteImobilizadoTest extends TesteIntegracaoMvcBase {

  private static final String MISSOES = "/api/v1/missoes";
  private static final String DIAGNOSTICO = "/api/v1/admin/missoes/potes-imobilizados";
  private static final String RECONCILIACAO = "/api/v1/admin/carteiras/reconciliacao";

  /** Seed V900. */
  private static final UUID ADMIN = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

  private static final long SALDO_INICIAL = 5_000L;

  /**
   * Recuo que garante a missão sob teste como a MAIS ANTIGA da lista, para que ela caia na primeira
   * página independentemente do que os seeds trouxerem. A alternativa — paginar até achar —
   * testaria a paginação em vez do diagnóstico.
   */
  private static final Duration ANTES_DE_TUDO = Duration.ofDays(3650);

  /**
   * Lido da configuração, nunca literal. Um {@code PT96H} escrito aqui faria a suíte concordar com
   * um número em vez de com o sistema, e recalibrar o limiar deixaria o teste verde medindo outra
   * coisa. Mesmo cuidado de {@code OutboxAdminTest} com {@code app.outbox.maximo-tentativas}.
   */
  @Value("${app.missoes.diagnostico.pote-imobilizado-apos}")
  private Duration limiar;

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  private UUID tribo;
  private UUID criador;
  private UUID executor;
  private UUID financiador;
  private final List<UUID> missoesCriadas = new ArrayList<>();

  /** Recompensa da última missão criada, capturada da resposta — derivada pela calculadora. */
  private long recompensa;

  @BeforeEach
  void montarCenario() {
    tribo = criarTribo();
    criador = criarUsuarioComCarteira("criador", tribo, 0L);
    executor = criarUsuarioComCarteira("executor", tribo, 0L);
    // Quem cria a missão NÃO paga (ADR 0009): o pote é formado por OUTRO membro da tribo.
    financiador = criarUsuarioComCarteira("financiador", tribo, SALDO_INICIAL);
  }

  @AfterEach
  void limpar() {
    missoesCriadas.forEach(id -> limparMissao(jdbcTemplate, id));
    missoesCriadas.clear();
    for (UUID usuario : List.of(criador, executor, financiador)) {
      jdbcTemplate.update(
          "DELETE FROM lancamento WHERE carteira_id IN"
              + " (SELECT id FROM carteira WHERE usuario_id = ?)",
          usuario);
      jdbcTemplate.update("DELETE FROM auditoria WHERE ator_id = ?", usuario);
      jdbcTemplate.update("DELETE FROM carteira WHERE usuario_id = ?", usuario);
      jdbcTemplate.update("DELETE FROM usuario WHERE id = ?", usuario);
    }
    jdbcTemplate.update("DELETE FROM tribo WHERE id = ?", tribo);
  }

  // ─── O teste que documenta que as invariantes são distintas ─────────────────────────────────

  @Test
  @DisplayName("a reconciliação segue integro=true enquanto o diagnóstico acusa o pote preso")
  void reconciliacaoContinuaIntegraEnquantoODiagnosticoAcusa() throws Exception {
    JsonNode antes = reconciliar();
    long missoesAntes = antes.get("potesImobilizados").get("missoes").asLong();
    long tokensAntes = antes.get("potesImobilizados").get("tokens").asLong();

    UUID missaoId = missaoParadaEm("EM_ANDAMENTO", ANTES_DE_TUDO);

    JsonNode depois = reconciliar();

    assertThat(depois.get("integro").asBoolean())
        .as(
            "pote imobilizado NÃO é divergência ledger × projeção: o financiamento escreveu "
                + "lançamento e saldo na mesma transação, e as duas somas continuam batendo. "
                + "Se esta assertion ficar vermelha, alguém fundiu duas invariantes diferentes "
                + "num campo só e a reconciliação parou de responder a pergunta dela.")
        .isTrue();
    assertThat(depois.get("divergencias").size()).isZero();

    assertThat(depois.get("potesImobilizados").get("missoes").asLong() - missoesAntes)
        .as("a mesma resposta, no campo ao lado, acusa a missão que a reconciliação não vê")
        .isEqualTo(1L);
    assertThat(depois.get("potesImobilizados").get("tokens").asLong() - tokensAntes)
        .as("e diz QUANTO token está preso — %d, o pote inteiro da missão", recompensa)
        .isEqualTo(recompensa);

    JsonNode linha = primeiraLinhaDoDiagnostico();
    assertThat(linha.get("missaoId").asText()).isEqualTo(missaoId.toString());
    assertThat(linha.get("status").asText()).isEqualTo("EM_ANDAMENTO");
    assertThat(linha.get("poteTokens").asLong()).isEqualTo(recompensa);
    assertThat(linha.get("varreduraCobre").asBoolean())
        .as("EM_ANDAMENTO tem varredura por prazo; estar aqui significa que ela não drenou")
        .isTrue();

    // Roda de propósito depois de tudo: a reconciliação conferida direto no banco, sem passar pelo
    // endpoint que ela deveria poder auditar, também passa. É o mesmo ponto, por outro caminho.
    assertLedgerReconcilia(jdbcTemplate);
  }

  // ─── Controles de ruído: o instrumento não é "conte todos os potes" ─────────────────────────

  @Test
  @DisplayName("pote recém-formado não é imobilizado — o limiar é o que separa custódia de perda")
  void poteRecenteNaoEhImobilizado() throws Exception {
    long antes = totalDeMissoesImobilizadas();

    missaoParadaEm("EM_ANDAMENTO", Duration.ZERO);

    assertThat(totalDeMissoesImobilizadas() - antes)
        .as(
            "toda missão em execução segura um pote; chamar isso de imobilizado transformaria o "
                + "diagnóstico num contador de missões ativas")
        .isZero();
  }

  @Test
  @DisplayName("recuar até o limiar não basta; um instante além dele basta")
  void oLimiarEhOCorteEfetivo() throws Exception {
    long antes = totalDeMissoesImobilizadas();

    UUID missaoId = missaoParadaEm("EM_ANDAMENTO", limiar.minusMinutes(5));
    assertThat(totalDeMissoesImobilizadas() - antes)
        .as("cinco minutos aquém do limiar ainda não é imobilizado")
        .isZero();

    envelhecerEstadoDesde(missaoId, limiar.plusMinutes(5));
    assertThat(totalDeMissoesImobilizadas() - antes).as("cinco minutos além dele é").isEqualTo(1L);
  }

  @Test
  @DisplayName("ABERTA mede por janela_fim: oferta com janela no futuro não é pote imobilizado")
  void abertaComJanelaNoFuturoNaoEhImobilizada() throws Exception {
    long antes = totalDeMissoesImobilizadas();

    UUID missaoId = missaoParadaEm("ABERTA", ANTES_DE_TUDO);

    assertThat(totalDeMissoesImobilizadas() - antes)
        .as(
            "missaoParadaEm recua estado_desde, mas a janela de oferta continua no futuro. Medir "
                + "ABERTA por estado_desde faria toda missão comunitária financiada com prazo "
                + "longo aparecer como imobilizada — o falso positivo seria o caso NORMAL, e um "
                + "instrumento assim não é consultado duas vezes.")
        .isZero();

    // A mesma missão, agora com a janela vencida: é o único fato que muda, e ele basta.
    jdbcTemplate.update(
        "UPDATE missao SET janela_fim = now() - interval '30 days' WHERE id = ?", missaoId);

    assertThat(totalDeMissoesImobilizadas() - antes)
        .as("janela vencida e ninguém aceitou: aí sim o pote está parado")
        .isEqualTo(1L);
  }

  // ─── A lacuna que o instrumento revela, registrada de forma executável ──────────────────────

  @Test
  @DisplayName("RASCUNHO, ACEITA e EM_DISPUTA aparecem com varreduraCobre=false")
  void estadosSemVarreduraAparecemMarcadosComoTal() throws Exception {
    for (String status : List.of("RASCUNHO", "ACEITA", "EM_DISPUTA")) {
      UUID missaoId = missaoParadaEm(status, ANTES_DE_TUDO);

      JsonNode linha = primeiraLinhaDoDiagnostico();
      assertThat(linha.get("missaoId").asText()).isEqualTo(missaoId.toString());
      assertThat(linha.get("status").asText()).isEqualTo(status);
      assertThat(linha.get("varreduraCobre").asBoolean())
          .as(
              "%s retém pote e NENHUMA RegraExpiracao o alcança — a saída depende de um humano "
                  + "específico aparecer. O diagnóstico mostra isso em vez de esconder: a query "
                  + "órfã removida em 2026-08-20 nem olhava para RASCUNHO e ACEITA.",
              status)
          .isFalse();

      // Some da lista para não contaminar a iteração seguinte, que assere sobre a primeira linha.
      jdbcTemplate.update("UPDATE missao SET estado_desde = now() WHERE id = ?", missaoId);
    }
  }

  // ─── O resumo não pode contradizer a lista ──────────────────────────────────────────────────

  @Test
  @DisplayName("o resumo concorda com o total da página — os dois predicados são o mesmo")
  void resumoConcordaComALista() throws Exception {
    missaoParadaEm("EM_ANDAMENTO", ANTES_DE_TUDO);

    JsonNode corpo = diagnostico("");

    assertThat(corpo.get("resumo").get("missoes").asLong())
        .as(
            "contagem e listagem são consultas SEPARADAS com o predicado REPETIDO, em "
                + "MissaoRepository. Divergir seria silencioso: o resumo passaria a contradizer a "
                + "lista na mesma resposta, sem nada falhar. Esta assertion é a única guarda.")
        .isEqualTo(corpo.get("pagina").get("totalElementos").asLong());
    assertThat(corpo.get("resumo").get("limiar").asText())
        .as("o limiar viaja junto: sem ele o número não é interpretável")
        .isEqualTo(limiar.toString());
  }

  // ─── O ciclo fecha: a ação que existe tira a missão da lista ────────────────────────────────

  @Test
  @DisplayName("destravar estorna o pote, tira a missão do diagnóstico e conserva a circulação")
  void destravarTiraAMissaoDoDiagnostico() throws Exception {
    long circulacaoAntes = tokensEmCirculacao(jdbcTemplate);
    long imobilizadasAntes = totalDeMissoesImobilizadas();

    UUID missaoId = missaoParadaEm("EM_ANDAMENTO", ANTES_DE_TUDO);
    assertThat(totalDeMissoesImobilizadas() - imobilizadasAntes).isEqualTo(1L);

    mockMvc
        .perform(
            post(MISSOES + "/{id}/destravar", missaoId)
                .header("Authorization", bearer(ADMIN, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"justificativa\":\"Executor sumiu; liberando o pote da tribo.\"}"))
        .andExpect(status().isOk());

    assertThat(totalDeMissoesImobilizadas() - imobilizadasAntes)
        .as(
            "POST /missoes/{id}/destravar é a ação que JÁ existia (ADR 0015); o que faltava era o "
                + "instrumento que diz sobre qual missão usá-la")
        .isZero();
    assertThat(potePersistido(missaoId)).isZero();
    assertThat(tokensEmCirculacao(jdbcTemplate))
        .as("o estorno devolve o pote a quem financiou: move token de lugar, não cria nem destrói")
        .isEqualTo(circulacaoAntes);
    assertLedgerReconcilia(jdbcTemplate);
  }

  /**
   * Os TRÊS estados que o ADR 0034 acrescentou, e a razão de eles virem juntos num teste só.
   *
   * <p>Antes dele, `varreduraCobre=false` no diagnóstico significava duas coisas ao mesmo tempo:
   * "nenhuma varredura alcança este estado" e "ninguém alcança este estado". Em `RASCUNHO` e
   * `ACEITA` a única saída era o criador ou o executor agir, e se a pessoa desaparecia o pote
   * ficava preso **sem que nem um ADMIN pudesse soltá-lo** — era a lacuna que a implementação do
   * ADR 0032 revelou, medida no banco de demonstração como uma missão `ACEITA` com 42 tokens
   * presos.
   *
   * <p>`EM_DISPUTA` é o caso menos óbvio dos três: ele **tinha** porta de ADMIN (`resolver`), mas
   * ela obriga a julgar o mérito, e há disputa que fica sem informação com as duas partes ausentes.
   * O teste assere que `DESTRAVAR` grava `DESTRAVADA_POR_ADMIN`, e não o tipo de `resolver` — a
   * diferença entre as duas saídas é a trilha, porque o efeito no dinheiro é o mesmo.
   *
   * <p>Parametrizado pelos três estados porque a asserção é idêntica e o que varia é só a origem:
   * um teste por estado repetiria quatro asserções três vezes e esconderia que a propriedade medida
   * é "todo estado não-terminal tem saída de ADMIN", não "este estado específico funciona".
   */
  @ParameterizedTest(name = "destravar solta o pote em {0}")
  @ValueSource(strings = {"RASCUNHO", "ACEITA", "EM_DISPUTA"})
  @DisplayName("ADR 0034: os três estados sem porta ganham saída de ADMIN, com estorno")
  void destravarSoltaOPoteNosTresEstadosNovos(String estado) throws Exception {
    long circulacaoAntes = tokensEmCirculacao(jdbcTemplate);

    // ANTES_DE_TUDO não é necessário aqui — a porta de ADMIN não tem prazo —, mas mantém o cenário
    // idêntico ao do teste acima, para que a única variável entre eles seja o estado de origem.
    UUID missaoId = missaoParadaEm(estado, ANTES_DE_TUDO);
    assertThat(potePersistido(missaoId))
        .as("o cenário só é válido se houver pote preso: sem isso o teste passaria por vácuo")
        .isPositive();

    mockMvc
        .perform(
            post(MISSOES + "/{id}/destravar", missaoId)
                .header("Authorization", bearer(ADMIN, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"justificativa\":\"As duas partes sumiram; liberando o pote.\"}"))
        .andExpect(status().isOk());

    assertThat(statusPersistido(missaoId)).isEqualTo("CANCELADA");
    assertThat(potePersistido(missaoId)).as("pote estornado a quem financiou").isZero();
    assertThat(tokensEmCirculacao(jdbcTemplate))
        .as("CONSERVAÇÃO: o estorno move token de lugar, não cria nem destrói")
        .isEqualTo(circulacaoAntes);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM missao_evento WHERE missao_id = ?"
                    + " AND tipo = 'DESTRAVADA_POR_ADMIN'",
                Long.class,
                missaoId))
        .as("a trilha distingue destravar de resolver, que chega ao mesmo destino")
        .isEqualTo(1L);
    assertLedgerReconcilia(jdbcTemplate);
  }

  /** Nos três estados novos a porta continua sendo SÓ do ADMIN — a extensão não afrouxou nada. */
  @ParameterizedTest(name = "criador não destrava em {0}")
  @ValueSource(strings = {"RASCUNHO", "ACEITA", "EM_DISPUTA"})
  void destravarNosEstadosNovosContinuaExigindoAdmin(String estado) throws Exception {
    UUID missaoId = missaoParadaEm(estado, ANTES_DE_TUDO);

    mockMvc
        .perform(
            post(MISSOES + "/{id}/destravar", missaoId)
                .header("Authorization", bearer(criador, "USUARIO"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"justificativa\":\"Quero cancelar do meu jeito.\"}"))
        .andExpect(status().isForbidden());

    assertThat(statusPersistido(missaoId)).isEqualTo(estado);
  }

  // ─── Autorização e validação de entrada ─────────────────────────────────────────────────────

  @Test
  @DisplayName("sem token é 401")
  void semTokenEh401() throws Exception {
    mockMvc.perform(get(DIAGNOSTICO)).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("usuário comum é 403 e o corpo não traz a lista")
  void naoAdminEh403() throws Exception {
    mockMvc
        .perform(get(DIAGNOSTICO).header("Authorization", bearer(criador, "USUARIO")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.pagina").doesNotExist())
        .andExpect(jsonPath("$.resumo").doesNotExist());
  }

  @Test
  @DisplayName("paginação fora dos limites é 400 — o diagnóstico não lista sem teto")
  void paginacaoInvalidaEh400() throws Exception {
    for (String query : new String[] {"?tamanho=5000", "?tamanho=0", "?pagina=-3"}) {
      mockMvc
          .perform(get(DIAGNOSTICO + query).header("Authorization", bearer(ADMIN, "ADMIN")))
          .andExpect(status().isBadRequest())
          .andExpect(
              jsonPath("$.type").value("https://omnitribo.dev/problemas/requisicao-invalida"));
    }
  }

  // ─── Helpers ────────────────────────────────────────────────────────────────────────────────

  /**
   * Uma missão TRIBO financiada, levada até {@code status} pelo caminho normal da API, com {@code
   * estado_desde} recuado de {@code idade}.
   *
   * <p>O financiamento vem de um TERCEIRO membro da tribo, não do criador: é o desenho do ADR 0009
   * ("quem cria a missão NÃO paga"), e é o que torna o pote real em vez de contábil.
   */
  private UUID missaoParadaEm(String status, Duration idade) throws Exception {
    UUID missaoId = criarMissaoEmRascunho();
    financiar(missaoId, recompensa);

    if (!"RASCUNHO".equals(status)) {
      acao(missaoId, "publicar", criador);
    }
    if (List.of("ACEITA", "EM_ANDAMENTO", "AGUARDANDO_CONFIRMACAO", "EM_DISPUTA")
        .contains(status)) {
      acao(missaoId, "aceitar", executor);
    }
    if (List.of("EM_ANDAMENTO", "AGUARDANDO_CONFIRMACAO", "EM_DISPUTA").contains(status)) {
      acao(missaoId, "iniciar", executor);
    }
    if (List.of("AGUARDANDO_CONFIRMACAO", "EM_DISPUTA").contains(status)) {
      // Salto por SQL, padrão da suíte de carteira: o check-in real tem cobertura própria em
      // CheckinControllerTest, e replicá-lo aqui acoplaria este teste à geolocalização sem
      // acrescentar nada ao que ele mede.
      jdbcTemplate.update(
          "UPDATE missao SET status = 'AGUARDANDO_CONFIRMACAO' WHERE id = ?", missaoId);
    }
    if ("EM_DISPUTA".equals(status)) {
      acao(missaoId, "contestar", criador);
    }

    envelhecerEstadoDesde(missaoId, idade);
    return missaoId;
  }

  /**
   * Recua {@code estado_desde} por SQL. A coluna é mantida por {@code MissaoStateMachine} e não há
   * como envelhecê-la pela API — a alternativa seria um {@code Clock} mutável no contexto, que
   * mudaria o tempo para a aplicação inteira dentro de uma suíte que roda em paralelo.
   */
  private void envelhecerEstadoDesde(UUID missaoId, Duration idade) {
    jdbcTemplate.update(
        "UPDATE missao SET estado_desde = now() - (? * interval '1 second') WHERE id = ?",
        idade.toSeconds(),
        missaoId);
  }

  private long potePersistido(UUID missaoId) {
    return jdbcTemplate.queryForObject(
        "SELECT pote_tokens FROM missao WHERE id = ?", Long.class, missaoId);
  }

  private String statusPersistido(UUID missaoId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM missao WHERE id = ?", String.class, missaoId);
  }

  private long totalDeMissoesImobilizadas() throws Exception {
    return diagnostico("").get("resumo").get("missoes").asLong();
  }

  private JsonNode primeiraLinhaDoDiagnostico() throws Exception {
    JsonNode conteudo = diagnostico("").get("pagina").get("conteudo");
    assertThat(conteudo.size())
        .as("a missão sob teste é a mais antiga, logo a primeira da página")
        .isPositive();
    return conteudo.get(0);
  }

  private JsonNode diagnostico(String query) throws Exception {
    MvcResult resultado =
        mockMvc
            .perform(get(DIAGNOSTICO + query).header("Authorization", bearer(ADMIN, "ADMIN")))
            .andExpect(status().isOk())
            .andReturn();
    return JSON.readTree(resultado.getResponse().getContentAsString());
  }

  private JsonNode reconciliar() throws Exception {
    MvcResult resultado =
        mockMvc
            .perform(get(RECONCILIACAO).header("Authorization", bearer(ADMIN, "ADMIN")))
            .andExpect(status().isOk())
            .andReturn();
    return JSON.readTree(resultado.getResponse().getContentAsString());
  }

  private void acao(UUID missaoId, String acao, UUID ator) throws Exception {
    mockMvc
        .perform(
            post(MISSOES + "/{id}/" + acao, missaoId)
                .header("Authorization", bearer(ator, "USUARIO")))
        .andExpect(status().isOk());
  }

  private void financiar(UUID missaoId, long tokens) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/tribos/{triboId}/financiamentos", tribo)
                .header("Authorization", bearer(financiador, "USUARIO"))
                .header("Idempotency-Key", "imobilizado-" + missaoId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"missaoId\":\"%s\",\"tokens\":%d}".formatted(missaoId, tokens)))
        .andExpect(status().isCreated());
  }

  private UUID criarMissaoEmRascunho() throws Exception {
    Instant inicio = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    String corpo =
        """
        {
          "categoria": "TRIBO",
          "titulo": "Mutirão para medir pote imobilizado",
          "descricao": "Missão comunitária financiada por um vizinho, para o diagnóstico.",
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
            .formatted(inicio, inicio.plus(30, ChronoUnit.DAYS));

    MvcResult criacao =
        mockMvc
            .perform(
                post(MISSOES)
                    .header("Authorization", bearer(criador, "USUARIO"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(corpo))
            .andExpect(status().isCreated())
            .andReturn();

    JsonNode criada = JSON.readTree(criacao.getResponse().getContentAsString());
    recompensa = criada.get("tokensRecompensa").asLong();
    UUID id = UUID.fromString(criada.get("id").asText());
    missoesCriadas.add(id);
    return id;
  }

  private UUID criarTribo() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO tribo (id, nome, bairro, criada_em) VALUES (?, ?, ?, NOW())",
        id,
        "Tribo Imobilizada " + id.toString().substring(0, 8),
        "Bairro Imobilizado");
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
      // Sem este lançamento de abertura o assertLedgerReconcilia falharia de saída: o saldo
      // materializado existiria sem linha correspondente no razão.
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

  private String bearer(UUID usuarioId, String papel) {
    return "Bearer " + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", papel);
  }
}
