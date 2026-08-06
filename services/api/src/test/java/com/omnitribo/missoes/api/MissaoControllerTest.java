package com.omnitribo.missoes.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Contrato HTTP do módulo de missões.
 *
 * <p>Não repete a matriz de 99 transições — essa é responsabilidade de MissaoStateMachineTest, que
 * roda sem Spring. Aqui verificamos o que só o HTTP prova: códigos de status, formato
 * ProblemDetail, ordem das checagens de erro, visibilidade e paginação.
 */
@Import(JwtTestConfig.class)
class MissaoControllerTest extends TesteIntegracaoMvcBase {

  private static final UUID ADMIN_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
  private static final UUID ALICE_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
  private static final UUID BOB_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000003");
  private static final UUID CAROL_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000004");

  private static final String BASE = "/api/v1/missoes";

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;

  // ─── Caminho feliz ─────────────────────────────────────────────────────────────────────────

  @Test
  void cicloCompleto_criarPublicarAceitarIniciar_ateOStubDeCheckin() throws Exception {
    UUID missaoId = criarMissao(ALICE_ID, corpoEntregaValido());

    // Nasce em RASCUNHO — nunca no estado que o cliente pedir.
    mockMvc
        .perform(get(BASE + "/{id}", missaoId).header("Authorization", bearer(ALICE_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RASCUNHO"))
        .andExpect(jsonPath("$.criadorId").value(ALICE_ID.toString()))
        .andExpect(jsonPath("$.executorId").doesNotExist());

    mockMvc
        .perform(post(BASE + "/{id}/publicar", missaoId).header("Authorization", bearer(ALICE_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ABERTA"));

    // Bob aceita: vira executor, mas NADA é creditado — crédito só existe em CONCLUIDA.
    mockMvc
        .perform(post(BASE + "/{id}/aceitar", missaoId).header("Authorization", bearer(BOB_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACEITA"))
        .andExpect(jsonPath("$.executorId").value(BOB_ID.toString()))
        .andExpect(jsonPath("$.aceitaEm").exists())
        .andExpect(jsonPath("$.concluidaEm").doesNotExist());

    mockMvc
        .perform(post(BASE + "/{id}/iniciar", missaoId).header("Authorization", bearer(BOB_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("EM_ANDAMENTO"));

    // Check-in é contrato publicado sem implementação: 501, não 500.
    mockMvc
        .perform(
            post(BASE + "/{id}/checkin", missaoId)
                .header("Authorization", bearer(BOB_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lat\":-23.5629,\"lon\":-46.6996,\"acuraciaM\":8.0}"))
        .andExpect(status().isNotImplemented())
        .andExpect(
            jsonPath("$.detail").value("Funcionalidade ainda não disponível nesta versão da API."));
  }

  @Test
  void desistenciaDevolveMissaoAoPoolSemExecutor() throws Exception {
    UUID missaoId = criarPublicarEAceitar();

    mockMvc
        .perform(
            post(BASE + "/{id}/desistir", missaoId)
                .header("Authorization", bearer(BOB_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"motivo\":\"Imprevisto no trabalho\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ABERTA"))
        .andExpect(jsonPath("$.executorId").doesNotExist());
  }

  @Test
  void cancelarSemCorpoNaoQuebra() throws Exception {
    // @RequestBody(required = false): POST sem corpo não pode virar NPE → 500.
    UUID missaoId = criarEPublicar();

    mockMvc
        .perform(post(BASE + "/{id}/cancelar", missaoId).header("Authorization", bearer(ALICE_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELADA"));
  }

  // ─── 409: transição inválida ───────────────────────────────────────────────────────────────

  @Test
  void aceitarRascunhoDaConflito() throws Exception {
    UUID missaoId = criarMissao(ALICE_ID, corpoEntregaValido());

    // Bob não vê o rascunho na listagem, mas aceitar precisa responder 409 e não 404:
    // o endpoint de transição carrega com lock, sem a regra de visibilidade de leitura.
    mockMvc
        .perform(post(BASE + "/{id}/aceitar", missaoId).header("Authorization", bearer(BOB_ID)))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.detail").value("Esta operação não é permitida no estado atual da missão."));
  }

  @Test
  void iniciarSemTerAceitadoDaConflitoOuAcessoNegado() throws Exception {
    UUID missaoId = criarEPublicar();

    // Missão ABERTA não tem executor: INICIAR exige EXECUTOR, então a autorização falha antes
    // da transição — 403, e nunca 200.
    mockMvc
        .perform(post(BASE + "/{id}/iniciar", missaoId).header("Authorization", bearer(BOB_ID)))
        .andExpect(status().isForbidden());
  }

  @Test
  void publicarDuasVezesDaConflito() throws Exception {
    UUID missaoId = criarEPublicar();

    mockMvc
        .perform(post(BASE + "/{id}/publicar", missaoId).header("Authorization", bearer(ALICE_ID)))
        .andExpect(status().isConflict());
  }

  // ─── 403: ator errado ──────────────────────────────────────────────────────────────────────

  @Test
  void cancelarComoNaoCriadorDaAcessoNegado() throws Exception {
    UUID missaoId = criarEPublicar();

    mockMvc
        .perform(post(BASE + "/{id}/cancelar", missaoId).header("Authorization", bearer(BOB_ID)))
        .andExpect(status().isForbidden())
        .andExpect(
            jsonPath("$.detail").value("Você não tem permissão para esta operação nesta missão."));
  }

  @Test
  void iniciarComoNaoExecutorDaAcessoNegado() throws Exception {
    UUID missaoId = criarPublicarEAceitar();

    // Carol não é a executora (Bob é).
    mockMvc
        .perform(post(BASE + "/{id}/iniciar", missaoId).header("Authorization", bearer(CAROL_ID)))
        .andExpect(status().isForbidden());
  }

  @Test
  void criadorNaoPodeAceitarAPropriaMissao() throws Exception {
    UUID missaoId = criarEPublicar();

    mockMvc
        .perform(post(BASE + "/{id}/aceitar", missaoId).header("Authorization", bearer(ALICE_ID)))
        .andExpect(status().isForbidden());
  }

  @Test
  void editarComoNaoCriadorDaAcessoNegado() throws Exception {
    UUID missaoId = criarEPublicar();

    mockMvc
        .perform(
            patch(BASE + "/{id}", missaoId)
                .header("Authorization", bearer(BOB_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"titulo\":\"Titulo sequestrado\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void resolverDisputaComoUsuarioComumDa403ENao500() throws Exception {
    // Regressão do risco mais perigoso desta fase: AccessDeniedException lançada pelo
    // @PreAuthorize é resolvida pelo DispatcherServlet, e sem handler explícito o
    // @ExceptionHandler(Exception.class) a transformaria num 500.
    UUID missaoId = criarEPublicar();

    mockMvc
        .perform(
            post(BASE + "/{id}/resolver", missaoId)
                .header("Authorization", bearer(BOB_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resultado\":\"CONCLUIR\",\"justificativa\":\"Entrega comprovada\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("Acesso negado"));
  }

  @Test
  void semTokenDa401() throws Exception {
    mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
  }

  // ─── Ordem 403 → 409 → 501 nos stubs ───────────────────────────────────────────────────────

  @Test
  void stubConfirmarValidaAutorizacaoDepoisTransicaoDepoisResponde501() throws Exception {
    UUID missaoId = criarEPublicar();

    // (1) ator errado numa missão ABERTA → 403, sem revelar o estado
    mockMvc
        .perform(post(BASE + "/{id}/confirmar", missaoId).header("Authorization", bearer(BOB_ID)))
        .andExpect(status().isForbidden());

    // (2) criador correto, mas estado errado → 409
    mockMvc
        .perform(post(BASE + "/{id}/confirmar", missaoId).header("Authorization", bearer(ALICE_ID)))
        .andExpect(status().isConflict());

    // (3) criador correto e estado correto → 501, porque o crédito em carteira é F7
    UUID aguardando = criarMissaoEm("AGUARDANDO_CONFIRMACAO");
    mockMvc
        .perform(
            post(BASE + "/{id}/confirmar", aguardando).header("Authorization", bearer(ALICE_ID)))
        .andExpect(status().isNotImplemented());
  }

  @Test
  void stubResolverComAdminEmDisputaResponde501() throws Exception {
    UUID emDisputa = criarMissaoEm("EM_DISPUTA");

    mockMvc
        .perform(
            post(BASE + "/{id}/resolver", emDisputa)
                .header("Authorization", bearer(ADMIN_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"resultado\":\"CONCLUIR\",\"justificativa\":\"Comprovantes conferem\"}"))
        .andExpect(status().isNotImplemented());
  }

  @Test
  void stubResolverComAdminEmEstadoErradoDaConflito() throws Exception {
    UUID missaoId = criarEPublicar();

    mockMvc
        .perform(
            post(BASE + "/{id}/resolver", missaoId)
                .header("Authorization", bearer(ADMIN_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resultado\":\"CANCELAR\",\"justificativa\":\"Sem disputa aberta\"}"))
        .andExpect(status().isConflict());
  }

  // ─── 400: validação ────────────────────────────────────────────────────────────────────────

  @Test
  void payloadInvalidoDa400ComListaDeCampos() throws Exception {
    String corpo =
        """
        {
          "categoria": "ENTREGA",
          "titulo": "abc",
          "descricao": "Descrição válida da missão.",
          "valorBrl": 900.00,
          "tokensRecompensa": 10,
          "xpRecompensa": 100,
          "origemLat": -23.5629,
          "origemLon": -46.6996,
          "cep": "05422030",
          "logradouro": "Rua dos Pinheiros",
          "bairro": "Pinheiros",
          "cidade": "São Paulo",
          "uf": "SP",
          "raioCheckinM": 50,
          "janelaInicio": "2026-09-01T10:00:00Z",
          "janelaFim": "2026-08-01T10:00:00Z"
        }
        """;

    MvcResult resultado =
        mockMvc
            .perform(
                post(BASE)
                    .header("Authorization", bearer(ALICE_ID))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(corpo))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Requisição inválida"))
            .andReturn();

    List<String> campos = camposComErro(resultado);
    assertThat(campos).contains("titulo", "valorBrl", "janelaFim");
  }

  @Test
  void missaoTriboComValorEmBrlDa400() throws Exception {
    // A regra que dá coerência às três moedas: TRIBO e COLETA recompensam em token e XP.
    String corpo = corpoValido("TRIBO", "10.00");

    MvcResult resultado =
        mockMvc
            .perform(
                post(BASE)
                    .header("Authorization", bearer(ALICE_ID))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(corpo))
            .andExpect(status().isBadRequest())
            .andReturn();

    assertThat(camposComErro(resultado)).contains("valorBrl");
  }

  @Test
  void missaoTriboSemValorEmBrlEAceita() throws Exception {
    mockMvc
        .perform(
            post(BASE)
                .header("Authorization", bearer(ALICE_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoValido("TRIBO", "0.00")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.categoria").value("TRIBO"));
  }

  @Test
  void tamanhoDePaginaAcimaDoLimiteDa400() throws Exception {
    // Afere o CAMPO, não só o status: um 400 vindo de outra causa (ex.: falha de binding de
    // 'pagina') faria este teste passar sem provar nada sobre o limite de página.
    MvcResult resultado =
        mockMvc
            .perform(get(BASE).param("tamanho", "500").header("Authorization", bearer(ALICE_ID)))
            .andExpect(status().isBadRequest())
            .andReturn();

    assertThat(camposComErro(resultado)).containsExactly("tamanho");
  }

  @Test
  void listagemSemNenhumParametroUsaDefaults() throws Exception {
    mockMvc
        .perform(get(BASE).header("Authorization", bearer(ALICE_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pagina").value(0))
        .andExpect(jsonPath("$.tamanho").value(20));
  }

  // ─── Mass assignment ───────────────────────────────────────────────────────────────────────

  @Test
  void patchIgnoraStatusExecutorEXpRecompensa() throws Exception {
    UUID missaoId = criarEPublicar();
    int xpOriginal = 100;

    mockMvc
        .perform(
            patch(BASE + "/{id}", missaoId)
                .header("Authorization", bearer(ALICE_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "titulo": "Título legitimamente editado",
                      "status": "CONCLUIDA",
                      "executorId": "bbbbbbbb-0000-0000-0000-000000000003",
                      "xpRecompensa": 9999,
                      "valorBrl": 499.99,
                      "criadorId": "bbbbbbbb-0000-0000-0000-000000000003"
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.titulo").value("Título legitimamente editado"));

    // Relê do banco: o que não é editável tem de continuar exatamente como estava.
    mockMvc
        .perform(get(BASE + "/{id}", missaoId).header("Authorization", bearer(ALICE_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ABERTA"))
        .andExpect(jsonPath("$.executorId").doesNotExist())
        .andExpect(jsonPath("$.xpRecompensa").value(xpOriginal))
        .andExpect(jsonPath("$.valorBrl").value(25.00))
        .andExpect(jsonPath("$.criadorId").value(ALICE_ID.toString()));
  }

  @Test
  void criacaoIgnoraStatusECriadorEnviadosNoCorpo() throws Exception {
    String corpo =
        corpoValido("ENTREGA", "25.00")
            .replaceFirst(
                "\\{",
                "{\"status\":\"CONCLUIDA\","
                    + "\"criadorId\":\"bbbbbbbb-0000-0000-0000-000000000003\","
                    + "\"executorId\":\"bbbbbbbb-0000-0000-0000-000000000003\",");

    mockMvc
        .perform(
            post(BASE)
                .header("Authorization", bearer(ALICE_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("RASCUNHO"))
        .andExpect(jsonPath("$.criadorId").value(ALICE_ID.toString()))
        .andExpect(jsonPath("$.executorId").doesNotExist());
  }

  // ─── Visibilidade de rascunho ──────────────────────────────────────────────────────────────

  @Test
  void rascunhoAlheioResponde404ENao403() throws Exception {
    // 403 confirmaria a existência do recurso a quem não deveria saber que ele existe.
    UUID missaoId = criarMissao(ALICE_ID, corpoEntregaValido());

    mockMvc
        .perform(get(BASE + "/{id}", missaoId).header("Authorization", bearer(BOB_ID)))
        .andExpect(status().isNotFound());
  }

  @Test
  void rascunhoAlheioNaoApareceNaListagem() throws Exception {
    UUID missaoId = criarMissao(ALICE_ID, corpoEntregaValido());

    MvcResult resultado =
        mockMvc
            .perform(
                get(BASE)
                    .param("status", "RASCUNHO")
                    .param("tamanho", "100")
                    .header("Authorization", bearer(BOB_ID)))
            .andExpect(status().isOk())
            .andReturn();

    JsonNode pagina = objectMapper.readTree(resultado.getResponse().getContentAsString());
    for (JsonNode item : pagina.get("conteudo")) {
      assertThat(item.get("id").asText()).isNotEqualTo(missaoId.toString());
    }

    // Só o criador enxerga o próprio rascunho.
    mockMvc
        .perform(get(BASE + "/{id}", missaoId).header("Authorization", bearer(ALICE_ID)))
        .andExpect(status().isOk());
  }

  // ─── Paginação e filtros ───────────────────────────────────────────────────────────────────

  @Test
  void listagemPaginadaDevolveEnvelopeEstavel() throws Exception {
    mockMvc
        .perform(
            get(BASE)
                .param("tamanho", "2")
                .param("status", "ABERTA")
                .header("Authorization", bearer(ALICE_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.conteudo").isArray())
        .andExpect(jsonPath("$.tamanho").value(2))
        .andExpect(jsonPath("$.pagina").value(0))
        .andExpect(jsonPath("$.totalElementos").exists())
        .andExpect(jsonPath("$.totalPaginas").exists())
        .andExpect(jsonPath("$.primeira").value(true))
        // O envelope não pode vazar a serialização interna de Page do Spring Data.
        .andExpect(jsonPath("$.pageable").doesNotExist())
        .andExpect(jsonPath("$.sort").doesNotExist());
  }

  @Test
  void filtroMinhasCriadasSoTrazMissoesDoAtor() throws Exception {
    criarMissao(ALICE_ID, corpoEntregaValido());

    MvcResult resultado =
        mockMvc
            .perform(
                get(BASE)
                    .param("minhas", "CRIADAS")
                    .param("tamanho", "100")
                    .header("Authorization", bearer(ALICE_ID)))
            .andExpect(status().isOk())
            .andReturn();

    JsonNode pagina = objectMapper.readTree(resultado.getResponse().getContentAsString());
    assertThat(pagina.get("conteudo")).isNotEmpty();
    for (JsonNode item : pagina.get("conteudo")) {
      assertThat(item.get("criadorId").asText()).isEqualTo(ALICE_ID.toString());
    }
  }

  @Test
  void missaoInexistenteDa404() throws Exception {
    mockMvc
        .perform(get(BASE + "/{id}", UUID.randomUUID()).header("Authorization", bearer(ALICE_ID)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("Missão não encontrada."));
  }

  // ─── Helpers ───────────────────────────────────────────────────────────────────────────────

  private String bearer(UUID usuarioId) {
    String papel = usuarioId.equals(ADMIN_ID) ? "ADMIN" : "USUARIO";
    return "Bearer " + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", papel);
  }

  private UUID criarMissao(UUID criador, String corpo) throws Exception {
    MvcResult resultado =
        mockMvc
            .perform(
                post(BASE)
                    .header("Authorization", bearer(criador))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(corpo))
            .andExpect(status().isCreated())
            .andReturn();

    return UUID.fromString(
        objectMapper.readTree(resultado.getResponse().getContentAsString()).get("id").asText());
  }

  private UUID criarEPublicar() throws Exception {
    UUID missaoId = criarMissao(ALICE_ID, corpoEntregaValido());
    mockMvc
        .perform(post(BASE + "/{id}/publicar", missaoId).header("Authorization", bearer(ALICE_ID)))
        .andExpect(status().isOk());
    return missaoId;
  }

  private UUID criarPublicarEAceitar() throws Exception {
    UUID missaoId = criarEPublicar();
    mockMvc
        .perform(post(BASE + "/{id}/aceitar", missaoId).header("Authorization", bearer(BOB_ID)))
        .andExpect(status().isOk());
    return missaoId;
  }

  /**
   * Leva a missão até um estado que hoje só é alcançável por caminhos de F6/F7. Percorre a máquina
   * até EM_ANDAMENTO pelos endpoints reais e faz o último salto por SQL — o salto está coberto pela
   * matriz de MissaoStateMachineTest, e simulá-lo aqui evita esperar F6 para testar o contrato de
   * erro dos stubs.
   */
  private UUID criarMissaoEm(String status) throws Exception {
    UUID missaoId = criarPublicarEAceitar();
    mockMvc
        .perform(post(BASE + "/{id}/iniciar", missaoId).header("Authorization", bearer(BOB_ID)))
        .andExpect(status().isOk());
    jdbcAtualizarStatus(missaoId, status);
    return missaoId;
  }

  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

  private void jdbcAtualizarStatus(UUID missaoId, String status) {
    jdbcTemplate.update("UPDATE missao SET status = ? WHERE id = ?", status, missaoId);
  }

  private List<String> camposComErro(MvcResult resultado) throws Exception {
    JsonNode raiz = objectMapper.readTree(resultado.getResponse().getContentAsString());
    List<String> campos = new ArrayList<>();
    for (JsonNode erro : raiz.get("errors")) {
      campos.add(erro.get("campo").asText());
    }
    return campos;
  }

  private static String corpoEntregaValido() {
    return corpoValido("ENTREGA", "25.00");
  }

  private static String corpoValido(String categoria, String valorBrl) {
    Instant inicio = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Instant fim = inicio.plus(2, ChronoUnit.DAYS);
    return """
        {
          "categoria": "%s",
          "titulo": "Entrega solidária no bairro",
          "descricao": "Levar a encomenda até o ponto de custódia da Vila Madalena.",
          "valorBrl": %s,
          "tokensRecompensa": 10,
          "xpRecompensa": 100,
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
        .formatted(categoria, valorBrl, inicio, fim);
  }
}
