package com.omnitribo.carteira;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Apoio compartilhado dos testes de carteira.
 *
 * <p>{@link #assertLedgerReconcilia} é a asserção que fecha TODO teste de concorrência desta fase:
 * depois de N threads brigando pela mesma linha, a soma do ledger ainda precisa bater com a
 * projeção de saldo de cada carteira. Sem ela, um teste de concorrência prova apenas que os códigos
 * HTTP saíram certos — o que é compatível com um ledger corrompido.
 *
 * <p>Consulta direta ao banco em vez do endpoint admin: assim a asserção vale também para testes
 * que não sobem MockMvc, e não depende de um endpoint que ela mesma deveria poder auditar.
 */
public final class SuporteCarteira {

  private SuporteCarteira() {}

  /**
   * Falha se alguma carteira divergir da soma dos seus lançamentos.
   *
   * <p>Uma única statement, pelo mesmo motivo do {@code ReconciliacaoRepository}: sob READ
   * COMMITTED, somar e ler saldo em consultas separadas pode straddle um commit concorrente e
   * acusar divergência fantasma.
   */
  public static void assertLedgerReconcilia(JdbcTemplate jdbc) {
    List<Map<String, Object>> divergencias =
        jdbc.queryForList(
            """
            SELECT c.id,
                   c.saldo_brl,
                   c.saldo_tokens,
                   COALESCE(l.soma_brl, 0)    AS soma_brl,
                   COALESCE(l.soma_tokens, 0) AS soma_tokens
            FROM carteira c
            LEFT JOIN (
                SELECT carteira_id,
                       SUM(CASE WHEN sinal = 'CREDITO' THEN valor_brl    ELSE -valor_brl    END) AS soma_brl,
                       SUM(CASE WHEN sinal = 'CREDITO' THEN valor_tokens ELSE -valor_tokens END) AS soma_tokens
                FROM lancamento
                GROUP BY carteira_id
            ) l ON l.carteira_id = c.id
            WHERE c.saldo_brl    <> COALESCE(l.soma_brl, 0)
               OR c.saldo_tokens <> COALESCE(l.soma_tokens, 0)
            """);

    assertThat(divergencias)
        .as("saldo de toda carteira tem de ser exatamente a soma do seu ledger")
        .isEmpty();
  }

  /** Soma de tokens em circulação: carteiras + potes em custódia. Constante num ciclo fechado. */
  public static long tokensEmCirculacao(JdbcTemplate jdbc) {
    Long emCarteiras =
        jdbc.queryForObject("SELECT COALESCE(SUM(saldo_tokens), 0) FROM carteira", Long.class);
    Long emPotes =
        jdbc.queryForObject("SELECT COALESCE(SUM(pote_tokens), 0) FROM missao", Long.class);
    return emCarteiras + emPotes;
  }

  public static long saldoTokens(JdbcTemplate jdbc, UUID usuarioId) {
    return jdbc.queryForObject(
        "SELECT saldo_tokens FROM carteira WHERE usuario_id = ?", Long.class, usuarioId);
  }

  public static BigDecimal saldoBrl(JdbcTemplate jdbc, UUID usuarioId) {
    return jdbc.queryForObject(
        "SELECT saldo_brl FROM carteira WHERE usuario_id = ?", BigDecimal.class, usuarioId);
  }

  public static long contarLancamentosDaMissao(JdbcTemplate jdbc, UUID missaoId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM lancamento WHERE missao_id = ?", Long.class, missaoId);
  }

  /** Remove os rastros de uma missão, filhos primeiro, para não violar FK. */
  public static void limparMissao(JdbcTemplate jdbc, UUID missaoId) {
    jdbc.update("DELETE FROM outbox WHERE agregado_id = ?", missaoId);
    jdbc.update("DELETE FROM alerta WHERE missao_id = ?", missaoId);
    jdbc.update("DELETE FROM missao_evento WHERE missao_id = ?", missaoId);
    jdbc.update("DELETE FROM missao WHERE id = ?", missaoId);
  }
}
