package com.omnitribo.logistica.infra;

import com.omnitribo.logistica.dominio.EntregaFalida;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EntregaFalidaRepository extends JpaRepository<EntregaFalida, UUID> {

  /**
   * Sondagem de idempotência do webhook pela chave natural (UNIQUE desde a V21). Chamada SOB o lock
   * do ponto de custódia — sondar antes de travar reabriria a corrida.
   */
  Optional<EntregaFalida> findByTransportadoraAndCodigoRastreio(
      String transportadora, String codigoRastreio);

  /** Baixa da custódia na conclusão da missão. */
  Optional<EntregaFalida> findByMissaoId(UUID missaoId);

  /**
   * Os quatro desfechos do webhook numa statement (ADR 0029): separados, um webhook entre as
   * leituras faria os desfechos não somarem o total. {@code pendentes} — sem missão e sem recusa —
   * também conta, para a taxa não ter um grupo invisível no denominador.
   */
  @Query(
      value =
          """
          SELECT COUNT(*)                                                          AS recebidas,
                 COUNT(*) FILTER (WHERE missao_id IS NOT NULL)                     AS convertidas,
                 COUNT(*) FILTER (WHERE missao_id IS NULL
                                    AND motivo_recusa IS NULL)                      AS pendentes,
                 COUNT(*) FILTER (WHERE motivo_recusa = 'PONTO_LOTADO')            AS lotado,
                 COUNT(*) FILTER (WHERE motivo_recusa = 'SEM_PATROCINIO')          AS semPatrocinio
          FROM entrega_falida
          """,
      nativeQuery = true)
  DesfechosProjecao contarDesfechos();

  /** {@code (missao_id, recebido_em)} das convertidas — metade da mediana "webhook → check-in". */
  @Query(
      value =
          """
          SELECT missao_id AS missaoId, recebido_em AS recebidoEm
          FROM entrega_falida
          WHERE missao_id IS NOT NULL
          """,
      nativeQuery = true)
  List<RecebimentoProjecao> buscarRecebimentoPorMissao();

  /** Projeção de interface: o Spring Data materializa do ResultSet, sem entidade no caminho. */
  interface DesfechosProjecao {
    long getRecebidas();

    long getConvertidas();

    long getPendentes();

    long getLotado();

    long getSemPatrocinio();
  }

  /**
   * Frequência de recusa por ponto e motivo — o número que o alerta deduplicado deixou de guardar
   * (ADR 0033). Agregado na hora, sem tabela nem cache (argumento do ADR 0029), pelo índice parcial
   * da V29. Por ponto e não por transportadora: a pergunta é capacidade do ponto.
   */
  @Query(
      value =
          """
          SELECT ef.ponto_custodia_id AS pontoCustodiaId,
                 pc.codigo            AS codigo,
                 pc.apelido           AS apelido,
                 pc.capacidade        AS capacidade,
                 ef.motivo_recusa     AS motivo,
                 COUNT(*)             AS recusas,
                 MIN(ef.recusada_em)  AS primeiraRecusa,
                 MAX(ef.recusada_em)  AS ultimaRecusa
          FROM entrega_falida ef
          JOIN ponto_custodia pc ON pc.id = ef.ponto_custodia_id
          WHERE ef.recusada_em IS NOT NULL
            AND ef.recusada_em >= :desde
          GROUP BY ef.ponto_custodia_id, pc.codigo, pc.apelido, pc.capacidade, ef.motivo_recusa
          ORDER BY COUNT(*) DESC, MAX(ef.recusada_em) DESC
          """,
      countQuery =
          """
          SELECT COUNT(*) FROM (
            SELECT 1
            FROM entrega_falida ef
            WHERE ef.recusada_em IS NOT NULL
              AND ef.recusada_em >= :desde
            GROUP BY ef.ponto_custodia_id, ef.motivo_recusa
          ) grupos
          """,
      nativeQuery = true)
  Page<RecusaProjecao> agruparRecusas(@Param("desde") Instant desde, Pageable paginacao);

  interface RecebimentoProjecao {
    UUID getMissaoId();

    java.time.Instant getRecebidoEm();
  }

  /**
   * Uma linha do agregado. O {@code countQuery} conta GRUPOS, não recusas, para a paginação bater.
   */
  interface RecusaProjecao {
    UUID getPontoCustodiaId();

    String getCodigo();

    String getApelido();

    int getCapacidade();

    String getMotivo();

    long getRecusas();

    Instant getPrimeiraRecusa();

    Instant getUltimaRecusa();
  }
}
