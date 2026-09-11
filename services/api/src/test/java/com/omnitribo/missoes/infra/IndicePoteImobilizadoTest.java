package com.omnitribo.missoes.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnitribo.TesteIntegracaoBase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prova que o diagnóstico de pote imobilizado usa {@code idx_missao_pote_imobilizado} (V28).
 *
 * <p>Mesmo molde e mesmas recusas de {@code IndiceGeoespacialTest}: em 20 linhas o PostgreSQL faz
 * seq scan e faz CERTO, então a prova exige carga; e {@code SET enable_seqscan = off} provaria
 * apenas que o índice PODE ser usado, não que o planner o ESCOLHE — que é a única coisa que
 * interessa.
 *
 * <h2>O que este teste NÃO prova, e vale dizer em vez de deixar supor</h2>
 *
 * <p>Ele explica um SQL escrito aqui, não o SQL que o Hibernate emite a partir do JPQL de {@link
 * MissaoRepository#buscarPotesImobilizados}. Não há constante de SQL para reusar, como {@code
 * ConsultasGeoespaciaisPostgis.SQL_MISSOES_NO_RAIO} permitiu ao teste geoespacial. Logo: a
 * CORRETUDE da consulta de produção é coberta por {@code PoteImobilizadoTest}, que a executa pelo
 * endpoint; o que se prova aqui é que o índice da V28 serve a esta FORMA de predicado e que o
 * planner o prefere sob carga.
 *
 * <p><b>E ele NÃO pega a degradação por {@code IN} com dois statuses</b>, que a V28 mede. Com
 * {@code medidosPorJanelaFim()} devolvendo dois valores a expressão deixa de casar com a indexada,
 * mas o índice continua sendo usado pelo predicado PARCIAL — vira Bitmap Index Scan, 5× mais lento
 * e ainda 3× mais rápido que o Seq Scan. As duas assertions abaixo continuariam verdes, e é
 * deliberado que continuem: apertar para exigir {@code Index Scan} puro tornaria o teste refém de
 * uma escolha de custo do planner, que é exatamente a instabilidade que {@code
 * IndiceGeoespacialTest} já recusou. O que este teste pega é o índice deixar de ser usado — porque
 * foi removido, ou porque o predicado saiu da cobertura do índice parcial.
 */
class IndicePoteImobilizadoTest extends TesteIntegracaoBase {

  private static final Logger log = LoggerFactory.getLogger(IndicePoteImobilizadoTest.class);

  private static final int LINHAS_SINTETICAS = 50_000;

  /** Prefixo sentinela próprio: o seed usa dddddddd-…, o teste geoespacial usa eeee0000-…. */
  private static final String PREFIXO_SINTETICO = "eeee1111-0000-0000-0000-";

  private static final String CRIADOR_SEED = "bbbbbbbb-0000-0000-0000-000000000001";

  @Autowired JdbcTemplate jdbc;

  @Test
  @DisplayName("o planner escolhe idx_missao_pote_imobilizado em vez de varrer missao")
  @Transactional // rollback do Spring: as 50 mil linhas nunca chegam a ser commitadas
  void diagnostico_de_pote_usa_o_indice_parcial() {
    semearCargaSintetica();

    // Obrigatório: sem ANALYZE o planner opera com estimativas de tabela vazia e o plano não é
    // evidência de nada. ANALYZE é legal dentro de bloco de transação (VACUUM não é) e o que ele
    // grava em pg_statistic volta atrás junto com o rollback.
    jdbc.execute("ANALYZE missao");

    String plano = explicarDiagnostico();
    log.info("EXPLAIN (ANALYZE, BUFFERS) do diagnóstico de pote imobilizado:\n{}", plano);

    assertThat(plano)
        .as("o planner precisa escolher o índice parcial da V28, não varrer missao inteira")
        .contains("idx_missao_pote_imobilizado");

    assertThat(plano)
        .as("acesso tem de ser por índice")
        .containsPattern("\"Node Type\": \"(Index Scan|Bitmap Index Scan)\"");

    assertThat(plano)
        .as("não pode haver varredura sequencial em missao")
        .doesNotContain("Seq Scan");

    // Guarda contra prova vazia, no mesmo espírito do teste geoespacial: um predicado que não casa
    // nada devolveria zero linhas e o plano ainda diria "Index Scan", verde e sem valor. A carga é
    // montada para produzir ~500 candidatas, e exigir linhas de verdade fecha isso.
    assertThat(plano)
        .as("a consulta precisa encontrar potes imobilizados, senão o plano não prova nada")
        .doesNotContain("\"Actual Rows\": 0,");
  }

  /**
   * Uma única instrução, com a forma REAL do problema: o conjunto procurado é minúsculo dentro de
   * uma tabela grande.
   *
   * <p>Só 1 em cada 10 linhas segura pote e só 1 em cada 100 está parada além do limiar — se a
   * maioria fosse candidata, o seq scan seria a escolha CERTA do planner e o teste estaria medindo
   * uma situação que não acontece.
   */
  private void semearCargaSintetica() {
    jdbc.update(
        """
        INSERT INTO missao (id, criador_id, categoria, titulo, descricao, status,
                            xp_recompensa, valor_brl, tokens_recompensa, origem,
                            cep, logradouro, bairro, cidade, uf, raio_checkin_m,
                            janela_inicio, janela_fim, criada_em, versao,
                            pote_tokens, estado_desde, nivel_minimo, fonte_pote)
        SELECT ('%s' || lpad(i::text, 12, '0'))::uuid,
               '%s'::uuid,
               'TRIBO', 'carga pote ' || i, 'carga sintetica para prova de indice',
               -- Os SEIS estados não-terminais, para que o predicado NOT IN (terminais) não vire
               -- trivialmente verdadeiro nem trivialmente falso.
               (ARRAY['RASCUNHO','ABERTA','ACEITA','EM_ANDAMENTO',
                      'AGUARDANDO_CONFIRMACAO','EM_DISPUTA'])[1 + (i %% 6)],
               10, 0.00, 100,
               ST_SetSRID(ST_MakePoint(-46.6996, -23.5629), 4326)::geography,
               '00000000', 'rua', 'bairro', 'cidade', 'SP', 50,
               NOW() - INTERVAL '40 days',
               -- ABERTA mede por janela_fim: quase toda oferta viva, 1 em 500 vencida.
               CASE WHEN i %% 500 = 0 THEN NOW() - INTERVAL '30 days'
                    ELSE NOW() + INTERVAL '30 days' END,
               NOW() - INTERVAL '40 days', 0,
               CASE WHEN i %% 10 = 0 THEN 100 ELSE 0 END,
               CASE WHEN i %% 100 = 0 THEN NOW() - INTERVAL '30 days'
                    ELSE NOW() - INTERVAL '1 hour' END,
               1, 'COMUNIDADE'
          FROM generate_series(1, %d) AS i
        """
            .formatted(PREFIXO_SINTETICO, CRIADOR_SEED, LINHAS_SINTETICAS));
  }

  /**
   * A listagem do diagnóstico, na forma que o JPQL de {@code buscarPotesImobilizados} descreve.
   *
   * <p>Valores bindados, zero concatenação — inclusive o limiar, que entra como intervalo
   * parametrizado.
   */
  private String explicarDiagnostico() {
    return jdbc.queryForObject(
        """
        EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
        SELECT m.* FROM missao m
        WHERE m.pote_tokens > 0
          AND m.status NOT IN ('CONCLUIDA', 'CANCELADA', 'EXPIRADA')
          AND (CASE WHEN m.status IN ('ABERTA') THEN m.janela_fim ELSE m.estado_desde END)
              < NOW() - (? * INTERVAL '1 hour')
        ORDER BY (CASE WHEN m.status IN ('ABERTA') THEN m.janela_fim ELSE m.estado_desde END) ASC,
                 m.id ASC
        LIMIT 20
        """,
        String.class,
        96);
  }

  /**
   * Cinto e suspensório, como no teste geoespacial: o {@code @Transactional} já garante o rollback,
   * mas o container é singleton e a tabela nunca é truncada entre classes.
   */
  @AfterAll
  static void limparResiduo(@Autowired JdbcTemplate jdbc) {
    jdbc.update("DELETE FROM missao WHERE id::text LIKE ?", PREFIXO_SINTETICO + "%");
  }
}
