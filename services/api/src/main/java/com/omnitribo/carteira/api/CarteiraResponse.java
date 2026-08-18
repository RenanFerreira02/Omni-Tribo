package com.omnitribo.carteira.api;

import com.omnitribo.carteira.dominio.Carteira;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

/** Saldo corrente da carteira do usuário autenticado. */
@Schema(description = "Saldos da carteira — projeção derivada do ledger")
public record CarteiraResponse(UUID id, UUID usuarioId, BigDecimal saldoBrl, long saldoTokens) {

  public static CarteiraResponse de(Carteira c) {
    return new CarteiraResponse(c.getId(), c.getUsuarioId(), c.getSaldoBrl(), c.getSaldoTokens());
  }
}
