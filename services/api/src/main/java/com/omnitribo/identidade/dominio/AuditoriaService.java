package com.omnitribo.identidade.dominio;

import com.omnitribo.compartilhado.api.AuditoriaPersistencia;
import com.omnitribo.identidade.infra.AuditoriaRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Implementação de AuditoriaPersistencia: usa AuditoriaRepository do próprio módulo. */
@Service
public class AuditoriaService implements AuditoriaPersistencia {

  private final AuditoriaRepository auditoriaRepository;

  public AuditoriaService(AuditoriaRepository auditoriaRepository) {
    this.auditoriaRepository = auditoriaRepository;
  }

  @Override
  public void gravar(
      UUID atorId,
      String acao,
      String entidade,
      String entidadeId,
      String ip,
      String userAgent,
      String correlationId) {
    var registro =
        new Auditoria(
            UUID.randomUUID(),
            atorId, // null = ação anônima (ex: tentativa de login sem usuário identificado)
            acao,
            entidade,
            entidadeId,
            ip,
            userAgent,
            correlationId,
            Instant.now());
    auditoriaRepository.save(registro);
  }
}
