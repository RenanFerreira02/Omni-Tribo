package com.omnitribo.compartilhado.dominio;

import org.springframework.http.HttpStatus;

/**
 * Ator autenticado sem autoridade sobre o recurso. 403 Forbidden.
 *
 * <p>A mensagem nunca descreve o estado do recurso — descrevê-lo transformaria o 403 em oráculo
 * sobre dados alheios.
 */
public class AcessoNegadoException extends DominioException {

  public AcessoNegadoException(String mensagem) {
    super(HttpStatus.FORBIDDEN, mensagem);
  }
}
