package com.omnitribo.missoes.infra;

import com.omnitribo.missoes.dominio.CategoriaMissao;
import com.omnitribo.missoes.dominio.ExpiracaoMissoesService.Candidata;
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
   * Candidatas à expiração depois de um cursor, SEM lock.
   *
   * <p>Quem trava é {@code travarSeAindaExpiravel}, uma missão por vez. Esta consulta só enumera —
   * roda fora de transação de escrita e devolve projeção, não entidade.
   *
   * <p><b>Keyset por {@code (janelaFim, id)}, não offset.</b> Com uma transação por missão, uma
   * missão que falha continua ABERTA e vencida; com {@code LIMIT/OFFSET 0} ela reapareceria no topo
   * do próximo lote e o job repetiria a mesma falha até o teto por execução. O par ordenado dá
   * ordem TOTAL — só {@code janelaFim} não bastaria, porque duas missões podem vencer no mesmo
   * instante e o cursor ficaria preso ou pularia uma delas.
   *
   * <p>Substitui {@code buscarAbertasVencidas}, que foi REMOVIDO em vez de mantido "por segurança":
   * aquele método travava o lote inteiro numa transação só, que é a causa do deadlock — deixá-lo
   * vivo garantiria que alguém voltasse a chamá-lo.
   *
   * <p><b>Sem {@code :aposJanela is null}, e a ausência é obrigatória.</b> Um parâmetro nulo sem
   * tipo declarado chega ao PostgreSQL sem informação suficiente e a consulta estoura com {@code
   * could not determine data type of parameter $2} — a mesma armadilha que os {@code CAST(:param AS
   * ...)} de {@code buscarComFiltros} e {@code ConsultasGeoespaciais} existem para evitar. Aqui a
   * saída é melhor que um cast: o primeiro lote passa o cursor-sentinela {@code (EPOCH,
   * 00000000-...)}, que é menor que qualquer chave real, e o predicado fica com um caminho só.
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
   * Gêmea da anterior, contando o prazo desde {@code estado_desde} em vez de {@code janela_fim}.
   *
   * <p>São duas consultas e não uma com a coluna parametrizada porque o nome da coluna não pode ser
   * um parâmetro bindado — e concatená-lo seria a única concatenação de SQL do projeto, justamente
   * numa consulta que o job roda em laço.
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
   * Trava a missão e reconfirma que ela AINDA está no status esperado.
   *
   * <p>A reconfirmação no {@code where} não é redundante: entre a seleção (sem lock) e este lock,
   * alguém pode ter aceitado, feito check-in ou confirmado. Sem ela, o job aplicaria a transição
   * sobre uma missão que já saiu do estado — e a máquina de estados recusaria com exceção, o que
   * viraria uma falha registrada para um evento perfeitamente normal.
   *
   * <p>O prazo NÃO é reconferido aqui de propósito: ele já foi avaliado na seleção, e reavaliá-lo
   * exigiria saber qual coluna a regra usa. O que importa sob o lock é o estado, que é o que muda
   * por ação concorrente.
   *
   * <p>{@code lock.timeout = -2} é {@code org.hibernate.LockOptions.SKIP_LOCKED}: se uma operação
   * está em curso sobre esta linha, o job devolve vazio em vez de bloquear atrás dela. O usuário
   * ganha a corrida, e a missão simplesmente não expira nesta rodada.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query("select m from Missao m where m.id = :id and m.status = :status")
  Optional<Missao> travarSeAindaNoStatus(
      @Param("id") UUID id, @Param("status") StatusMissao status);

  /**
   * Missões não-terminais com pote em custódia cujo marco temporal já venceu — o dinheiro que a
   * reconciliação NÃO acha.
   *
   * <p>Reconciliação compara ledger com projeção, e as duas continuam batendo enquanto tokens estão
   * presos numa missão parada: quem quebra é a CONSERVAÇÃO, que é outra invariante. Esta consulta
   * existe para dar visibilidade a essa diferença.
   */
  @Query(
      """
      select m from Missao m
      where m.poteTokens > 0
        and m.status in (com.omnitribo.missoes.dominio.StatusMissao.EM_ANDAMENTO,
                         com.omnitribo.missoes.dominio.StatusMissao.AGUARDANDO_CONFIRMACAO,
                         com.omnitribo.missoes.dominio.StatusMissao.EM_DISPUTA)
        and m.estadoDesde < :corte
      order by m.estadoDesde asc
      """)
  List<Missao> potesImobilizados(@Param("corte") Instant corte, Pageable limite);
}
