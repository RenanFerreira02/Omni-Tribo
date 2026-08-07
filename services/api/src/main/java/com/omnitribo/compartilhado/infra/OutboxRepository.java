package com.omnitribo.compartilhado.infra;

import com.omnitribo.compartilhado.dominio.Outbox;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<Outbox, UUID> {

  List<Outbox> findByPublicadoEmIsNullOrderByCriadoEmAsc();

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
}
