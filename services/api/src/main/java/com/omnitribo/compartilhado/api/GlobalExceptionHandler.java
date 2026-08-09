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
   * Funil de TODAS as respostas de erro produzidas pelo {@code ResponseEntityExceptionHandler}.
   *
   * <p>A superclasse traz cerca de quinze handlers prontos — corpo ilegível, método não suportado,
   * media type inválido, parâmetro obrigatório ausente, handler não encontrado — e nenhum deles
   * passa pelos {@code @ExceptionHandler} escritos aqui embaixo. O resultado era que a fatia mais
   * banal de 4xx saía sem {@code type} e sem {@code traceId}, com um contrato de erro diferente do
   * resto da API: {@code POST /api/v1/missoes} com corpo {@code {}} devolvia apenas {@code
   * {"detail":"Failed to read request","instance":...,"status":400,"title":"Bad Request"}}.
   *
   * <p>Enriquecer aqui, e não sobrescrever os quinze, é o que garante que um handler que a
   * superclasse venha a ganhar numa versão futura do Spring já nasça dentro do contrato.
   *
   * <p>Só preenche o que está faltando: um handler específico que já decidiu o tipo — {@code
   * handleMethodArgumentNotValid} — continua mandando.
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
      // Uma leitura só, guardada: getProperties() é @Nullable, e chamá-lo duas vezes deixa o
      // segundo retorno fora do alcance da checagem do primeiro
      // (NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE).
      Map<String, Object> propriedades = pd.getProperties();
      if (propriedades == null || !propriedades.containsKey("traceId")) {
        pd.setProperty("traceId", MDC.get("correlationId"));
      }
    }
    return super.createResponseEntity(body, headers, statusCode, request);
  }

  /** Valor default de {@code ProblemDetail.type} — o que este handler existe para substituir. */
  private static final URI TIPO_AUSENTE = URI.create("about:blank");

  /**
   * {@code HttpStatus.valueOf} lança para código fora da enumeração, e o container pode produzir
   * um. Um erro no mapeamento do tipo não pode virar um 500 por cima do erro original.
   */
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
    // Devolve PELO funil, e não por ResponseEntity.body() direto: é o funil que preenche type,
    // instance e traceId. Antes esta era a única resposta de validação sem `instance` — justamente
    // a mais frequente da API.
    return createResponseEntity(pd, headers, status, request);
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
    pd.setType(TipoProblema.REQUISICAO_INVALIDA);
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
    // A exceção escolhe o próprio tipo: subclasse com semântica específica pode sobrescrever
    // getTipo() sem que este handler saiba da existência dela.
    pd.setType(ex.getTipo());
    pd.setInstance(URI.create(request.getRequestURI()));
    // Campos numéricos que a exceção quis expor, ANTES do traceId: assim uma subclasse descuidada
    // não consegue sobrescrever o que liga esta resposta à linha de log do servidor.
    ex.getPropriedades().forEach(pd::setProperty);
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
    pd.setType(TipoProblema.ACESSO_NEGADO);
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
    pd.setType(TipoProblema.NAO_IMPLEMENTADO);
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
    // NÃO é TRANSICAO_INVALIDA, embora ambos sejam 409: aqui o retry da MESMA requisição tende
    // a funcionar; lá, não. É exatamente a distinção que o status sozinho não expressa.
    pd.setType(TipoProblema.CONFLITO_CONCORRENCIA);
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
    pd.setType(TipoProblema.ERRO_INTERNO);
    pd.setInstance(URI.create(request.getRequestURI()));
    pd.setProperty("traceId", MDC.get("correlationId"));
    return pd;
  }

  private String mensagemCampo(FieldError f) {
    return f.getDefaultMessage() != null ? f.getDefaultMessage() : "valor inválido";
  }

  /**
   * Caminho da requisição a partir do {@link WebRequest} que o {@code
   * ResponseEntityExceptionHandler} entrega — os handlers herdados não recebem {@code
   * HttpServletRequest}.
   *
   * <p>{@code getDescription(false)} devolve {@code "uri=/api/v1/..."}; o prefixo é formato de log,
   * não faz parte do contrato da resposta.
   */
  private static String uriDe(WebRequest request) {
    String descricao = request.getDescription(false);
    return descricao.startsWith("uri=") ? descricao.substring(4) : descricao;
  }

  record ErroCampo(String campo, String mensagem) {}
}
