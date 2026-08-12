package com.omnitribo.compartilhado.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnitribo.TesteIntegracaoBase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prova que a busca por proximidade usa o índice GiST idx_missao_origem (V8).
 *
 * <p>Por que o teste é grande: em 12 linhas o PostgreSQL faz seq scan, e faz certo. Um EXPLAIN
 * sobre tabela minúscula não prova nada sobre o comportamento em produção. E {@code SET
 * enable_seqscan = off} prova menos ainda — prova que o índice PODE ser usado, não que o planner o
 * ESCOLHE, que é a única coisa que interessa. As duas recusas estão registradas em
 * docs/evidencias/f6-explain-analyze.md.
 *
 * <p>Todas as 200 mil linhas sintéticas nascem ABERTA de propósito: isso torna idx_missao_status
 * inútil (praticamente toda linha casa) e deixa idx_missao_origem como único caminho seletivo. Com
 * status misturado, o planner poderia legitimamente preferir o B-tree e a prova seria sobre o
 * índice errado.
 *
 * <p>A saída real do EXPLAIN é impressa no log para ser colada na evidência.
 */
@Tag("geo")
class IndiceGeoespacialTest extends TesteIntegracaoBase {

  private static final Logger log = LoggerFactory.getLogger(IndiceGeoespacialTest.class);

  private static final int LINHAS_SINTETICAS = 200_000;

  // Prefixo sentinela: o seed usa dddddddd-…, MissaoControllerTest usa randomUUID. Sem colisão.
  private static final String PREFIXO_SINTETICO = "eeee0000-0000-0000-0000-";

  private static final String CRIADOR_SEED = "bbbbbbbb-0000-0000-0000-000000000001";

  @Autowired JdbcTemplate jdbc;

  @Test
  @Transactional // rollback do Spring: as 200 mil linhas nunca chegam a ser commitadas
  void busca_por_proximidade_usa_o_indice_gist() {
    semearCargaSintetica();

    // Obrigatório, não opcional: o PostGIS deriva a seletividade de ST_DWithin das estatísticas que
    // só o ANALYZE coleta. Sem ele o planner cai num palpite fixo e o plano não é evidência de
    // nada.
    // ANALYZE é legal dentro de bloco de transação (VACUUM não é) e o que grava em pg_statistic
    // volta atrás junto com o rollback.
    jdbc.execute("ANALYZE missao");

    String plano = explicarConsultaDeProducao();
    log.info("EXPLAIN (ANALYZE, BUFFERS) da busca por proximidade:\n{}", plano);

    assertThat(plano)
        .as("o planner precisa escolher idx_missao_origem, não varrer a tabela")
        .contains("idx_missao_origem");

    // Index Scan ou Bitmap Index Scan: qual dos dois o PostGIS escolhe depende de random_page_cost
    // e da contagem de linhas. Fixar um só deixaria o teste instável sem provar nada a mais.
    assertThat(plano)
        .as("acesso tem de ser por índice")
        .containsPattern("\"Node Type\": \"(Index Scan|Bitmap Index Scan)\"");

    assertThat(plano)
        .as("não pode haver varredura sequencial em missao")
        .doesNotContain("Seq Scan");

    // Guarda contra prova vazia. A remapeação de parâmetros nomeados para posicionais em
    // explicarConsultaDeProducao depende da ORDEM em que os placeholders aparecem no SQL. Se alguém
    // reordenar :lat e :lon dentro da constante, a sonda cai no meio do Atlântico, a consulta
    // devolve zero linhas — e o plano continua dizendo "Index Scan on idx_missao_origem", verde e
    // sem valor. Exigir linhas de verdade fecha isso.
    assertThat(plano)
        .as("a sonda precisa encontrar linhas, senão o plano não prova nada")
        .doesNotContain("\"Actual Rows\": 0,");
  }

  /** Uma única instrução: 200 mil INSERT em laço levariam minutos e não provariam nada a mais. */
  private void semearCargaSintetica() {
    jdbc.update(
        """
        INSERT INTO missao (id, criador_id, categoria, titulo, descricao, status,
                            xp_recompensa, valor_brl, tokens_recompensa, origem,
                            cep, logradouro, bairro, cidade, uf, raio_checkin_m,
                            janela_inicio, janela_fim, criada_em, versao)
        SELECT ('%s' || lpad(i::text, 12, '0'))::uuid,
               '%s'::uuid,
               'ENTREGA', 'carga ' || i, 'carga sintetica para prova de indice',
               'ABERTA',
               10, 0.00, 0,
               -- Brasil inteiro: lon -73..-34, lat -33..-5. Espalhar é essencial: carga
               -- concentrada tornaria o raio de 2 km pouco seletivo e o seq scan seria correto.
               ST_SetSRID(ST_MakePoint(-73 + random() * 39, -33 + random() * 28), 4326)::geography,
               '00000000', 'rua', 'bairro', 'cidade', 'SP', 50,
               NOW(), NOW() + INTERVAL '30 days', NOW(), 0
          FROM generate_series(1, %d) AS i
        """
            .formatted(PREFIXO_SINTETICO, CRIADOR_SEED, LINHAS_SINTETICAS));
  }

  /**
   * Roda o SQL de produção verbatim — a constante de {@link ConsultasGeoespaciaisPostgis}, não uma
   * cópia. Se a query mudar e parar de usar o índice, este teste quebra; uma cópia local
   * silenciaria isso.
   *
   * <p>Os parâmetros nomeados viram placeholders posicionais porque EXPLAIN aqui vai por
   * JdbcTemplate. Continuam bindados — nenhum valor entra por concatenação.
   */
  private String explicarConsultaDeProducao() {
    String sqlPosicional =
        ConsultasGeoespaciaisPostgis.SQL_MISSOES_NO_RAIO
            .replace(":lon", "?")
            .replace(":lat", "?")
            .replace(":raio", "?")
            .replace(":status", "?")
            .replace(":categoria", "?")
            .replace(":limite", "?");

    // Ordem dos ? conforme aparecem no SQL: lon, lat (SELECT), lon, lat, raio (WHERE),
    // status, categoria, categoria, limite.
    return jdbc.queryForObject(
        "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sqlPosicional,
        String.class,
        -46.6996,
        -23.5629,
        -46.6996,
        -23.5629,
        2000.0,
        "ABERTA",
        null,
        null,
        50);
  }

  /**
   * Cinto e suspensório. O @Transactional já garante o rollback; isto cobre o dia em que alguém
   * remover a anotação sem perceber que a tabela é compartilhada por toda a suíte (o container é
   * singleton e nunca é truncado).
   */
  @AfterAll
  static void limparResiduo(@Autowired JdbcTemplate jdbc) {
    jdbc.update("DELETE FROM missao WHERE id::text LIKE ?", PREFIXO_SINTETICO + "%");
  }
}
