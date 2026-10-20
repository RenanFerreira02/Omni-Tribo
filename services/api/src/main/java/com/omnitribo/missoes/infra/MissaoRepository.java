package com.omnitribo.missoes.infra;

import com.omnitribo.missoes.dominio.CategoriaMissao;
import com.omnitribo.missoes.dominio.DiagnosticoPotesService.ContagemPotes;
import com.omnitribo.missoes.dominio.ExpiracaoMissoesService.Candidata;
import com.omnitribo.missoes.dominio.Missao;
import com.omnitribo.missoes.dominio.StatusMissao;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface MissaoRepository extends JpaRepository<Missao, UUID> {

  /**
   * SELECT ... FOR UPDATE na missão: exatamente um vencedor no aceite concorrente (ADR 0006).
   *
   * <p>Lock pessimista e não @Version: o perdedor bloqueia por microssegundos, relê a linha já
   * ACEITA e recebe o MESMO 409 de qualquer transição inválida — um caminho de erro só. @Version
   * fica como defesa nos caminhos que não travam (PATCH).
   *
   * <p><b>Tem de ser a PRIMEIRA leitura da transação</b>: com a entidade já no persistence context,
   * o Hibernate devolve o cache sem reemitir o FOR UPDATE.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select m from Missao m where m.id = :id")
  Optional<Missao> buscarParaAtualizar(@Param("id") UUID id);

  /**
   * Listagem paginada com filtros opcionais. A visibilidade fica DENTRO da query (rascunho só para
   * o criador), senão o totalElementos vazaria rascunho alheio. {@code cast(:x as string)}:
   * parâmetro nulo sem tipo chega como bytea e a consulta estoura.
   */
  @Query(
      """
      select m from Missao m
      where (:status is null or m.status = :status)
        and (:categoria is null or m.categoria = :categoria)
        and (cast(:cidade as string) is null
             or lower(m.cidade) = lower(cast(:cidade as string)))
        and (cast(:bairro as string) is null
             or lower(m.bairro) = lower(cast(:bairro as string)))
        and (:criadorId is null or m.criadorId = :criadorId)
        and (:executorId is null or m.executorId = :executorId)
        and (m.status <> com.omnitribo.missoes.dominio.StatusMissao.RASCUNHO
             or m.criadorId = :solicitanteId)
      """)
  Page<Missao> buscarComFiltros(
      @Param("status") StatusMissao status,
      @Param("categoria") CategoriaMissao categoria,
      @Param("cidade") String cidade,
      @Param("bairro") String bairro,
      @Param("criadorId") UUID criadorId,
      @Param("executorId") UUID executorId,
      @Param("solicitanteId") UUID solicitanteId,
      Pageable pageable);

  /**
   * Candidatas à expiração depois de um cursor, SEM lock — quem trava é {@code
   * travarSeAindaExpiravel}, uma por vez (ADR 0014).
   *
   * <p>Keyset por {@code (janelaFim, id)}, não offset: uma missão que falha continua vencida e, com
   * offset, voltaria ao topo de todo lote. O par dá ordem total. O primeiro lote usa um cursor-
   * sentinela em vez de {@code :x is null}, que estouraria por parâmetro sem tipo.
   */
  @Query(
      """
      select new com.omnitribo.missoes.dominio.ExpiracaoMissoesService$Candidata(m.id, m.janelaFim)
      from Missao m
      where m.status = :status
        and m.janelaFim < :corte
        and (m.janelaFim > :aposMarco
             or (m.janelaFim = :aposMarco and m.id > :aposId))
      order by m.janelaFim asc, m.id asc
      """)
  List<Candidata> candidatasPorJanela(
      @Param("status") StatusMissao status,
      @Param("corte") Instant corte,
      @Param("aposMarco") Instant aposMarco,
      @Param("aposId") UUID aposId,
      Pageable lote);

  /**
   * Gêmea da anterior, com prazo a partir de {@code estado_desde}. Duas consultas porque nome de
   * coluna não pode ser parâmetro bindado, e concatenar seria a única SQL concatenada do projeto.
   */
  @Query(
      """
      select new com.omnitribo.missoes.dominio.ExpiracaoMissoesService$Candidata(m.id, m.estadoDesde)
      from Missao m
      where m.status = :status
        and m.estadoDesde < :corte
        and (m.estadoDesde > :aposMarco
             or (m.estadoDesde = :aposMarco and m.id > :aposId))
      order by m.estadoDesde asc, m.id asc
      """)
  List<Candidata> candidatasPorEstadoDesde(
      @Param("status") StatusMissao status,
      @Param("corte") Instant corte,
      @Param("aposMarco") Instant aposMarco,
      @Param("aposId") UUID aposId,
      Pageable lote);

  /**
   * Trava a missão e reconfirma que ela AINDA está no status esperado — entre a seleção e o lock
   * alguém pode ter agido. {@code lock.timeout = -2} é SKIP_LOCKED: com operação em curso na linha,
   * o job desiste nesta rodada e o usuário ganha a corrida.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query("select m from Missao m where m.id = :id and m.status = :status")
  Optional<Missao> travarSeAindaNoStatus(
      @Param("id") UUID id, @Param("status") StatusMissao status);

  /**
   * Criadas e concluídas pelo usuário-sistema numa statement: separadas, poderiam dar concluídas >
   * criadas.
   */
  @Query(
      value =
          """
          SELECT COUNT(*)                                        AS criadas,
                 COUNT(*) FILTER (WHERE status = 'CONCLUIDA')    AS concluidas
          FROM missao
          WHERE criador_id = :sistema
          """,
      nativeQuery = true)
  ResumoSistemaProjecao contarDoSistema(@Param("sistema") UUID sistema);

  /** Tokens parados em pote, em qualquer estado. COALESCE: SUM de conjunto vazio é NULL. */
  @Query(value = "SELECT COALESCE(SUM(pote_tokens), 0) FROM missao", nativeQuery = true)
  long somarPotes();

  /**
   * Missões não-terminais com pote parado além do limiar — o dinheiro que a reconciliação não acha
   * (ADR 0032). O marco muda por status: ABERTA por {@code janela_fim}, os demais por {@code
   * estado_desde}; sem isso, toda oferta financiada com janela longa seria falso positivo. Os
   * conjuntos de status entram BINDADOS, vindos do enum.
   */
  @Query(
      value =
          """
          select m from Missao m
          where m.poteTokens > 0
            and m.status not in :terminais
            and (case when m.status in :porJanelaFim then m.janelaFim else m.estadoDesde end) < :corte
          order by (case when m.status in :porJanelaFim then m.janelaFim else m.estadoDesde end) asc,
                   m.id asc
          """,
      countQuery =
          """
          select count(m) from Missao m
          where m.poteTokens > 0
            and m.status not in :terminais
            and (case when m.status in :porJanelaFim then m.janelaFim else m.estadoDesde end) < :corte
          """)
  Page<Missao> buscarPotesImobilizados(
      @Param("terminais") Collection<StatusMissao> terminais,
      @Param("porJanelaFim") Collection<StatusMissao> porJanelaFim,
      @Param("corte") Instant corte,
      Pageable paginacao);

  /**
   * Contagem e soma dos mesmos potes numa statement (separadas, poderiam straddle um commit). O
   * predicado é repetido de propósito; {@code PoteImobilizadoTest} trava que resumo e lista
   * concordem.
   */
  @Query(
      """
      select new com.omnitribo.missoes.dominio.DiagnosticoPotesService$ContagemPotes(
             count(m), coalesce(sum(m.poteTokens), 0L))
      from Missao m
      where m.poteTokens > 0
        and m.status not in :terminais
        and (case when m.status in :porJanelaFim then m.janelaFim else m.estadoDesde end) < :corte
      """)
  ContagemPotes contarPotesImobilizados(
      @Param("terminais") Collection<StatusMissao> terminais,
      @Param("porJanelaFim") Collection<StatusMissao> porJanelaFim,
      @Param("corte") Instant corte);

  /** Projeção de interface — sem entidade no caminho. */
  interface ResumoSistemaProjecao {
    long getCriadas();

    long getConcluidas();
  }
}
