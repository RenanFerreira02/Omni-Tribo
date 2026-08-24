package com.omnitribo.identidade.api;

import com.omnitribo.compartilhado.api.RecursoAuditavel;
import com.omnitribo.identidade.dominio.Patrocinador;
import java.time.Instant;
import java.util.UUID;

/**
 * O patrocinador como o ADMIN o vê.
 *
 * <p><b>Sem saldo, de propósito.</b> Saldo é estado da carteira e muda sob lock; devolvê-lo numa
 * listagem seria uma leitura sem lock que envelhece antes de chegar à tela. Quem precisa do número
 * atual usa a resposta do aporte, que o devolve depois de escrever. Ver ADR 0024 §6.
 *
 * @param usuarioId o titular da carteira. Aparece porque é ele que o extrato e a reconciliação usam
 *     como chave — sem isto, ligar um lançamento ao patrocinador exigiria uma consulta manual.
 */
public record PatrocinadorResponse(
    UUID id,
    UUID usuarioId,
    String transportadoraSlug,
    String nome,
    boolean ativo,
    Instant criadoEm)
    implements RecursoAuditavel {

  public static PatrocinadorResponse de(Patrocinador p) {
    return new PatrocinadorResponse(
        p.getId(),
        p.getUsuarioId(),
        p.getTransportadoraSlug(),
        p.getNome(),
        p.isAtivo(),
        p.getCriadoEm());
  }

  /**
   * O patrocinador é o recurso auditado. Sem isto o {@code AuditoriaAspecto} gravaria {@code
   * entidade_id} nulo, e a trilha diria "um patrocinador foi cadastrado" sem dizer qual.
   */
  @Override
  public UUID idAuditoria() {
    return id;
  }
}
