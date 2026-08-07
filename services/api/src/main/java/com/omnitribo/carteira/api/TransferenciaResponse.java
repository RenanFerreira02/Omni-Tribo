package com.omnitribo.carteira.api;

import com.omnitribo.compartilhado.api.RecursoAuditavel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Resultado de uma transferência.
 *
 * @param lancamentoEntradaId {@code null} num replay — a segunda chamada não gravou nada e só a
 *     perna de saída é recuperável pela chave sondada
 * @param replay {@code true} quando a mesma {@code Idempotency-Key} já havia sido processada
 */
@Schema(description = "Resultado da transferência de tokens")
public record TransferenciaResponse(
    @Schema(description = "Lançamento de débito no remetente") UUID lancamentoSaidaId,
    @Schema(description = "Lançamento de crédito no destinatário") UUID lancamentoEntradaId,
    @Schema(description = "Saldo de tokens do remetente após a operação") long saldoTokensRemetente,
    boolean replay)
    implements RecursoAuditavel {

  /** Lançamento de saída como entidade auditada — é a perna que o ator autenticado originou. */
  @Override
  public UUID idAuditoria() {
    return lancamentoSaidaId;
  }
}
