package com.omnitribo.compartilhado.api;

import com.omnitribo.compartilhado.dominio.BloqueioException;
import com.omnitribo.compartilhado.dominio.DominioException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
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

  /**
   * Funil de TODA resposta do {@code ResponseEntityExceptionHandler}: os ~15 handlers herdados não
   * passam pelos daqui e sairiam sem {@code type} nem {@code traceId}. Só preenche o que falta — um
   * handler que já decidiu o tipo continua mandando.
   */
  @Override
  protected ResponseEntity<Object> createResponseEntity(
      Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {

    if (body instanceof ProblemDetail pd) {
      if (pd.getType() == null || TIPO_AUSENTE.equals(pd.getType())) {
        pd.setType(TipoProblema.deStatus(resolverStatus(statusCode)));
      }
      if (pd.getInstance() == null) {
        pd.setInstance(URI.create(uriDe(request)));
      }
      // Uma leitura só: getProperties() é @Nullable (SpotBugs NP_NULL_ON_SOME_PATH).
      Map<String, Object> propriedades = pd.getProperties();
      if (propriedades == null || !propriedades.containsKey("traceId")) {
        pd.setProperty("traceId", MDC.get("correlationId"));
      }
    }
    return super.createResponseEntity(body, headers, statusCode, request);
  }

  /** Valor default de {@code ProblemDetail.type} — o que este handler existe para substituir. */
  private static final URI TIPO_AUSENTE = URI.create("about:blank");

  /** {@code HttpStatus.valueOf} lança para código desconhecido; o mapeamento não pode virar 500. */
  private static HttpStatus resolverStatus(HttpStatusCode codigo) {
    HttpStatus resolvido = HttpStatus.resolve(codigo.value());
    return resolvido != null ? resolvido : HttpStatus.INTERNAL_SERVER_ERROR;
  }

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
    pd.setProperty("errors", erros);
    // Pelo funil, que preenche type, instance e traceId.
    return createResponseEntity(pd, headers, status, request);
  }

  /**
   * 400 para constraint em parâmetro de método (header, path, query). Em controller
   * {@code @Validated} a validação vem do proxy AOP como {@code ConstraintViolationException}; sem
   * este handler, um {@code Idempotency-Key} malformado virava 500 com stack trace.
   */
  @ExceptionHandler(ConstraintViolationException.class)
  ProblemDetail handleViolacaoDeParametro(
      ConstraintViolationException ex, HttpServletRequest request) {

    List<ErroCampo> erros =
        ex.getConstraintViolations().stream()
            .map(v -> new ErroCampo(nomeDoParametro(v), v.getMessage()))
            .toList();

    ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    pd.setType(TipoProblema.REQUISICAO_INVALIDA);
    pd.setTitle("Requisição inválida");
    pd.setDetail("Um ou mais parâmetros falharam na validação.");
    pd.setInstance(URI.create(request.getRequestURI()));
    pd.setProperty("traceId", MDC.get("correlationId"));
    pd.setProperty("errors", erros);
    return pd;
  }

  /** Do propertyPath {@code metodo.argumento}, só o argumento interessa ao cliente. */
  private static String nomeDoParametro(ConstraintViolation<?> violacao) {
    String caminho = violacao.getPropertyPath().toString();
    int ultimoPonto = caminho.lastIndexOf('.');
    return ultimoPonto < 0 ? caminho : caminho.substring(ultimoPonto + 1);
  }

  @ExceptionHandler(BloqueioException.class)
  ResponseEntity<ProblemDetail> handleBloqueio(BloqueioException ex, HttpServletRequest request) {
    ProblemDetail pd =
        ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
    pd.setType(TipoProblema.LIMITE_REQUISICOES);
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
    // A exceção escolhe o próprio tipo (getTipo), sem este handler conhecê-la.
    pd.setType(ex.getTipo());
    pd.setInstance(URI.create(request.getRequestURI()));
    // Campos da exceção ANTES do traceId: uma subclasse não consegue sobrescrevê-lo.
    ex.getPropriedades().forEach(pd::setProperty);
    pd.setProperty("traceId", MDC.get("correlationId"));
    return pd;
  }

  /**
   * 403 de @PreAuthorize: o interceptor lança dentro do proxy do controller, antes do filtro do
   * Security — sem este handler, viraria 500.
   */
  @ExceptionHandler(AccessDeniedException.class)
  ProblemDetail handleAcessoNegadoSecurity(AccessDeniedException ex, HttpServletRequest request) {
    // Sem detalhe: a resposta não confirma nem nega a existência do recurso.
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Acesso negado");
    pd.setType(TipoProblema.ACESSO_NEGADO);
    pd.setInstance(URI.create(request.getRequestURI()));
    pd.setProperty("traceId", MDC.get("correlationId"));
    return pd;
  }

  /**
   * Colisão de @Version nos caminhos que não travam a linha (PATCH). O aceite concorrente não passa
   * aqui: é serializado por FOR UPDATE.
   */
  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  ProblemDetail handleConflitoConcorrencia(
      ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
    ProblemDetail pd =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "O recurso foi alterado por outra operação. Recarregue e tente de novo.");
    // Não é TRANSICAO_INVALIDA, embora 409: aqui repetir a MESMA requisição tende a funcionar.
    pd.setType(TipoProblema.CONFLITO_CONCORRENCIA);
    pd.setInstance(URI.create(request.getRequestURI()));
    pd.setProperty("traceId", MDC.get("correlationId"));
    return pd;
  }

  /**
   * Violação de integridade, por SQLState — só o 22001 (valor maior que a coluna) vira 400, com
   * {@code warn}. Todo o resto continua 500: nada captura violação de integridade para tratar
   * replay de idempotência. É segunda barreira para coluna estreita adicionada no futuro.
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  ProblemDetail handleIntegridade(DataIntegrityViolationException ex, HttpServletRequest request) {
    if (!"22001".equals(sqlState(ex))) {
      return handleGenerico(ex, request);
    }
    log.warn(
        "Valor excede o tamanho da coluna [traceId={}, uri={}]",
        MDC.get("correlationId"),
        request.getRequestURI());
    ProblemDetail pd =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, "Um dos valores enviados excede o tamanho máximo aceito.");
    pd.setType(TipoProblema.REQUISICAO_INVALIDA);
    pd.setInstance(URI.create(request.getRequestURI()));
    pd.setProperty("traceId", MDC.get("correlationId"));
    return pd;
  }

  /** O SQLState vive na {@code SQLException} no fundo da cadeia de causas. */
  private static String sqlState(Throwable ex) {
    for (Throwable causa = ex; causa != null; causa = causa.getCause()) {
      if (causa instanceof java.sql.SQLException sql) {
        return sql.getSQLState();
      }
      if (causa.getCause() == causa) {
        break;
      }
    }
    return null;
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail handleGenerico(Exception ex, HttpServletRequest request) {
    log.error("Erro inesperado [traceId={}]", MDC.get("correlationId"), ex);
    ProblemDetail pd =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno. Contate o suporte.");
    pd.setType(TipoProblema.ERRO_INTERNO);
    pd.setInstance(URI.create(request.getRequestURI()));
    pd.setProperty("traceId", MDC.get("correlationId"));
    return pd;
  }

  private String mensagemCampo(FieldError f) {
    return f.getDefaultMessage() != null ? f.getDefaultMessage() : "valor inválido";
  }

  /**
   * Caminho da requisição a partir do {@link WebRequest} (sem o prefixo "uri=" do formato de log).
   */
  private static String uriDe(WebRequest request) {
    String descricao = request.getDescription(false);
    return descricao.startsWith("uri=") ? descricao.substring(4) : descricao;
  }

  record ErroCampo(String campo, String mensagem) {}
}
