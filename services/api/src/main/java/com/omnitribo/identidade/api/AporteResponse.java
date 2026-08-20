package com.omnitribo.identidade.api;

import com.omnitribo.compartilhado.api.RecursoAuditavel;
import java.util.UUID;

/**
 * Resultado de um aporte.
 *
 * @param saldoTokens saldo DEPOIS do aporte. É o único lugar da API que devolve o saldo de um
 *     patrocinador, e de propósito: aqui o número é lido sob o lock que acabou de escrevê-lo, então
 *     é verdade no instante da resposta. Uma listagem devolveria uma leitura sem lock que envelhece
 *     antes de chegar à tela.
 * @param replay {@code true} quando a chave de idempotência já existia e NADA foi emitido nesta
 *     chamada. O ADMIN precisa conseguir distinguir "aportei agora" de "já tinha aportado" — num
 *     endpoint que cunha moeda, repetir por engano é o erro caro.
 */
public record AporteResponse(
    UUID patrocinadorId, UUID usuarioId, UUID lancamentoId, long saldoTokens, boolean replay)
    implements RecursoAuditavel {

  @Override
  public UUID idAuditoria() {
    return patrocinadorId;
  }
}
