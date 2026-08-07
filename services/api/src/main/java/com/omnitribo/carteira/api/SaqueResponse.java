package com.omnitribo.carteira.api;

import com.omnitribo.compartilhado.api.RecursoAuditavel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Resultado de um saque.
 *
 * @param protocolo id do lançamento de débito — é o identificador que o usuário cita ao pedir
 *     suporte sobre um saque
 */
@Schema(description = "Resultado da solicitação de saque")
public record SaqueResponse(UUID protocolo, BigDecimal saldoBrlRestante, boolean replay)
    implements RecursoAuditavel {

  @Override
  public UUID idAuditoria() {
    return protocolo;
  }
}
