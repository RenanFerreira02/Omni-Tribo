package com.omnitribo.missoes.api;

import com.omnitribo.compartilhado.api.RecursoAuditavel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Resultado de um financiamento de missão.
 *
 * @param replay {@code true} quando a requisição foi um retry da mesma {@code Idempotency-Key} e
 *     nada novo foi debitado
 */
@Schema(description = "Estado do pote da missão após o financiamento")
public record FinanciamentoResponse(
    UUID missaoId,
    @Schema(description = "Tokens em custódia na missão") long poteTokens,
    @Schema(description = "Quanto a missão promete pagar ao executor") long tokensRecompensa,
    @Schema(description = "Saldo de tokens do financiador após o débito") long saldoTokensRestante,
    boolean replay)
    implements RecursoAuditavel {

  /** Id da missão para a trilha de auditoria. Não é componente do record, então não vai no JSON. */
  @Override
  public UUID idAuditoria() {
    return missaoId;
  }
}
