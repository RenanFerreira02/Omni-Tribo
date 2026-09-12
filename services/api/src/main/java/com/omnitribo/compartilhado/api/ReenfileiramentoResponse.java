package com.omnitribo.compartilhado.api;

import com.omnitribo.compartilhado.dominio.Outbox;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Resultado de devolver um evento esgotado à fila.
 *
 * <p>Implementa {@link RecursoAuditavel} porque o método de serviço que a devolve é
 * {@code @Auditavel}: sem isto o {@code AuditoriaAspecto} gravaria {@code entidade_id} nulo e a
 * trilha diria "alguém reenfileirou algo" sem dizer O QUÊ. É a metade da anotação que o compilador
 * não cobra, e que {@code RegrasArquiteturaTest} passou a barrar.
 */
@Schema(description = "Resultado do reenfileiramento de um evento da outbox")
public record ReenfileiramentoResponse(
    UUID id,
    String tipoEvento,
    int tentativas,
    Instant proximaTentativaEm,
    @Schema(
            description =
                "false quando nada foi escrito: o evento já estava na fila (replay do próprio POST,"
                    + " ou evento ainda em backoff). A operação é idempotente por estado da linha.")
        boolean reenfileirado)
    implements RecursoAuditavel {

  public static ReenfileiramentoResponse de(Outbox evento, boolean reenfileirado) {
    return new ReenfileiramentoResponse(
        evento.getId(),
        evento.getTipoEvento(),
        evento.getTentativas(),
        evento.getProximaTentativaEm(),
        reenfileirado);
  }

  @Override
  public UUID idAuditoria() {
    return id;
  }
}
