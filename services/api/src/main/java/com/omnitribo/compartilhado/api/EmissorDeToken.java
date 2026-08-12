package com.omnitribo.compartilhado.api;

import java.util.UUID;

/**
 * PORTA de emissão de access token.
 *
 * <p>Existe para que {@code identidade/dominio} não precise importar {@code
 * compartilhado/infra/JwtService} — um domínio dependendo do adaptador de outro módulo, que é a
 * direção que o resto do projeto trata como proibida e que o ArchUnit não pegava porque {@code
 * compartilhado} estava fora da regra.
 *
 * <p><b>Só a emissão atravessa a fronteira.</b> {@code JwtService.validar} devolve {@code
 * io.jsonwebtoken.Claims} e fica de fora de propósito: pôr um tipo da biblioteca de JWT numa porta
 * pública faria a escolha da lib vazar para todos os módulos, que é justamente o que uma porta
 * existe para impedir. Quem valida é o filtro de autenticação, que já vive em {@code
 * compartilhado/infra} e pode falar com a implementação direto.
 */
public interface EmissorDeToken {

  /**
   * Emite um access token assinado (RS256) com {@code sub}, {@code email}, {@code papel} e {@code
   * jti}.
   *
   * <p>{@code papel} entra como String pela mesma restrição de fronteira que dá forma a {@code
   * ConsultasGeoespaciais}: a regra do ArchUnit é DIRECIONAL, e {@code compartilhado} continua
   * sendo ORIGEM — não pode importar {@code PapelUsuario}, de {@code identidade/dominio}.
   */
  String emitirAccessToken(UUID usuarioId, String email, String papel);
}
