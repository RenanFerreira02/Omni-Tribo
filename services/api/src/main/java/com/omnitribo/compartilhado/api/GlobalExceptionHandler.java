package com.omnitribo.compartilhado.api;

import com.omnitribo.compartilhado.dominio.BloqueioException;
import com.omnitribo.compartilhado.dominio.DominioException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {

    List<ErroCampo> erros =
        ex.getBindingResult().getFieldErrors().stream()
            .map(f -> new ErroCampo(f.getField(), mensagemCampo(f)))
            .toList();

    ProblemDetail pd = ProblemDetail.forStatus(status);
    pd.setTitle("Requisição inválida");
    pd.setDetail("Um ou mais campos falharam na validação.");
    pd.setProperty("traceId", MDC.get("correlationId"));
    pd.setProperty("errors", erros);
    return ResponseEntity.status(status).headers(headers).body(pd);
  }

  @ExceptionHandler(BloqueioException.class)
  ResponseEntity<ProblemDetail> handleBloqueio(BloqueioException ex, HttpServletRequest request) {
    ProblemDetail pd =
        ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
    pd.setInstance(URI.create(request.getRequestURI()));
    pd.setProperty("traceId", MDC.get("correlationId"));
    pd.setProperty("retryAfter", ex.getSegundosRestantes());
    // Retry-After: RFC 7231 §7.1.3 — informa ao cliente quanto tempo esperar.
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header("Retry-After", String.valueOf(ex.getSegundosRestantes()))
        .body(pd);
  }

  @ExceptionHandler(DominioException.class)
  ProblemDetail handleDominio(DominioException ex, HttpServletRequest request) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getHttpStatus(), ex.getMessage());
    pd.setInstance(URI.create(request.getRequestURI()));
    pd.setProperty("traceId", MDC.get("correlationId"));
    return pd;
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail handleGenerico(Exception ex, HttpServletRequest request) {
    log.error("Erro inesperado [traceId={}]", MDC.get("correlationId"), ex);
    ProblemDetail pd =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno. Contate o suporte.");
    pd.setInstance(URI.create(request.getRequestURI()));
    pd.setProperty("traceId", MDC.get("correlationId"));
    return pd;
  }

  private String mensagemCampo(FieldError f) {
    return f.getDefaultMessage() != null ? f.getDefaultMessage() : "valor inválido";
  }

  record ErroCampo(String campo, String mensagem) {}
}
