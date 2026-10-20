package com.omnitribo.compartilhado.api;

import java.util.Map;
import java.util.UUID;

/**
 * Registra um evento de domínio para entrega assíncrona — o lado de escrita do Transactional Outbox
 * (ADR 0008).
 *
 * <p>Notificar de dentro da transação não é atômico com ela em ordem nenhuma: antes do commit
 * anuncia o que o rollback desfaz; depois, perde o que a rede derrubar. A outbox grava o FATO e a
 * INTENÇÃO na mesma transação, sem broker. O consumidor tolera receber o mesmo evento duas vezes.
 *
 * <p><b>Limite: a entrega NÃO é at-least-once.</b> O drenador para na 5ª falha; o evento esgotado
 * fica na tabela, listável e reenfileirável por ADMIN (ADR 0031) — mas só por consulta ativa.
 *
 * <p>É interface por testabilidade: {@code ConclusaoRollbackTest} a substitui para forçar falha
 * depois do INSERT no ledger, sem flag de falha em código de produção.
 */
public interface PublicadorEventos {

  /**
   * Grava na outbox NA TRANSAÇÃO DO CHAMADOR. {@code MANDATORY}: com {@code REQUIRED}, fora de
   * transação a intenção commitaria sem o fato.
   *
   * @param payload vira {@code jsonb}; só tipos que o mapper resolve sem configuração
   */
  void publicar(String tipoEvento, UUID agregadoId, Map<String, Object> payload);
}
