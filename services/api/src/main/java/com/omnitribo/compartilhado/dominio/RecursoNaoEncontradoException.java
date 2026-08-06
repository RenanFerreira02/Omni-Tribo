package com.omnitribo.compartilhado.dominio;

import org.springframework.http.HttpStatus;

/**
 * Recurso inexistente OU invisível para o solicitante. 404 Not Found.
 *
 * <p>Deliberadamente 404 e não 403 quando o recurso existe mas é privado (um rascunho alheio):
 * responder 403 confirmaria a existência do recurso a quem não deveria saber dela.
 */
public class RecursoNaoEncontradoException extends DominioException {

  public RecursoNaoEncontradoException(String mensagem) {
    super(HttpStatus.NOT_FOUND, mensagem);
  }
}
