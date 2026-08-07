package com.omnitribo.compartilhado.api;

import com.omnitribo.compartilhado.dominio.BloqueioException;
import com.omnitribo.compartilhado.dominio.DominioException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
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

  /**
   * 400 para violação de constraint em parâmetro de método (header, path, query).
   *
   * <p>Existe por causa de uma sutileza do Spring MVC: num controller anotado com
   * {@code @Validated} — hoje {@code CarteiraController} e {@code TriboFinanciamentoController},
   * por causa do {@code @NotBlank @Size} no header {@code Idempotency-Key} — a validação de método
   * embutida do MVC é DESLIGADA e passa a vir do proxy AOP, que lança {@code
   * ConstraintViolationException} em vez de {@code HandlerMethodValidationException}.
   *
   * <p>Sem este handler ela caía no handleGenerico: 500 com {@code log.error} e stack trace para
   * uma requisição meramente malformada. Qualquer cliente autenticado produziria incidente falso à
   * vontade mandando {@code Idempotency-Key: abc}, e o contrato do OpenAPI, que promete 400,
   * estaria mentindo.
   */
  @ExceptionHandler(ConstraintViolationException.class)
  ProblemDetail handleViolacaoDeParametro(
      ConstraintViolationException ex, HttpServletRequest request) {

    List<ErroCampo> erros =
        ex.getConstraintViolations().stream()
            .map(v -> new ErroCampo(nomeDoParametro(v), v.getMessage()))
            .toList();

    ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    pd.setTitle("Requisição inválida");
    pd.setDetail("Um ou mais parâmetros falharam na validação.");
    pd.setInstance(URI.create(request.getRequestURI()));
    pd.setProperty("traceId", MDC.get("correlationId"));
    pd.setProperty("errors", erros);
    return pd;
  }

  /**
   * O propertyPath de um parâmetro de método vem como {@code metodo.argumento}; só o último
   * segmento interessa ao cliente — o nome do método é detalhe interno.
   */
  private static String nomeDoParametro(ConstraintViolation<?> violacao) {
    String caminho = violacao.getPropertyPath().toString();
    int ultimoPonto = caminho.lastIndexOf('.');
    return ultimoPonto < 0 ? caminho : caminho.substring(ultimoPonto + 1);
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

  /**
   * 403 de @PreAuthorize.
   *
   * <p>O interceptor de method security lança AuthorizationDeniedException (subclasse de
   * AccessDeniedException) DENTRO do proxy do controller, então ela é resolvida pelo
   * DispatcherServlet antes de chegar ao ExceptionTranslationFilter do Spring Security. Sem este
   * handler, o handleGenerico abaixo a capturaria e devolveria 500 com log de erro — mascarando um
   * 403 legítimo como falha do servidor.
   */
  @ExceptionHandler(AccessDeniedException.class)
  ProblemDetail handleAcessoNegadoSecurity(AccessDeniedException ex, HttpServletRequest request) {
    // Sem detalhe: a resposta não confirma nem nega a existência do recurso.
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Acesso negado");
    pd.setInstance(URI.create(request.getRequestURI()));
    pd.setProperty("traceId", MDC.get("correlationId"));
    return pd;
  }

  /**
   * Endpoints com contrato publicado e implementação prevista para fase futura (F6/F7). Loga em
   * info, e não em error: um 501 aqui é planejado, não é incidente.
   */
  @ExceptionHandler(UnsupportedOperationException.class)
  ProblemDetail handleNaoImplementado(
      UnsupportedOperationException ex, HttpServletRequest request) {
    log.info("Endpoint ainda não implementado [{}]: {}", request.getRequestURI(), ex.getMessage());
    ProblemDetail pd =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_IMPLEMENTED, "Funcionalidade ainda não disponível nesta versão da API.");
    pd.setInstance(URI.create(request.getRequestURI()));
    pd.setProperty("traceId", MDC.get("correlationId"));
    return pd;
  }

  /**
   * Colisão de @Version nos caminhos que não travam a linha (ex.: PATCH de missão). O aceite
   * concorrente NÃO passa por aqui — ele é serializado por SELECT ... FOR UPDATE e o perdedor
   * recebe TransicaoInvalidaException. Este handler é a rede de segurança para o resto.
   */
  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  ProblemDetail handleConflitoConcorrencia(
      ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
    ProblemDetail pd =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "O recurso foi alterado por outra operação. Recarregue e tente de novo.");
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
