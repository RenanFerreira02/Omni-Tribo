package com.omnitribo.missoes.infra;

import com.omnitribo.missoes.dominio.CategoriaMissao;
import com.omnitribo.missoes.dominio.Missao;
import com.omnitribo.missoes.dominio.StatusMissao;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
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

  List<Missao> findByStatus(StatusMissao status);

  List<Missao> findByExecutorId(UUID executorId);

  List<Missao> findByCriadorId(UUID criadorId);

  /**
   * SELECT ... FOR UPDATE na linha da missão. É o mecanismo que garante exatamente um vencedor no
   * aceite concorrente.
   *
   * <p>Por que lock pessimista e não @Version com retry:
   *
   * <ul>
   *   <li>Com @Version, o perdedor só descobre a colisão no flush/commit — a
   *       ObjectOptimisticLockingFailureException nasce no interceptor transacional, depois de o
   *       método de negócio ter retornado, e o INSERT em missao_evento do perdedor já foi emitido
   *       para ser desfeito no rollback.
   *   <li>Com FOR UPDATE, o perdedor bloqueia por microssegundos, relê a linha já ACEITA e cai na
   *       TransicaoInvalidaException — o MESMO 409 de qualquer outra transição inválida. Um caminho
   *       de erro, não dois.
   *   <li>Retry seria pior ainda: o estado já mudou para ACEITA, então repetir nunca sucede. Retry
   *       só faz sentido quando a colisão é espúria.
   *   <li>A contenção é por linha e brevíssima — uma missão, poucos aceites simultâneos.
   * </ul>
   *
   * <p>@Version permanece na entidade como defesa em profundidade para os caminhos que não travam a
   * linha (PATCH).
   *
   * <p>CHAMADA OBRIGATORIAMENTE COMO PRIMEIRA LEITURA DA TRANSAÇÃO: se a entidade já estiver no
   * persistence context, o Hibernate devolve a instância em cache sem reemitir o SELECT ... FOR
   * UPDATE, e o lock jamais é adquirido.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select m from Missao m where m.id = :id")
  Optional<Missao> buscarParaAtualizar(@Param("id") UUID id);

  /**
   * Listagem paginada com filtros opcionais.
   *
   * <p>A regra de visibilidade fica DENTRO da query: rascunho só aparece para o próprio criador.
   * Filtrar depois da consulta quebraria a contagem da página e vazaria a existência de rascunhos
   * alheios pelo totalElementos.
   *
   * <p>cast(:cidade as string) não é decoração: sem o cast, um filtro nulo chega ao PostgreSQL como
   * parâmetro sem tipo, o driver assume bytea e a consulta estoura com "function lower(bytea) does
   * not exist". O cast informa o tipo mesmo quando o valor é nulo.
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
   * Missões abertas cuja janela venceu, para o job de expiração.
   *
   * <p>lock.timeout = -2 é org.hibernate.LockOptions.SKIP_LOCKED: o job pula missões cuja linha
   * está travada por um aceite em curso, em vez de bloquear atrás dele ou derrubar o lote inteiro.
   * O aceite ganha a corrida e a missão simplesmente não expira nesta rodada.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      """
      select m from Missao m
      where m.status = com.omnitribo.missoes.dominio.StatusMissao.ABERTA
        and m.janelaFim < :agora
      order by m.janelaFim asc
      """)
  List<Missao> buscarAbertasVencidas(@Param("agora") Instant agora, Pageable lote);
}
