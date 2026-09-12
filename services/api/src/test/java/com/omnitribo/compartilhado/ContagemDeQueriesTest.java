package com.omnitribo.compartilhado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.ContadorDeQueries;
import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import com.omnitribo.UsuarioDeTeste;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Quantas instruções SQL cada leitura quente emite — e, sobretudo, se esse número CRESCE com a
 * quantidade de linhas devolvidas.
 *
 * <p><b>O que este teste protege, e por que ele não existia.</b> O backend não tem uma única
 * associação JPA: toda FK é {@code UUID} escalar, e {@code open-in-view} é {@code false}. Isso
 * torna o N+1 clássico impossível <i>hoje</i> — e é exatamente por isso que ninguém escreveu teste
 * nenhum sobre o assunto. A proteção é uma escolha de modelagem, não uma trava: no dia em que
 * alguém trocar {@code UUID criadorId} por {@code @ManyToOne Usuario criador} para mostrar o handle
 * na listagem, {@code MissaoService.listar} passa a emitir 1+N e a suíte inteira continua verde. A
 * partir daqui, não continua.
 *
 * <p><b>A asserção que importa é a de INVARIÂNCIA, não a do número.</b> Cada caso mede o mesmo
 * endpoint com poucas e com muitas linhas e exige o MESMO total. Um número fixo sozinho envelhece —
 * uma query legítima a mais o quebraria sem que nada tivesse piorado; a invariância só quebra
 * quando a contagem passa a depender do tamanho do resultado, que é a definição de N+1. Os números
 * exatos ficam registrados junto, porque são a evidência pedida.
 *
 * <p><b>Todo caso aquece a sessão antes de contar.</b> O {@code JwtAuthFilter} consulta {@code
 * ConsultaSessao} a cada requisição (cache de 60 s, ADR 0016), então a PRIMEIRA requisição de um
 * usuário carrega um SELECT em {@code usuario} que não é do endpoint. Contar sem aquecer mediria a
 * autenticação junto e faria o número variar conforme a ordem dos testes.
 */
@Import(JwtTestConfig.class)
@DisplayName("Contagem de queries dos caminhos quentes de leitura")
class ContagemDeQueriesTest extends TesteIntegracaoMvcBase {

  private static final Logger log = LoggerFactory.getLogger(ContagemDeQueriesTest.class);

  private static final String MISSOES = "/api/v1/missoes";
  private static final String EXTRATO = "/api/v1/carteira/lancamentos";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbcClient;

  private UUID usuarioId;
  private UUID carteiraId;

  @BeforeEach
  void arranjar() {
    usuarioId = UsuarioDeTeste.criarAtivo(jdbcTemplate, "contagem");
    carteiraId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO carteira (id, usuario_id, saldo_brl, saldo_tokens, versao)"
            + " VALUES (?, ?, 0.00, 0, 0)",
        carteiraId,
        usuarioId);
  }

  @AfterEach
  void limpar() {
    // Ordem obrigatória: lancamento referencia carteira, que referencia usuario. E o JdbcTemplate
    // injetado é o do OPERADOR — a aplicação não pode apagar `lancamento`, e é isso que
    // MigracaoTest
    // prova em runtime.
    jdbcTemplate.update("DELETE FROM lancamento WHERE carteira_id = ?", carteiraId);
    jdbcTemplate.update("DELETE FROM carteira WHERE id = ?", carteiraId);
    UsuarioDeTeste.remover(jdbcTemplate, usuarioId);
  }

  @Test
  @DisplayName("extrato da carteira: constante no número de linhas devolvidas")
  void extrato_nao_cresce_com_o_numero_de_lancamentos() throws Exception {
    inserirLancamentos(100);
    aquecerSessao();

    // DUAS páginas CHEIAS, de tamanhos diferentes. Comparar uma página cheia com uma incompleta
    // mediria outra coisa — ver `paginacao_omite_o_count_quando_a_pagina_nao_enche`.
    int pagina20 = contarEm(EXTRATO, "tamanho", "20");
    int pagina50 = contarEm(EXTRATO, "tamanho", "50");

    assertThat(pagina50)
        .as("devolver 50 lançamentos não pode custar mais queries que devolver 20")
        .isEqualTo(pagina20);
    // Resolução usuario→carteira (projeção escalar), página e count.
    assertThat(pagina20).isEqualTo(3);
  }

  @Test
  @DisplayName("listagem de missões: constante no número de linhas devolvidas")
  void listagem_nao_cresce_com_o_tamanho_da_pagina() throws Exception {
    aquecerSessao();

    int pagina5 = contarEm(MISSOES, "tamanho", "5");
    int pagina20 = contarEm(MISSOES, "tamanho", "20");

    assertThat(pagina20)
        .as("uma página de 20 missões não pode custar mais queries que uma de 5")
        .isEqualTo(pagina5);
    // Página + count.
    assertThat(pagina5).isEqualTo(2);
  }

  /**
   * O count da paginação NÃO é emitido quando a página não enche — e isso é do Spring Data, não do
   * código deste projeto.
   *
   * <p>{@code PageableExecutionUtils.getPage} dispensa o {@code SELECT count(*)} quando a primeira
   * página volta com menos elementos que o tamanho pedido: o total já está determinado. A primeira
   * versão deste arquivo tratou isso como N+1 ao contrário — mediu 2 queries com 3 lançamentos e 3
   * com 50, e a asserção de invariância reprovou. O número não cresce com as LINHAS; ele depende de
   * a página ter enchido.
   *
   * <p>Fica registrado porque muda como se lê qualquer contagem de query paginada aqui: 2 e 3 são
   * os dois valores corretos do mesmo endpoint, e escolher o fixture errado esconde ou inventa
   * diferença.
   */
  @Test
  @DisplayName("a paginação omite o count quando a página não enche")
  void paginacao_omite_o_count_quando_a_pagina_nao_enche() throws Exception {
    inserirLancamentos(3);
    aquecerSessao();

    int paginaCheia = contarEm(EXTRATO, "tamanho", "3");
    int paginaFolgada = contarEm(EXTRATO, "tamanho", "50");

    assertThat(paginaCheia).as("3 de 3: o total é desconhecido, o count sai").isEqualTo(3);
    assertThat(paginaFolgada).as("3 de 50: o total já é sabido, o count é dispensado").isEqualTo(2);
  }

  @Test
  @DisplayName("detalhe da missão: 1 query")
  void detalhe_emite_uma_unica_query() throws Exception {
    aquecerSessao();
    UUID missaoId = primeiraMissaoDoSeed();

    try (ContadorDeQueries.Captura captura = ContadorDeQueries.iniciar()) {
      mockMvc
          .perform(get(MISSOES + "/{id}", missaoId).header("Authorization", bearer()))
          .andExpect(status().isOk());
      assertThat(captura.total()).isEqualTo(1);
      assertThat(captura.porTabela()).containsEntry("missao", 1);
    }
  }

  @Test
  @DisplayName("radar: 2 queries no miss com resultado, 0 no hit do cache")
  void radar_gasta_duas_queries_e_o_cache_zera_a_segunda_chamada() throws Exception {
    aquecerSessao();

    // Coordenada VARIÁVEL e PRÓXIMA do seed, e as duas propriedades são necessárias.
    //
    // Variável porque a chave do cache é a célula de geohash (precisão 7, ~150 m): um ponto fixo
    // seria servido pelo cache que outra classe já populou e o teste mediria zero, passando por
    // vácuo. Próxima porque o radar retorna cedo, com `List.of()`, quando nada cai no raio — e aí
    // o `findAllById` nem acontece. A primeira versão deste caso sorteava um ponto no meio de
    // lugar nenhum e media 1 query, não 2: o caminho sem resultado é mais barato, e medi-lo achando
    // que era o caminho normal teria registrado o número errado na evidência.
    double deslocamento = ((UUID.randomUUID().hashCode() & 0x7fff) % 200 - 100) * 0.0002;
    // Locale.ROOT e não o default: nesta máquina o default é pt-BR, `%.5f` sai com VÍRGULA, e o
    // binder do Spring devolve 400 — o teste morria antes de contar query nenhuma.
    String lat = String.format(Locale.ROOT, "%.5f", -23.5629 + deslocamento);
    String lon = String.format(Locale.ROOT, "%.5f", -46.6996 + deslocamento);

    int miss = contarRadar(lat, lon, true);
    int hit = contarRadar(lat, lon, false);

    // ConsultasGeoespaciais (PostGIS, via JdbcClient) + findAllById para reidratar as entidades.
    assertThat(miss).isEqualTo(2);
    assertThat(hit).as("segunda chamada na mesma célula sai inteira do Caffeine").isZero();
  }

  @Test
  @DisplayName("registra o SQL que o Hibernate de fato emite na listagem")
  void registra_o_sql_real_da_listagem() throws Exception {
    aquecerSessao();

    try (ContadorDeQueries.Captura captura = ContadorDeQueries.iniciar()) {
      mockMvc
          .perform(
              get(MISSOES)
                  .param("ordenarPor", "TOKENS_RECOMPENSA")
                  .param("cidade", "São Paulo")
                  .header("Authorization", bearer()))
          .andExpect(status().isOk());

      // Impresso, não asserido sobre o texto inteiro: é a contraparte de PlanoConsultasQuentesTest,
      // que EXPLICA um SQL reproduzido à mão porque JPQL não expõe constante. Colar as duas saídas
      // lado a lado na evidência é o que permite conferir que o plano medido é o desta consulta.
      captura.instrucoes().forEach(sql -> log.info("SQL da listagem: {}", sql));

      assertThat(captura.instrucoes())
          .as("a ordenação pedida precisa chegar ao SQL, senão o plano medido é de outra consulta")
          .anySatisfy(sql -> assertThat(sql).contains("tokens_recompensa"));
      assertThat(captura.instrucoes())
          .as("o lower() do filtro de cidade também")
          .anySatisfy(sql -> assertThat(sql).contains("lower("));
    }
  }

  /**
   * ACHADO: na suíte, o {@code JdbcClient} conecta como o DONO do banco, não como {@code
   * omnitribo_app}.
   *
   * <p>{@code OperadorBancoTestConfig} declara um {@code JdbcTemplate} {@code @Primary} ligado ao
   * dono, para que os 33 pontos de limpeza de tabela append-only funcionem. O efeito colateral é
   * que a autoconfiguração do Boot monta {@code NamedParameterJdbcTemplate} — e daí o {@code
   * JdbcClient} — a partir do template primário. Como {@code ConsultasGeoespaciais} injeta {@code
   * JdbcClient}, <b>todo {@code ST_*} do sistema roda com privilégio de dono nos testes</b>.
   *
   * <p><b>Não é defeito de produção:</b> lá não existe {@code OperadorBancoTestConfig}, o único
   * {@code DataSource} é o de {@code omnitribo_app} e o {@code JdbcClient} herda esse. É uma
   * diferença de FIDELIDADE da suíte: {@code MigracaoTest} prova em runtime que a aplicação não
   * apaga o ledger, mas essa prova cobre o caminho JPA — o caminho geoespacial nunca exercita o
   * papel restrito. Uma escrita introduzida em {@code ConsultasGeoespaciais} passaria verde aqui e
   * falharia com {@code permission denied} em produção.
   *
   * <p>O teste fixa o fato medido para que a mudança seja notada, não para aprová-la.
   */
  @Test
  @DisplayName("ACHADO: o JdbcClient da suíte usa o papel de DONO, não o da aplicação")
  void jdbcclient_da_suite_conecta_como_dono_do_banco() {
    String papel = jdbcClient.sql("SELECT current_user").query(String.class).single();
    log.info("JdbcClient da suíte conecta como: {}", papel);

    assertThat(papel)
        .as(
            "se um dia isto virar omnitribo_app, a lacuna de fidelidade fechou — atualize o javadoc")
        .isNotEqualTo("omnitribo_app");
  }

  // ─── Apoio ─────────────────────────────────────────────────────────────────────────────────

  private int contarEm(String caminho, String parametro, String valor) throws Exception {
    try (ContadorDeQueries.Captura captura = ContadorDeQueries.iniciar()) {
      mockMvc
          .perform(get(caminho).param(parametro, valor).header("Authorization", bearer()))
          .andExpect(status().isOk());
      return captura.total();
    }
  }

  private int contarRadar(String lat, String lon, boolean exigirResultado) throws Exception {
    try (ContadorDeQueries.Captura captura = ContadorDeQueries.iniciar()) {
      String corpo =
          mockMvc
              .perform(
                  get(MISSOES + "/proximas")
                      .param("lat", lat)
                      .param("lon", lon)
                      .param("raioMetros", "5000")
                      .header("Authorization", bearer()))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();

      if (exigirResultado) {
        // Sem isto o caso passaria medindo o caminho vazio, que custa uma query a menos.
        assertThat(corpo)
            .as("o radar precisa achar missão do seed, senão mede o caminho vazio")
            .isNotEqualTo("[]");
      }
      return captura.total();
    }
  }

  /** Uma requisição descartada só para popular o cache de sessão do {@code JwtAuthFilter}. */
  private void aquecerSessao() throws Exception {
    mockMvc
        .perform(get(MISSOES).param("tamanho", "1").header("Authorization", bearer()))
        .andExpect(status().isOk());
  }

  private UUID primeiraMissaoDoSeed() {
    return jdbcTemplate.queryForObject(
        "SELECT id FROM missao WHERE status = 'ABERTA' ORDER BY criada_em LIMIT 1", UUID.class);
  }

  private void inserirLancamentos(int quantidade) {
    for (int i = 0; i < quantidade; i++) {
      jdbcTemplate.update(
          """
          INSERT INTO lancamento (id, carteira_id, sinal, motivo, valor_brl, valor_tokens,
                                  chave_idempotencia, saldo_apos_brl, saldo_apos_tokens, criado_em)
          VALUES (?, ?, 'CREDITO', 'BONUS', 0.00, 1, ?, 0.00, ?, NOW())
          """,
          UUID.randomUUID(),
          carteiraId,
          "contagem-" + UUID.randomUUID(),
          i + 1L);
    }
    // O saldo da projeção acompanha o ledger: uma carteira com lançamentos e saldo zero deixaria
    // ReconciliacaoTest e ConservacaoTokensTest vermelhos, que asseram sobre o banco INTEIRO.
    jdbcTemplate.update(
        "UPDATE carteira SET saldo_tokens = (SELECT COALESCE(SUM(valor_tokens), 0)"
            + " FROM lancamento WHERE carteira_id = ?) WHERE id = ?",
        carteiraId,
        carteiraId);
  }

  private String bearer() {
    return "Bearer "
        + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", "USUARIO");
  }
}
