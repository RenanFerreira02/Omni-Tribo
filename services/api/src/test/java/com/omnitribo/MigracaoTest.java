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
import org.springframework.dao.DataIntegrityViolationException;
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
        .isInstanceOf(DataIntegrityViolationException.class);

    // Limpeza do registro inserido (teste fora de transação não tem rollback automático)
    jdbcTemplate.update("DELETE FROM lancamento WHERE chave_idempotencia = ?", chaveUnica);
  }
}
