package com.omnitribo.carteira.api;

import static com.omnitribo.carteira.SuporteCarteira.assertLedgerReconcilia;
import static com.omnitribo.carteira.SuporteCarteira.limparMissao;
import static com.omnitribo.carteira.SuporteCarteira.tokensEmCirculacao;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import com.omnitribo.missoes.dominio.CategoriaMissao;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Conservação da oferta de TOKEN ao longo de um ciclo completo de missão, por categoria.
 *
 * <h2>Por que existe, separado da reconciliação</h2>
 *
 * <p>São invariantes DIFERENTES, e confundi-las já custou caro. A reconciliação verifica que a soma
 * do ledger é igual ao saldo materializado de cada carteira — consistência interna. A conservação
 * verifica que nenhuma moeda foi criada sem contrapartida. A primeira pode passar enquanto a
 * segunda é violada, e foi exatamente o que aconteceu no defeito que originou o ADR 0009: R$ 118,00
 * viraram R$ 1.618,00 em três ciclos do fluxo feliz, e {@code GET /admin/carteiras/reconciliacao}
 * respondeu {@code integro=true} o tempo todo — corretamente, porque ledger e projeção nunca
 * divergiram.
 *
 * <p>Este teste chama {@link com.omnitribo.carteira.SuporteCarteira#assertLedgerReconcilia} nos
 * DOIS ramos de propósito. Não é redundância: é a demonstração executável de que a reconciliação
 * passa mesmo quando há cunhagem. Se algum dia alguém propuser "a reconciliação já cobre isso",
 * este teste é a resposta.
 *
 * <h2>Por que ramifica em vez de exigir conservação nas quatro categorias</h2>
 *
 * <p>{@code MissaoService.pagaTokensDoPote} restringe o pote a TRIBO e COLETA. ENTREGA e AJUDA
 * pagam o executor sem débito de contrapartida — <b>cunham</b>. Isso é lacuna DELIBERADA e
 * documentada (ADR 0009, §Consequências): o financiador correto dessas categorias é o patrocinador,
 * que chega na F8, e exigir pote de membros da tribo faria a comunidade custear a logística do
 * varejista, que é o inverso do modelo.
 *
 * <p>Um {@code @EnumSource} exigindo {@code delta == 0} nas quatro falharia em ENTREGA e AJUDA —
 * não por bug, mas por desenho. O objetivo aqui é <b>declarar os dois regimes</b>, não apagar um.
 * Com isso, tanto uma emissão vazando para as categorias com pote quanto uma mudança de regime
 * acidental nas sem pote quebram o build. Quando o patrocinador da F8 entrar, este teste é o que
 * deve mudar primeiro — e a mudança dele é o registro de que a lacuna fechou.
 */
@Import(JwtTestConfig.class)
class ConservacaoTokensTest extends TesteIntegracaoMvcBase {

  private static final String MISSOES = "/api/v1/missoes";
  private static final long SALDO_INICIAL = 1000L;

  /** Recompensa da missão sob teste, capturada da criação — derivada pela calculadora. */
  private long recompensa;

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  private UUID tribo;
  private UUID criador;
  private UUID executor;
  private final List<UUID> missoesCriadas = new ArrayList<>();

  @BeforeEach
  void montarCenario() {
    tribo = criarTribo();
    // Criador com saldo porque em TRIBO/COLETA é ele quem financia o pote; executor começa zerado
    // para que o delta dele seja exatamente a recompensa, sem ruído.
    criador = criarUsuarioComCarteira("criador", tribo, SALDO_INICIAL);
    executor = criarUsuarioComCarteira("executor", tribo, 0L);
  }

  @AfterEach
  void limpar() {
    missoesCriadas.forEach(id -> limparMissao(jdbcTemplate, id));
    missoesCriadas.clear();
    for (UUID usuario : List.of(criador, executor)) {
      jdbcTemplate.update(
          "DELETE FROM lancamento WHERE carteira_id IN (SELECT id FROM carteira WHERE usuario_id = ?)",
          usuario);
      jdbcTemplate.update("DELETE FROM auditoria WHERE ator_id = ?", usuario);
      jdbcTemplate.update("DELETE FROM carteira WHERE usuario_id = ?", usuario);
      jdbcTemplate.update("DELETE FROM usuario WHERE id = ?", usuario);
    }
    jdbcTemplate.update("DELETE FROM tribo WHERE id = ?", tribo);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(CategoriaMissao.class)
  void conservacaoDaOfertaDeTokenDependeDaCategoria(CategoriaMissao categoria) throws Exception {
    boolean pagaDoPote = categoria == CategoriaMissao.TRIBO || categoria == CategoriaMissao.COLETA;

    long circulacaoAntes = tokensEmCirculacao(jdbcTemplate);

    UUID missaoId = criarMissaoEmRascunho(categoria);

    if (pagaDoPote) {
      // ENTREGA e AJUDA recebem 422 aqui ("só missões TRIBO e COLETA aceitam financiamento"), então
      // o passo simplesmente não existe para elas. É o que torna a cunhagem possível nessas duas.
      mockMvc
          .perform(
              post("/api/v1/tribos/{triboId}/financiamentos", tribo)
                  .header("Authorization", bearer(criador))
                  .header("Idempotency-Key", "conservacao-" + missaoId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {"missaoId":"%s","tokens":%d}
                      """
                          .formatted(missaoId, recompensa)))
          .andExpect(status().isCreated());

      assertThat(tokensEmCirculacao(jdbcTemplate))
          .as("financiar move token da carteira para o pote — não cria nem destrói")
          .isEqualTo(circulacaoAntes);
    }

    acao(missaoId, "publicar", criador);
    acao(missaoId, "aceitar", executor);
    acao(missaoId, "iniciar", executor);
    // Salto por SQL, padrão da suíte de carteira (ver ConclusaoConcorrenteTest): o check-in real
    // tem
    // cobertura própria em CheckinControllerTest, e replicá-lo aqui acoplaria a conservação à
    // geolocalização sem acrescentar nada ao que este teste mede.
    jdbcTemplate.update(
        "UPDATE missao SET status = 'AGUARDANDO_CONFIRMACAO' WHERE id = ?", missaoId);
    acao(missaoId, "confirmar", criador);

    long delta = tokensEmCirculacao(jdbcTemplate) - circulacaoAntes;

    if (pagaDoPote) {
      assertThat(delta)
          .as("%s paga DO pote: a oferta de token não pode mudar no ciclo inteiro", categoria)
          .isZero();
    } else {
      assertThat(delta)
          .as(
              "%s CUNHA %d tokens — lacuna deliberada até a carteira de patrocinador da F8 "
                  + "(ADR 0009). Se este valor mudar, a lacuna mudou de tamanho ou fechou, e as "
                  + "duas coisas exigem decisão explícita.",
              categoria, recompensa)
          .isEqualTo(recompensa);
    }

    // Roda nos dois ramos: a reconciliação passa mesmo no caso que cunha. É o ponto do teste.
    assertLedgerReconcilia(jdbcTemplate);
  }

  // ─── Helpers ────────────────────────────────────────────────────────────────────────────────

  private void acao(UUID missaoId, String acao, UUID ator) throws Exception {
    mockMvc
        .perform(post(MISSOES + "/{id}/" + acao, missaoId).header("Authorization", bearer(ator)))
        .andExpect(status().isOk());
  }

  private UUID criarMissaoEmRascunho(CategoriaMissao categoria) throws Exception {
    // ENTREGA e COLETA exigem peso e volume (o servidor deriva a complexidade); TRIBO e AJUDA
    // declaram. Ver CriacaoMissaoVerificador.
    boolean carregaCoisa =
        categoria == CategoriaMissao.ENTREGA || categoria == CategoriaMissao.COLETA;
    String insumos =
        carregaCoisa
            ? "\"pesoKg\": 10.00,\n          \"volumeL\": 40.00,"
            : "\"complexidade\": \"MEDIA\",";

    Instant inicio = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    String corpo =
        """
        {
          "categoria": "%s",
          "titulo": "Conservação de token na categoria %s",
          "descricao": "Ciclo completo para medir a oferta de token antes e depois.",
          "valorBrl": 0.00,
          %s
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
            .formatted(categoria, categoria, insumos, inicio, inicio.plus(2, ChronoUnit.DAYS));

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
    UUID id = UUID.fromString(corpoCriada.get("id").asText());
    missoesCriadas.add(id);
    return id;
  }

  private UUID criarTribo() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO tribo (id, nome, bairro, criada_em) VALUES (?, ?, ?, NOW())",
        id,
        "Tribo Conservação " + id.toString().substring(0, 8),
        "Bairro Conservação");
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

  private String bearer(UUID usuarioId) {
    return "Bearer "
        + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", "USUARIO");
  }
}
