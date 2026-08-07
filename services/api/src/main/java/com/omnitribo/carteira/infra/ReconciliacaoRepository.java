package com.omnitribo.carteira.infra;

import com.omnitribo.carteira.dominio.Carteira;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Conciliação do ledger contra a projeção de saldo.
 *
 * <p>Fica em {@code infra/} com {@code nativeQuery} pela convenção do projeto para SQL nativa.
 */
public interface ReconciliacaoRepository extends JpaRepository<Carteira, UUID> {

  /**
   * Devolve SOMENTE as carteiras cuja projeção diverge da soma do ledger.
   *
   * <p>UMA ÚNICA STATEMENT, e isso é a decisão central desta consulta. Sob READ COMMITTED cada
   * statement enxerga um snapshot próprio: em duas consultas separadas — uma somando o ledger,
   * outra lendo os saldos — um commit concorrente entre elas produziria uma divergência FANTASMA, e
   * a reconciliação acusaria corrupção onde não há. Numa statement só, o snapshot é o mesmo por
   * definição e o resultado é consistente sem precisar elevar o nível de isolamento.
   *
   * <p>De quebra é O(1) consultas em vez de O(carteiras) — a alternativa de iterar carteira a
   * carteira não escala e ainda reintroduziria o problema do snapshot.
   *
   * <p>{@code LEFT JOIN}: carteira sem nenhum lançamento é o caso normal de usuário recém-criado.
   * Com {@code INNER JOIN} ela sumiria do resultado, e uma carteira com saldo positivo e ZERO
   * lançamentos — que é exatamente a corrupção mais grave possível — passaria despercebida.
   *
   * <p>O sinal mora na coluna {@code sinal}; os valores são sempre não-negativos por {@code
   * ck_lancamento_valores_nao_negativos} (V13). Por isso o {@code CASE} pode somar cegamente.
   */
  @Query(
      value =
          """
          SELECT c.id                AS carteiraId,
                 c.usuario_id        AS usuarioId,
                 c.saldo_brl         AS saldoBrlRegistrado,
                 c.saldo_tokens      AS saldoTokensRegistrado,
                 COALESCE(l.soma_brl, 0)    AS saldoBrlLedger,
                 COALESCE(l.soma_tokens, 0) AS saldoTokensLedger
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
          ORDER BY c.id
          """,
      nativeQuery = true)
  List<DivergenciaProjecao> buscarDivergencias();

  /** Total de carteiras existentes, para o relatório dizer quantas foram verificadas. */
  @Query(value = "SELECT COUNT(*) FROM carteira", nativeQuery = true)
  long contarCarteiras();

  /** Projeção de interface — o Spring Data materializa direto do ResultSet, sem entidade. */
  interface DivergenciaProjecao {
    UUID getCarteiraId();

    UUID getUsuarioId();

    java.math.BigDecimal getSaldoBrlRegistrado();

    long getSaldoTokensRegistrado();

    java.math.BigDecimal getSaldoBrlLedger();

    long getSaldoTokensLedger();
  }
}
