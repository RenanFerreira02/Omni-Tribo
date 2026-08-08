package com.omnitribo.compartilhado.dominio;

import com.omnitribo.compartilhado.api.TipoProblema;
import java.net.URI;
import org.springframework.http.HttpStatus;

public class DominioException extends RuntimeException {

  private final HttpStatus httpStatus;

  public DominioException(HttpStatus httpStatus, String mensagem) {
    super(mensagem);
    this.httpStatus = httpStatus;
  }

  public HttpStatus getHttpStatus() {
    return httpStatus;
  }

  /**
   * Valor do campo {@code type} do RFC 9457 para esta exceção.
   *
   * <p>Sobrescrito por cada subclasse com um tipo específico. A implementação padrão deriva do
   * status, e existe para quem lança {@code DominioException} direto — hoje só o fluxo de
   * autenticação, que precisa de 401 sem revelar qual das causas ocorreu. O efeito é que nenhuma
   * resposta de erro do sistema sai com {@code about:blank}, mesmo sem subclasse dedicada.
   */
  public URI getTipo() {
    return TipoProblema.deStatus(httpStatus);
  }
}
