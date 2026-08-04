package com.omnitribo.compartilhado.dominio;

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
}
