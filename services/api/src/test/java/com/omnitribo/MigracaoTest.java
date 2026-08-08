package com.omnitribo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class MigracaoTest extends TesteIntegracaoBase {

  @Autowired DataSource dataSource;
  @Autowired JdbcTemplate jdbcTemplate;

  @Test
  void migrations_criam_schema_completo() throws Exception {
    List<String> tabelasEsperadas =
        List.of(
            "tribo",
            "usuario",
            "consentimento",
            "refresh_token",
            "dispositivo",
            "auditoria",
            "missao",
            "missao_evento",
            "checkin",
            "carteira",
            "lancamento",
            "ponto_custodia",
            "entrega_falida",
            "outbox",
            "alerta");

    List<String> tabelasExistentes = new ArrayList<>();
    try (var conn = dataSource.getConnection()) {
      DatabaseMetaData meta = conn.getMetaData();
      try (ResultSet rs = meta.getTables(null, "public", "%", new String[] {"TABLE"})) {
        while (rs.next()) {
          tabelasExistentes.add(rs.getString("TABLE_NAME"));
        }
      }
    }

    assertThat(tabelasExistentes).containsAll(tabelasEsperadas);
  }

  @Test
  void seed_carregado_com_dados_do_dominio_leroy_merlin() {
    long tribos = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tribo", Long.class);
    long usuarios = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM usuario", Long.class);
    long missoes = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM missao", Long.class);
    long pontosCustodia =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ponto_custodia", Long.class);

    assertThat(tribos).isGreaterThanOrEqualTo(3);
    assertThat(usuarios).isGreaterThanOrEqualTo(6);
    assertThat(missoes).isGreaterThanOrEqualTo(12);
    assertThat(pontosCustodia).isGreaterThanOrEqualTo(5);

    // Verifica que há missões ENTREGA com peso e volume preenchidos (domínio correto)
    long entregasComPeso =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM missao WHERE categoria = 'ENTREGA' AND peso_kg IS NOT NULL",
            Long.class);
    assertThat(entregasComPeso).isGreaterThanOrEqualTo(1);

    // Verifica que nenhuma missão TRIBO/COLETA tem valor_brl > 0 (invariante econômica)
    long violacoes =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM missao WHERE categoria IN ('TRIBO','COLETA') AND valor_brl > 0",
            Long.class);
    assertThat(violacoes).isZero();
  }

  /**
   * A tese do produto tem dado dos DOIS lados: entrega falhada que já virou missão, e entrega
   * parada na custódia esperando alguém criar a missão de retirada.
   *
   * <p>A segunda população é a que importa travar: sem nenhuma linha pendente, a tela de
   * oportunidades do app só poderia ser demonstrada com dados criados à mão, e o seed não
   * sustentaria a narrativa que dá nome ao challenge.
   */
  @Test
  void seed_tem_entregas_falidas_convertidas_e_pendentes() {
    long convertidas =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM entrega_falida WHERE missao_id IS NOT NULL", Long.class);
    long pendentes =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM entrega_falida WHERE missao_id IS NULL", Long.class);

    assertThat(convertidas).isPositive();
    assertThat(pendentes).isPositive();

    // Toda convertida aponta para missão que existe. Não há FK (fronteira logistica→missoes é
    // deliberadamente sem constraint), então nada além desta assertion impede o seed de apontar
    // para um UUID inexistente.
    long orfas =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM entrega_falida ef
             WHERE ef.missao_id IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM missao m WHERE m.id = ef.missao_id)
            """,
            Long.class);
    assertThat(orfas).as("entrega_falida apontando para missao inexistente").isZero();

    // ocupacao de cada ponto == encomendas fisicamente lá: pendentes + convertidas cuja missão
    // ainda não concluiu. Encomenda de missão CONCLUIDA já saiu da custódia.
    long incoerentes =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM (
              SELECT pc.id
                FROM ponto_custodia pc
                LEFT JOIN entrega_falida ef ON ef.ponto_custodia_id = pc.id
                LEFT JOIN missao m          ON m.id = ef.missao_id
               GROUP BY pc.id, pc.ocupacao
              HAVING pc.ocupacao <>
                     COUNT(*) FILTER (WHERE ef.id IS NOT NULL AND ef.missao_id IS NULL)
                   + COUNT(*) FILTER (WHERE ef.missao_id IS NOT NULL AND m.status <> 'CONCLUIDA')
            ) divergentes
            """,
            Long.class);
    assertThat(incoerentes)
        .as("ponto_custodia.ocupacao divergente das encomendas em custódia")
        .isZero();
  }

  @Test
  void soma_do_ledger_igual_ao_saldo_de_cada_carteira_semeada() {
    // Para cada carteira, soma do ledger deve igualar o saldo registrado
    List<Map<String, Object>> carteiras = jdbcTemplate.queryForList("SELECT * FROM carteira");

    for (Map<String, Object> carteira : carteiras) {
      UUID carteiraId = (UUID) carteira.get("id");

      // Soma BRL: CREDITO positivo, DEBITO negativo
      BigDecimal saldoLedgerBrl =
          jdbcTemplate.queryForObject(
              """
              SELECT COALESCE(SUM(
                  CASE WHEN sinal = 'CREDITO' THEN valor_brl ELSE -valor_brl END
              ), 0) FROM lancamento WHERE carteira_id = ?
              """,
              BigDecimal.class,
              carteiraId);

      // Soma tokens: CREDITO positivo, DEBITO negativo
      Long saldoLedgerTokens =
          jdbcTemplate.queryForObject(
              """
              SELECT COALESCE(SUM(
                  CASE WHEN sinal = 'CREDITO' THEN valor_tokens ELSE -valor_tokens END
              ), 0) FROM lancamento WHERE carteira_id = ?
              """,
              Long.class,
              carteiraId);

      BigDecimal saldoRegistradoBrl = (BigDecimal) carteira.get("saldo_brl");
      long saldoRegistradoTokens = (long) carteira.get("saldo_tokens");

      assertThat(saldoLedgerBrl)
          .as("saldo_brl da carteira %s não bate com o ledger", carteiraId)
          .isEqualByComparingTo(saldoRegistradoBrl);

      assertThat(saldoLedgerTokens)
          .as("saldo_tokens da carteira %s não bate com o ledger", carteiraId)
          .isEqualTo(saldoRegistradoTokens);
    }
  }

  // NOT_SUPPORTED: executa fora de transação, garantindo que cada INSERT seja commitado
  // imediatamente. Necessário para que a UNIQUE constraint seja verificada pelo banco
  // ao tentar inserir o segundo lançamento com a mesma chave_idempotencia.
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void lancamento_duplicado_falha_por_constraint_do_banco() {
    UUID carteiraId = jdbcTemplate.queryForObject("SELECT id FROM carteira LIMIT 1", UUID.class);
    String chaveUnica = "idem-constraint-test-" + UUID.randomUUID();

    // Primeiro INSERT deve ter sucesso
    jdbcTemplate.update(
        """
        INSERT INTO lancamento
            (id, carteira_id, sinal, motivo, valor_brl, valor_tokens,
             chave_idempotencia, saldo_apos_brl, saldo_apos_tokens, criado_em)
        VALUES (gen_random_uuid(), ?, 'CREDITO', 'BONUS', 1.00, 0, ?, 1.00, 0, ?)
        """,
        carteiraId,
        chaveUnica,
        Timestamp.from(Instant.now()));

    // Segundo INSERT com a mesma chave_idempotencia deve falhar com constraint do banco
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                    INSERT INTO lancamento
                        (id, carteira_id, sinal, motivo, valor_brl, valor_tokens,
                         chave_idempotencia, saldo_apos_brl, saldo_apos_tokens, criado_em)
                    VALUES (gen_random_uuid(), ?, 'CREDITO', 'BONUS', 1.00, 0, ?, 2.00, 0, ?)
                    """,
                    carteiraId,
                    chaveUnica,
                    Timestamp.from(Instant.now())))
        // DuplicateKeyException, e não DataIntegrityViolationException: a segunda é a superclasse
        // que cobre NOT NULL, CHECK e FK também. Com ela, derrubar uk_lancamento_idempotencia e
        // quebrar a idempotência do ledger deixaria este teste VERDE, desde que o INSERT falhasse
        // por qualquer outro motivo. O nome da constraint é conferido pela mesma razão: é a
        // afirmação de que foi ESTA garantia que atuou.
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessageContaining("uk_lancamento_idempotencia");

    // Limpeza do registro inserido (teste fora de transação não tem rollback automático)
    jdbcTemplate.update("DELETE FROM lancamento WHERE chave_idempotencia = ?", chaveUnica);
  }

  /**
   * Trava a matriz de privilégios das tabelas append-only.
   *
   * <p>O {@code REVOKE UPDATE, DELETE} das migrations não tinha nenhuma assertion: apagar a linha
   * de uma migration não quebraria build nenhum, e a garantia de imutabilidade do ledger e da
   * trilha de auditoria — que é argumento de defesa oral — sumiria em silêncio.
   *
   * <p><b>Este teste prova que o papel está correto, NÃO que a aplicação o use.</b> Hoje o
   * datasource conecta como {@code omnitribo}, dono das tabelas, para quem o REVOKE não vale — a
   * proteção existe no banco e está desligada em runtime. Ver Pendências conhecidas no CLAUDE.md.
   */
  @Test
  void tabelas_append_only_negam_update_e_delete_ao_papel_de_aplicacao() {
    List<String> appendOnly = List.of("lancamento", "auditoria", "checkin", "missao_evento");

    for (String tabela : appendOnly) {
      List<String> privilegios =
          jdbcTemplate.queryForList(
              """
              SELECT privilege_type FROM information_schema.role_table_grants
               WHERE grantee = 'omnitribo_app' AND table_name = ?
              """,
              String.class,
              tabela);

      assertThat(privilegios)
          .as("%s é append-only: omnitribo_app precisa poder ler e inserir", tabela)
          .contains("SELECT", "INSERT");
      assertThat(privilegios)
          .as("%s é append-only: UPDATE/DELETE devem estar revogados de omnitribo_app", tabela)
          .doesNotContain("UPDATE", "DELETE");
    }
  }
}
