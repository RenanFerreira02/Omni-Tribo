package com.omnitribo.carteira.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

/** Uma carteira cuja projeção de saldo não bate com a soma do ledger. */
@Schema(description = "Divergência entre saldo registrado e soma do ledger")
public record DivergenciaResponse(
    UUID carteiraId,
    UUID usuarioId,
    @Schema(description = "O que carteira.saldo_brl diz") BigDecimal saldoBrlRegistrado,
    @Schema(description = "O que a soma dos lançamentos diz") BigDecimal saldoBrlLedger,
    long saldoTokensRegistrado,
    long saldoTokensLedger) {}
