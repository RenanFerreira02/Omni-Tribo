package com.omnitribo.compartilhado.api;

import java.util.UUID;

/**
 * Contrato público do módulo identidade para persistência de auditoria. Mantido em
 * compartilhado/api/ para que o AuditoriaAspecto (compartilhado/infra/) possa referenciar sem
 * violar a regra ArchUnit (que proíbe acesso direto a identidade/infra/).
 */
public interface AuditoriaPersistencia {

  void gravar(
      UUID atorId,
      String acao,
      String entidade,
      String entidadeId,
      String ip,
      String userAgent,
      String correlationId);
}
