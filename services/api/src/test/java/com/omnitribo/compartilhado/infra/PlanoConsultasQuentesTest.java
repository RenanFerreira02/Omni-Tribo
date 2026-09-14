package com.omnitribo.compartilhado.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnitribo.TesteIntegracaoBase;
import com.omnitribo.missoes.api.MissaoFiltroRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Que plano o PostgreSQL escolhe para as leituras quentes NÃO geoespaciais, sob volume.
 *
 * <p>Irmão de {@link IndiceGeoespacialTest}, e repete as duas recusas dele, que são o que dá valor
 * à evidência: nada de EXPLAIN sobre a tabela como ela está (20 missões fazem seq scan, e fazem
 * certo), e nada de {@code SET enable_seqscan = off} (provaria que o índice PODE ser usado,
 * pergunta que ninguém tem). Nenhum {@code enable_*} é tocado aqui.
 *
 * <p><b>Uma diferença em relação ao irmão, e ela precisa estar declarada.</b> Lá o SQL explicado é
 * a constante de produção, referenciada e não copiada. Aqui não dá: {@code
 * MissaoRepository.buscarComFiltros} e {@code LancamentoRepository.findByCarteiraId} são JPQL e
 * query derivada — não existe constante de SQL para referenciar, e o SQL real só nasce no
 * Hibernate, com placeholders posicionais cuja ordem não é contrato. A saída é reproduzir o
 * predicado aqui E travar a divergência: {@link
 * #consulta_de_producao_continua_com_a_forma_que_este_teste_explica()} falha se a listagem deixar
 * de ordenar por {@code tokens_recompensa} ou de aplicar {@code lower()} sobre a cidade. Sem essa
 * trava, este arquivo viraria um EXPLAIN sobre uma consulta que ninguém executa.
 *
 * <p>Os planos são impressos no log para serem colados na evidência.
 */
@Tag("geo")
@DisplayName("Plano das leituras quentes sob volume")
class PlanoConsultasQuentesTest extends TesteIntegracaoBase {

  private static final Logger log = LoggerFactory.getLogger(PlanoConsultasQuentesTest.class);

  private static final int MISSOES = 200_000;

  /**
   * 1 000 carteiras × 500 lançamentos. Uma carteira só faria TODA linha casar com o filtro, e o seq
   * scan passaria a ser a escolha correta — o teste mediria a fixture, não o índice.
   */
  private static final int CARTEIRAS = 1_000;

  private static final int LANCAMENTOS_POR_CARTEIRA = 500;

  /**
   * Prefixos sentinela, com o registro COMPLETO da faixa {@code eeee*}.
   *
   * <p>A lista antiga era incompleta e por isso inútil: ela citava só {@code
   * IndiceGeoespacialTest}, e {@code IndicePoteImobilizadoTest} tinha adotado {@code eeee1111-} — o
   * mesmo de {@code PREFIXO_MISSAO} abaixo — com um javadoc próprio afirmando exclusividade. Os
   * dois {@code @AfterAll} varriam a faixa um do outro por {@code DELETE ... LIKE}. Mantenha esta
   * lista ao acrescentar um prefixo, senão o próximo autor lê uma lista incompleta e reocupa faixa
   * viva.
   *
   * <ul>
   *   <li>{@code eeee0000-} — {@code IndiceGeoespacialTest}
   *   <li>{@code eeee1111-} / {@code eeee2222-} / {@code eeee3333-} / {@code eeee4444-} — esta
   *       classe
   *   <li>{@code eeee5555-} — {@code IndicePoteImobilizadoTest}
   * </ul>
   *
   * <p>O seed usa {@code dddddddd-} e {@code bbbbbbbb-}.
   */
  private static final String PREFIXO_MISSAO = "eeee1111-0000-0000-0000-";

  private static final String PREFIXO_USUARIO = "eeee2222-0000-0000-0000-";

  private static final String PREFIXO_CARTEIRA = "eeee3333-0000-0000-0000-";

  private static final String PREFIXO_LANCAMENTO = "eeee4444-";

  private static final String CRIADOR_SEED = "bbbbbbbb-0000-0000-0000-000000000001";

  /** A carteira 500 de 1 000 — do meio, para não cair numa borda do índice. */
  private static final String CARTEIRA_SONDADA = PREFIXO_CARTEIRA + "000000000500";

  @Autowired JdbcTemplate jdbc;

  /**
   * Os quatro planos de {@code missao} num método só, e a razão é de custo: cada método
   * {@code @Transactional} semearia as 200 mil linhas de novo, e três deles seriam 600 mil
   * inserções para responder a perguntas sobre a MESMA tabela no MESMO estado. Uma semeadura,
   * quatro EXPLAIN.
   */
  @Test
  @Transactional
  @DisplayName("planos da listagem de missões sob 200 mil linhas")
  void planos_da_listagem_de_missoes() {
    semearMissoes();
    // Obrigatório: sem estatísticas o planner recorre a palpite fixo e o plano deixa de ser
    // evidência de coisa alguma. ANALYZE é legal dentro de transação; VACUUM não é.
    jdbc.execute("ANALYZE missao");

    String comIndice =
        explicar(
            """
            SELECT m.id, m.titulo, m.status, m.tokens_recompensa, m.criada_em
              FROM missao m
             WHERE m.status = ?
               AND (m.status <> 'RASCUNHO' OR m.criador_id = CAST(? AS uuid))
             ORDER BY m.criada_em DESC
             LIMIT 20
            """,
            "ABERTA",
            CRIADOR_SEED);
    log.info("EXPLAIN — listagem ORDER BY criada_em DESC:\n{}", comIndice);

    String semIndice =
        explicar(
            """
            SELECT m.id, m.titulo, m.status, m.tokens_recompensa, m.criada_em
              FROM missao m
             WHERE m.status = ?
               AND (m.status <> 'RASCUNHO' OR m.criador_id = CAST(? AS uuid))
             ORDER BY m.tokens_recompensa DESC
             LIMIT 20
            """,
            "ABERTA",
            CRIADOR_SEED);
    log.info("EXPLAIN — listagem ORDER BY tokens_recompensa DESC:\n{}", semIndice);

    String porCidade =
        explicar(
            """
            SELECT m.id, m.titulo
              FROM missao m
             WHERE m.status = ?
               AND lower(m.cidade) = lower(CAST(? AS varchar))
             ORDER BY m.criada_em DESC
             LIMIT 20
            """,
            "ABERTA",
            "Manaus");
    log.info("EXPLAIN — listagem filtrando por lower(cidade):\n{}", porCidade);

    String porStatus =
        explicar("SELECT m.id FROM missao m WHERE m.status = ? LIMIT 20", "CONCLUIDA");
    log.info("EXPLAIN — filtro por status sem ordenação:\n{}", porStatus);
    log.info("Tamanho dos índices de `missao`:\n{}", tamanhoDosIndices("missao"));
    log.info("Tamanho dos índices de `alerta`:\n{}", tamanhoDosIndices("alerta"));

    assertThat(comIndice)
        .as("ordenar pela coluna que idx_missao_status_criada (V11) cobre tem de usá-lo")
        .contains("idx_missao_status_criada");

    // O registro do custo: sem índice sobre tokens_recompensa, as 200 mil linhas que casam com o
    // filtro precisam ser ORDENADAS para que o LIMIT 20 signifique alguma coisa. O `Sort` no plano
    // é
    // o achado — não uma falha do banco.
    assertThat(semIndice)
        .as("sem índice sobre tokens_recompensa, o LIMIT 20 exige ordenar o conjunto inteiro")
        .contains("\"Node Type\": \"Sort\"");

    // `uk_usuario_handle_lower` (V27) mostra que o projeto conhece o índice funcional; `missao` não
    // ganhou o equivalente. Sem ele nenhum índice pode servir o predicado — a função sobre a coluna
    // o torna inalcançável. É o registro do fato, não a decisão de corrigir.
    assertThat(porCidade)
        .as("não existe índice funcional sobre lower(cidade)")
        .doesNotContain("idx_missao_cidade");

    // idx_missao_status (status) é PREFIXO ESTRITO de idx_missao_status_criada (status, criada_em
    // DESC). A asserção NÃO é "o redundante nunca é usado" — um índice menor pode legitimamente
    // ganhar num scan sem ordenação, por ter menos páginas. O que se afirma é o verificável: a
    // consulta é servida por índice, e o log diz qual e quanto cada um ocupa. O julgamento sobre
    // remover fica para a priorização, com o tamanho medido ao lado.
    assertThat(porStatus).as("o filtro por status é servido por índice").contains("Index");
  }

  @Test
  @Transactional
  @DisplayName("extrato da carteira usa idx_lancamento_carteira_criado, página e contagem")
  void extrato_usa_o_indice_composto_em_carteira_e_data() {
    semearCarteirasELancamentos();
    jdbc.execute("ANALYZE lancamento");

    String pagina =
        explicar(
            """
            SELECT l.id, l.sinal, l.motivo, l.valor_tokens, l.criado_em
              FROM lancamento l
             WHERE l.carteira_id = CAST(? AS uuid)
             ORDER BY l.criado_em DESC
             LIMIT 20
            """,
            CARTEIRA_SONDADA);
    log.info("EXPLAIN — extrato paginado:\n{}", pagina);

    String contagem =
        explicar(
            "SELECT count(*) FROM lancamento l WHERE l.carteira_id = CAST(? AS uuid)",
            CARTEIRA_SONDADA);
    log.info("EXPLAIN — count da paginação do extrato:\n{}", contagem);

    assertThat(pagina)
        .as("idx_lancamento_carteira_criado (V13) cobre filtro E ordenação")
        .contains("idx_lancamento_carteira_criado");
    assertThat(pagina).as("com o índice servindo a ordem, não há Sort").doesNotContain("\"Sort\"");

    // O count de TODA página do extrato: é ele, e não a página, quem varre a chave inteira daquela
    // carteira. Registrar o plano é o que permite dizer depois se vale a pena trocá-lo por algo
    // como um `slice` sem total — decisão que este teste não toma.
    assertThat(contagem).as("a contagem também sai do índice, sem tocar a heap").contains("Index");
  }

  /**
   * Trava contra a divergência que a reprodução do SQL introduz. Não é o EXPLAIN — é a garantia de
   * que o EXPLAIN acima fala da consulta que a API executa.
   *
   * <p>As duas metades vêm de lugares DIFERENTES, e descobrir isso corrigiu a premissa deste
   * arquivo: o {@code lower(cidade)} está na JPQL de {@code buscarComFiltros}, mas a ordenação
   * <b>não está</b> — ela entra pelo {@code Sort} do {@code Pageable}, montado em {@code
   * MissaoService.listar} a partir de {@code MissaoFiltroRequest.CampoOrdenacao}. Procurar
   * "tokensRecompensa" na JPQL falha, e falharia mesmo com a listagem ordenando exatamente assim.
   *
   * <p>A trava mais forte não está aqui: {@code ContagemDeQueriesTest.registra_o_sql_real_da_
   * listagem} captura o SQL que o Hibernate emite e exige {@code tokens_recompensa} e {@code
   * lower(} dentro dele. Esta aqui cobre o caminho anterior — a whitelist que permite pedir a
   * ordenação.
   */
  @Test
  @DisplayName("a consulta de produção continua com a forma que este teste explica")
  void consulta_de_producao_continua_com_a_forma_que_este_teste_explica() {
    assertThat(lerJpqlDaListagem())
        .as("se o lower() sair do filtro de cidade, o plano medido perde o assunto")
        .contains("lower(m.cidade)");

    assertThat(MissaoFiltroRequest.CampoOrdenacao.TOKENS_RECOMPENSA.propriedade())
        .as("a whitelist precisa continuar oferecendo a coluna sem índice que o EXPLAIN mede")
        .isEqualTo("tokensRecompensa");
  }

  // ─── Apoio ─────────────────────────────────────────────────────────────────────────────────

  /** Nome e tamanho de cada índice da tabela, para que "redundante" venha com o custo junto. */
  private String tamanhoDosIndices(String tabela) {
    return String.join(
        "\n",
        jdbc.query(
            """
            SELECT indexrelname || ' = ' || pg_size_pretty(pg_relation_size(indexrelid))
              FROM pg_stat_user_indexes
             WHERE relname = ?
             ORDER BY pg_relation_size(indexrelid) DESC
            """,
            (rs, i) -> rs.getString(1),
            tabela));
  }

  private String explicar(String sql, Object... parametros) {
    return jdbc.queryForObject(
        "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql, String.class, parametros);
  }

  /**
   * Lê a anotação {@code @Query} de {@code buscarComFiltros} pela reflexão, e não de um arquivo:
   * assim a trava acompanha o código compilado, não o texto-fonte.
   */
  private String lerJpqlDaListagem() {
    try {
      return java.util.Arrays.stream(
              Class.forName("com.omnitribo.missoes.infra.MissaoRepository").getMethods())
          .filter(m -> "buscarComFiltros".equals(m.getName()))
          .map(m -> m.getAnnotation(org.springframework.data.jpa.repository.Query.class))
          .filter(q -> q != null)
          .map(org.springframework.data.jpa.repository.Query::value)
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("buscarComFiltros sem @Query"));
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("MissaoRepository não encontrado", e);
    }
  }

  /** Uma instrução só — 200 mil INSERT em laço levariam minutos e não provariam nada a mais. */
  private void semearMissoes() {
    jdbc.update(
        """
        INSERT INTO missao (id, criador_id, categoria, titulo, descricao, status,
                            xp_recompensa, valor_brl, tokens_recompensa, origem,
                            cep, logradouro, bairro, cidade, uf, raio_checkin_m,
                            janela_inicio, janela_fim, criada_em, versao)
        SELECT ('%s' || lpad(i::text, 12, '0'))::uuid,
               '%s'::uuid,
               'ENTREGA', 'plano ' || i, 'carga sintetica para prova de plano',
               'ABERTA',
               10, 0.00,
               -- Recompensa ESPALHADA de propósito: com valor constante o ORDER BY seria sobre uma
               -- coluna de cardinalidade 1, e o planner poderia dispensar a ordenação inteira.
               (random() * 500)::bigint,
               ST_SetSRID(ST_MakePoint(-73 + random() * 39, -33 + random() * 28), 4326)::geography,
               '00000000', 'rua', 'bairro',
               -- Cidades variadas: com uma só, o filtro por cidade não seria seletivo e o seq scan
               -- voltaria a ser a escolha correta.
               (ARRAY['Manaus','Sao Paulo','Recife','Curitiba'])[1 + (i %% 4)],
               'SP', 50,
               NOW(), NOW() + INTERVAL '30 days',
               NOW() - (i %% 10000) * INTERVAL '1 minute', 0
          FROM generate_series(1, %d) AS i
        """
            .formatted(PREFIXO_MISSAO, CRIADOR_SEED, MISSOES));
  }

  private void semearCarteirasELancamentos() {
    jdbc.update(
        """
        INSERT INTO usuario (id, nome, email, senha_hash, handle, tribo_id, xp, nivel, streak,
                             rating, papel, status, criado_em, atualizado_em, versao)
        SELECT ('%s' || lpad(i::text, 12, '0'))::uuid,
               'plano ' || i, 'plano+' || i || '@teste.dev',
               '{bcrypt}$2a$10$naoUsadoNesteTeste', 'plano_' || i,
               NULL, 0, 1, 0, 0.0, 'USUARIO', 'ATIVO', NOW(), NOW(), 0
          FROM generate_series(1, %d) AS i
        """
            .formatted(PREFIXO_USUARIO, CARTEIRAS));

    jdbc.update(
        """
        INSERT INTO carteira (id, usuario_id, saldo_brl, saldo_tokens, versao)
        SELECT ('%s' || lpad(i::text, 12, '0'))::uuid,
               ('%s' || lpad(i::text, 12, '0'))::uuid,
               0.00, %d, 0
          FROM generate_series(1, %d) AS i
        """
            .formatted(PREFIXO_CARTEIRA, PREFIXO_USUARIO, LANCAMENTOS_POR_CARTEIRA, CARTEIRAS));

    jdbc.update(
        """
        INSERT INTO lancamento (id, carteira_id, sinal, motivo, valor_brl, valor_tokens,
                                chave_idempotencia, saldo_apos_brl, saldo_apos_tokens, criado_em)
        SELECT ('%s' || lpad(c::text, 4, '0') || '-0000-0000-' || lpad(l::text, 12, '0'))::uuid,
               ('%s' || lpad(c::text, 12, '0'))::uuid,
               'CREDITO', 'BONUS', 0.00, 1,
               'plano-' || c || '-' || l,
               0.00, l,
               NOW() - l * INTERVAL '1 minute'
          FROM generate_series(1, %d) AS c, generate_series(1, %d) AS l
        """
            .formatted(PREFIXO_LANCAMENTO, PREFIXO_CARTEIRA, CARTEIRAS, LANCAMENTOS_POR_CARTEIRA));
  }

  /**
   * Cinto e suspensório, igual ao do irmão: o {@code @Transactional} já desfaz tudo, mas o
   * container é singleton para a JVM inteira e ninguém trunca essas tabelas entre classes.
   */
  @AfterAll
  static void limparResiduo(@Autowired JdbcTemplate jdbc) {
    jdbc.update("DELETE FROM lancamento WHERE id::text LIKE ?", PREFIXO_LANCAMENTO + "%");
    jdbc.update("DELETE FROM carteira WHERE id::text LIKE ?", PREFIXO_CARTEIRA + "%");
    jdbc.update("DELETE FROM usuario WHERE id::text LIKE ?", PREFIXO_USUARIO + "%");
    jdbc.update("DELETE FROM missao WHERE id::text LIKE ?", PREFIXO_MISSAO + "%");
  }
}
