package com.omnitribo.compartilhado.infra;

import com.omnitribo.compartilhado.api.AuditoriaPersistencia;
import com.omnitribo.compartilhado.api.RecursoAuditavel;
import com.omnitribo.compartilhado.dominio.Auditavel;
import com.omnitribo.identidade.api.AutenticadoPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Intercepta métodos anotados com @Auditavel após execução bem-sucedida e persiste o registro.
 * Extrai ator do SecurityContext, IP e User-Agent do request, correlationId do MDC.
 *
 * <p>Ordem em relação ao @Transactional: LOWEST_PRECEDENCE - 1 coloca este aspecto POR FORA do
 * interceptor transacional (menor valor = mais externo), então o @AfterReturning roda depois do
 * commit. A trilha registra apenas escrita que de fato persistiu, nunca uma que deu rollback.
 * Sem @Order explícito haveria empate com o advisor de transação, cuja ordem default também é
 * LOWEST_PRECEDENCE — e ordem indefinida em código de auditoria não é defensável.
 *
 * <p>Contrapartida aceita: se a gravação da auditoria falhar, o cliente recebe 500 com a mudança de
 * negócio já commitada. Tolerável porque o histórico canônico da missão é missao_evento, gravado na
 * MESMA transação da mudança de status; a linha em auditoria é o complemento de segurança (quem, de
 * qual IP, com qual user-agent, sob qual correlation-id).
 *
 * <p>Só grava sucesso (@AfterReturning, não @Around). Tentativa negada já produz 403/409
 * ProblemDetail com traceId igual ao correlationId, correlacionável no log; auditar toda negativa
 * multiplicaria uma tabela append-only que nunca é purgada. Auditar tentativas negadas de escrita
 * (@AfterThrowing) está previsto para F12.
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class AuditoriaAspecto {

  private final AuditoriaPersistencia auditoriaPersistencia;

  public AuditoriaAspecto(AuditoriaPersistencia auditoriaPersistencia) {
    this.auditoriaPersistencia = auditoriaPersistencia;
  }

  @AfterReturning(value = "@annotation(auditavel)", returning = "retorno")
  public void registrar(JoinPoint joinPoint, Auditavel auditavel, Object retorno) {
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
        extrairEntidadeId(retorno),
        ip,
        userAgent,
        MDC.get("correlationId"));
  }

  /**
   * Deriva o id do recurso afetado quando o retorno declara RecursoAuditavel. Sem isso a trilha
   * diria "alguém publicou uma missão" sem dizer qual — inútil para reconstruir um incidente.
   * Retorno que não implementa o contrato grava nulo, que é o comportamento antigo.
   */
  private String extrairEntidadeId(Object retorno) {
    if (retorno instanceof RecursoAuditavel recurso && recurso.idAuditoria() != null) {
      return recurso.idAuditoria().toString();
    }
    return null;
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
