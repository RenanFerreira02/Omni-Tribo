package com.omnitribo.compartilhado.dominio;

/**
 * Lançada quando um usuário/IP excede o limite de tentativas de login. Vive em
 * compartilhado/dominio para que GlobalExceptionHandler (compartilhado/api) e AutenticacaoService
 * (identidade/dominio) possam referenciá-la sem violar as regras de módulos do ArchUnit.
 */
public class BloqueioException extends RuntimeException {

  private final long segundosRestantes;

  public BloqueioException(long segundosRestantes) {
    super("Muitas tentativas. Aguarde " + segundosRestantes + " segundos.");
    this.segundosRestantes = segundosRestantes;
  }

  public long getSegundosRestantes() {
    return segundosRestantes;
  }
}
