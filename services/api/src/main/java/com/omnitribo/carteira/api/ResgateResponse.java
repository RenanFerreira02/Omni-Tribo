package com.omnitribo.carteira.api;

import com.omnitribo.carteira.dominio.Resgate;
import com.omnitribo.carteira.dominio.StatusResgate;
import com.omnitribo.compartilhado.api.RecursoAuditavel;
import java.time.Instant;
import java.util.UUID;

/**
 * O comprovante do resgate.
 *
 * @param codigoRetirada os 8 caracteres que a pessoa mostra no balcão. <b>Não é credencial</b>:
 *     quem autoriza a baixa é um ADMIN, pelo id. Ver {@code GeradorCodigoRetirada}
 * @param saldoTokensRestante saldo DEPOIS da queima, lido sob o lock que acabou de escrevê-lo
 * @param replay verdadeiro quando a chave de idempotência já existia e NADA foi queimado nesta
 *     chamada
 */
public record ResgateResponse(
    UUID id,
    UUID beneficioId,
    long custoTokens,
    String codigoRetirada,
    StatusResgate status,
    Instant criadoEm,
    Instant utilizadoEm,
    long saldoTokensRestante,
    boolean replay)
    implements RecursoAuditavel {

  public static ResgateResponse de(Resgate r, long saldoRestante, boolean replay) {
    return new ResgateResponse(
        r.getId(),
        r.getBeneficioId(),
        r.getCustoTokens(),
        r.getCodigoRetirada(),
        r.getStatus(),
        r.getCriadoEm(),
        r.getUtilizadoEm(),
        saldoRestante,
        replay);
  }

  @Override
  public UUID idAuditoria() {
    return id;
  }
}
