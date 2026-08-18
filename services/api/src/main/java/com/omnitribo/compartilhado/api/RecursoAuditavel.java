package com.omnitribo.compartilhado.api;

import java.util.UUID;

/**
 * Implementado por DTOs de resposta cujo método de origem está anotado com @Auditavel, para que o
 * AuditoriaAspecto consiga preencher a coluna entidade_id.
 *
 * <p>Sem isto o aspecto grava entidade_id nulo e a trilha perde valor forense: saber que "alguém
 * publicou uma missão" sem saber QUAL missão não permite reconstruir o incidente. A alternativa
 * seria passar ip/userAgent/correlationId como parâmetro em todo método de serviço, o que
 * espalharia detalhe de transporte HTTP pelo domínio.
 *
 * <p>Vive em compartilhado/api/ pelo mesmo motivo de {@link AuditoriaPersistencia}: permite que
 * compartilhado/infra/ referencie o contrato sem violar a regra ArchUnit de acesso entre módulos.
 */
public interface RecursoAuditavel {

  /** Identificador do recurso afetado pela operação auditada. */
  UUID idAuditoria();
}
