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
   * Criadas e concluídas pelo usuário-sistema, numa statement (painel de impacto, ADR 0029).
   *
   * <p>Duas contagens juntas pela mesma razão do resumo de entregas falidas: separadas, uma
   * conclusão acontecendo entre as duas leituras produziria {@code concluidas > criadas} — um
   * número impossível, causado só pelo instante da consulta.
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

  /**
   * Tokens parados em pote, em qualquer estado — metade da conservação do ADR 0027.
   *
   * <p>{@code COALESCE} porque {@code SUM} de conjunto vazio é NULL, e um banco recém-criado
   * devolveria nulo onde o painel espera zero.
   */
  @Query(value = "SELECT COALESCE(SUM(pote_tokens), 0) FROM missao", nativeQuery = true)
  long somarPotes();

  /**
   * Missões não-terminais cujo pote está parado além do limiar — o dinheiro que a reconciliação NÃO
   * acha (ADR 0032).
   *
   * <p><b>O marco muda por status, e é isso que separa este diagnóstico de um contador de
   * potes.</b> ABERTA mede por {@code janela_fim} e os demais por {@code estado_desde}, replicando
   * {@code RegraExpiracao.Marco}: sem a distinção, toda missão comunitária financiada com janela
   * longa apareceria como imobilizada assim que passasse do limiar parada em ABERTA — que é o
   * estado NORMAL de uma oferta esperando executor. Um instrumento cujo falso positivo é o caso
   * comum não é consultado duas vezes.
   *
   * <p>Os dois conjuntos entram BINDADOS, vindos de {@code StatusMissao.ehTerminal()} e de {@code
   * RegraExpiracao.medidosPorJanelaFim()}. Nenhum nome de status é literal aqui: um estado novo na
   * máquina passa a ser considerado sem que esta consulta seja editada.
   *
   * <p>Devolve a ENTIDADE, e não uma projeção, no molde de {@code
   * OutboxRepository.buscarEsgotados}: a página tem no máximo 100 linhas por {@code
   * PotesImobilizadosFiltroRequest}, e quem monta o DTO é o serviço. As projeções escalares deste
   * repositório existem para agregados que varrem a tabela inteira, que é outro problema.
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
   * Contagem e soma dos mesmos potes, numa statement só.
   *
   * <p>Juntas pela razão que o {@code ReconciliacaoRepository} já documenta: sob READ COMMITTED,
   * contar e somar em consultas separadas pode straddle um commit concorrente e publicar um par
   * impossível — zero missões com tokens presos, ou o contrário.
   *
   * <p>{@code coalesce(..., 0L)} porque {@code sum} de conjunto vazio é NULL, e o caso saudável do
   * sistema é justamente o conjunto vazio. O literal é {@code 0L}, não {@code 0}: com um inteiro a
   * expressão inteira seria promovida a Integer e o construtor não casaria.
   *
   * <p><b>O predicado é repetido de propósito, e o risco é conhecido:</b> se ele divergir do da
   * consulta acima, o resumo passa a contradizer a lista sem que nada falhe. É por isso que {@code
   * PoteImobilizadoTest.resumoConcordaComALista} assere a igualdade em vez de confiar na leitura.
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
