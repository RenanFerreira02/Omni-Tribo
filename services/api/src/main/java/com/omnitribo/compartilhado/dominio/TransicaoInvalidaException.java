package com.omnitribo.compartilhado.dominio;

import org.springframework.http.HttpStatus;

/**
 * Operação incompatível com o estado atual do agregado. 409 Conflict.
 *
 * <p>Mora em compartilhado/dominio, e não em missoes/dominio, porque o GlobalExceptionHandler está
 * fora de com.omnitribo.missoes.. e a regra ArchUnit o proibiria de importar o pacote interno do
 * módulo. Herdando de DominioException, o handler existente já a mapeia pelo httpStatus.
 */
public class TransicaoInvalidaException extends DominioException {

  public TransicaoInvalidaException(String mensagem) {
    super(HttpStatus.CONFLICT, mensagem);
  }
}
