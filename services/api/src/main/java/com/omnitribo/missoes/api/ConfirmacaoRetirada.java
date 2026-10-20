package com.omnitribo.missoes.api;

import java.util.UUID;

/**
 * Porta pela qual {@code logistica} confirma que a encomenda chegou (ADR 0026).
 *
 * <p>Quem confirma é a TRANSPORTADORA, não o executor: o check-in prova PRESENÇA, não RECEBIMENTO,
 * e a transportadora é a contraparte com interesse oposto — ela paga e responde ao destinatário. A
 * varredura de prazo continua como rede de segurança quando ela não confirma.
 */
public interface ConfirmacaoRetirada {

  /**
   * Aplica CONFIRMAR com ator SISTEMA, que é o criador da missão de retirada — satisfaz {@code
   * CRIADOR} por construção. Idempotente: o replay devolve o estado sem creditar de novo.
   *
   * @param missaoId a missão de retirada, resolvida a partir da entrega falida
   * @return a recompensa congelada (não distingue crédito novo de replay; quem sabe é o chamador)
   * @throws com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException missão inexistente
   */
  long confirmarRetirada(UUID missaoId);
}
