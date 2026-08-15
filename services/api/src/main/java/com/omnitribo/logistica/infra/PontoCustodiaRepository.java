package com.omnitribo.logistica.infra;

import com.omnitribo.logistica.dominio.PontoCustodia;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PontoCustodiaRepository extends JpaRepository<PontoCustodia, UUID> {

  /**
   * {@code SELECT ... FOR UPDATE} no ponto — o lock que serializa a disputa por vaga.
   *
   * <p>Sem ele, dois webhooks concorrentes para o mesmo ponto leem a mesma {@code ocupacao}, ambos
   * concluem que há vaga e ambos incrementam: o ponto passa da capacidade e nada acusa, porque a
   * única constraint da coluna é {@code NOT NULL}. É a mesma corrida que o aceite de missão fecha
   * com {@code buscarParaAtualizar}.
   *
   * <p>Este lock também serializa o replay do MESMO webhook, que é o que permite sondar a
   * idempotência sem correr risco de dois INSERTs empatando na UNIQUE.
   *
   * <p>CHAMADA OBRIGATORIAMENTE COMO PRIMEIRA LEITURA DA TRANSAÇÃO: se o {@code PontoCustodia} já
   * estiver no persistence context, o Hibernate devolve a instância em cache sem reemitir o {@code
   * SELECT ... FOR UPDATE}, e o lock nunca existe — o teste passa e a proteção não está lá.
   *
   * <p>Ordem global de locks do sistema: {@code missao → ponto_custodia → carteira (id crescente) →
   * usuario}. O webhook trava só este; a conclusão de missão trava a missão antes. Inverter reabre
   * o deadlock.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from PontoCustodia p where p.id = :id")
  Optional<PontoCustodia> buscarParaAtualizar(@Param("id") UUID id);
}
