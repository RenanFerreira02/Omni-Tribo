package com.omnitribo.compartilhado.infra;

import com.omnitribo.compartilhado.api.AuditoriaPersistencia;
import com.omnitribo.compartilhado.dominio.Auditavel;
import com.omnitribo.identidade.api.AutenticadoPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Intercepta métodos anotados com @Auditavel após execução bem-sucedida e persiste o registro.
 * Extrai ator do SecurityContext, IP e User-Agent do request, correlationId do MDC.
 */
@Aspect
@Component
public class AuditoriaAspecto {

  private final AuditoriaPersistencia auditoriaPersistencia;

  public AuditoriaAspecto(AuditoriaPersistencia auditoriaPersistencia) {
    this.auditoriaPersistencia = auditoriaPersistencia;
  }

  @AfterReturning("@annotation(auditavel)")
  public void registrar(JoinPoint joinPoint, Auditavel auditavel) {
    UUID atorId = extrairAtorId();
    String ip = null;
    String userAgent = null;

    var attrs = RequestContextHolder.getRequestAttributes();
    if (attrs instanceof ServletRequestAttributes sra) {
      HttpServletRequest req = sra.getRequest();
      ip = extrairIp(req);
      userAgent = req.getHeader("User-Agent");
    }

    auditoriaPersistencia.gravar(
        atorId,
        auditavel.acao(),
        auditavel.entidade(),
        null, // entidadeId: não derivável genericamente do retorno; métodos específicos usam
        // AuditoriaService diretamente quando precisam do ID do recurso criado.
        ip,
        userAgent,
        MDC.get("correlationId"));
  }

  private UUID extrairAtorId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof AutenticadoPrincipal principal) {
      return principal.id();
    }
    return null; // Ação anônima ou chamada fora de contexto HTTP
  }

  private String extrairIp(HttpServletRequest request) {
    // X-Forwarded-For é preenchido por proxies/load balancers; pegamos o primeiro IP da cadeia.
    // Em produção, validar que o proxy é confiável antes de confiar neste header.
    String xff = request.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) {
      return xff.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }
}
