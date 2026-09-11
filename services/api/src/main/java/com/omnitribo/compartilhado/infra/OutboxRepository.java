package com.omnitribo.compartilhado.infra;

import com.omnitribo.compartilhado.dominio.Outbox;
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

public interface OutboxRepository extends JpaRepository<Outbox, UUID> {

  /**
   * Lote de eventos pendentes e já elegíveis, travado com SKIP LOCKED.
   *
   * <p>{@code jakarta.persistence.lock.timeout = -2} é {@code LockOptions.SKIP_LOCKED} — mesmo
   * recurso e mesmo motivo de {@code MissaoRepository.buscarAbertasVencidas}. Sem ele, duas
   * instâncias do drenador (ou um lote lento sobrepondo o seguinte) bloqueariam uma na outra e
   * despachariam o MESMO evento. Com ele, a segunda simplesmente pula as linhas travadas e pega as
   * próximas.
   *
   * <p>Ordena por {@code proximaTentativaEm}, não por {@code criadoEm}: é este campo que decide
   * elegibilidade depois de uma falha, e ordenar pelo outro faria um evento em espera continuar no
   * topo de todo lote. Servido por {@code idx_outbox_pendente} (V14).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      """
      select o from Outbox o
      where o.publicadoEm is null
        and o.proximaTentativaEm <= :agora
        and o.tentativas < :maximoTentativas
      order by o.proximaTentativaEm asc
      """)
  List<Outbox> buscarPendentesParaPublicar(
      @Param("agora") Instant agora,
      @Param("maximoTentativas") int maximoTentativas,
      Pageable lote);

  /**
   * O complemento exato do lote: eventos que esgotaram as tentativas e nunca foram entregues.
   *
   * <p>É a carta-morta da outbox (ADR 0031). O predicado é a negação da terceira condição de {@code
   * buscarPendentesParaPublicar} — {@code tentativas >= :maximoTentativas} em vez de {@code <} —
   * sobre o mesmo {@code publicadoEm is null}. As duas queries lendo o mesmo parâmetro é o que
   * garante que "o que saiu do lote" e "o que a consulta mostra" não possam divergir se o teto
   * mudar no YAML.
   *
   * <p>Sem lock, ao contrário do lote: é leitura de diagnóstico e não pode disputar linha com o
   * drenador. Ordena por {@code criadoEm} porque a pergunta do operador é "qual fato mais antigo se
   * perdeu", não "qual tentativa venceu primeiro".
   *
   * <p>{@code idx_outbox_pendente} (V14) é parcial em {@code (proxima_tentativa_em) WHERE
   * publicado_em IS NULL} e NÃO cobre {@code tentativas}: o planejador usa a parcialidade e filtra
   * o resto. Não há índice dedicado de propósito — custaria manutenção em toda escrita da outbox
   * para servir uma consulta manual sobre um conjunto que, num sistema saudável, é vazio.
   */
  @Query(
      """
      select o from Outbox o
      where o.publicadoEm is null
        and o.tentativas >= :maximoTentativas
      order by o.criadoEm asc
      """)
  Page<Outbox> buscarEsgotados(@Param("maximoTentativas") int maximoTentativas, Pageable paginacao);

  /**
   * Trava uma linha da outbox para escrita administrativa.
   *
   * <p>{@code PESSIMISTIC_WRITE} sem SKIP LOCKED, ao contrário do lote: aqui o alvo é uma linha
   * nomeada e pular a linha travada devolveria "não encontrado" para um evento que existe. Se o
   * drenador estiver segurando esta mesma linha, o reenfileiramento ESPERA o commit dele e sonda o
   * estado real — que pode ter virado publicado no meio do caminho, e é justamente o caso que a
   * sondagem sob o lock precisa enxergar.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select o from Outbox o where o.id = :id")
  Optional<Outbox> buscarParaAtualizar(@Param("id") UUID id);
}
