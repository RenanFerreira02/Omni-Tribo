package com.omnitribo.logistica.api;

import com.omnitribo.compartilhado.api.RecursoAuditavel;
import com.omnitribo.logistica.dominio.ConfirmacaoRetiradaService;
import java.util.UUID;

/**
 * Resposta da confirmação de retirada.
 *
 * @param tokensCreditados quanto o executor recebeu NESTA chamada. Zero num replay — a segunda
 *     confirmação não move dinheiro
 * @param replay verdadeiro quando a missão já estava concluída. A transportadora pode usá-lo para
 *     distinguir "confirmei agora" de "já estava confirmado", mas não precisa: o retry é seguro
 */
public record ConfirmacaoRetiradaResponse(
    UUID entregaFalidaId, UUID missaoId, long tokensCreditados, boolean replay, String mensagem)
    implements RecursoAuditavel {

  public static ConfirmacaoRetiradaResponse de(ConfirmacaoRetiradaService.Resultado resultado) {
    String mensagem =
        resultado.replay()
            ? "Esta retirada já havia sido confirmada. Nada foi creditado de novo."
            : "Retirada confirmada. O executor foi creditado e a vaga do ponto de custódia liberada.";
    return new ConfirmacaoRetiradaResponse(
        resultado.entregaFalidaId(),
        resultado.missaoId(),
        resultado.tokensCreditados(),
        resultado.replay(),
        mensagem);
  }

  /**
   * A entrega falida é o recurso auditado — a mesma entidade do webhook de reporte, para que os
   * dois atos apareçam na trilha sob o mesmo {@code entidade_id} e um incidente possa ser
   * reconstruído lendo uma coisa só.
   */
  @Override
  public UUID idAuditoria() {
    return entregaFalidaId;
  }
}
