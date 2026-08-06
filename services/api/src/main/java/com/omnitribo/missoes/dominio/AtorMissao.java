package com.omnitribo.missoes.dominio;

import java.util.Objects;
import java.util.UUID;

/**
 * Quem está agindo sobre a missão. A identidade vem SEMPRE do JWT — nunca do corpo, query ou header
 * da requisição.
 *
 * <p>Por que um enum de papel próprio em vez de PapelUsuario: PapelUsuario mora em
 * identidade/dominio, e a regra ArchUnit proíbe qualquer classe fora de com.omnitribo.identidade..
 * de acessá-lo. O controller deriva o papel da authority ROLE_ADMIN do SecurityContext, que é
 * populada pelo JwtAuthFilter a partir do claim "papel".
 */
public record AtorMissao(UUID usuarioId, PapelAtor papel) {

  public enum PapelAtor {
    USUARIO,
    ADMIN,
    SISTEMA
  }

  public AtorMissao {
    Objects.requireNonNull(papel, "papel do ator é obrigatório");
    if (papel != PapelAtor.SISTEMA && usuarioId == null) {
      throw new IllegalArgumentException("ator humano exige usuarioId vindo do JWT");
    }
  }

  public static AtorMissao usuario(UUID id) {
    return new AtorMissao(Objects.requireNonNull(id), PapelAtor.USUARIO);
  }

  public static AtorMissao admin(UUID id) {
    return new AtorMissao(Objects.requireNonNull(id), PapelAtor.ADMIN);
  }

  /** Job de expiração: sem usuário, então missao_evento.ator_id fica NULL (coluna nullable). */
  public static AtorMissao sistema() {
    return new AtorMissao(null, PapelAtor.SISTEMA);
  }

  public boolean ehAdmin() {
    return papel == PapelAtor.ADMIN;
  }

  public boolean ehSistema() {
    return papel == PapelAtor.SISTEMA;
  }

  public boolean ehMesmo(UUID outro) {
    return usuarioId != null && usuarioId.equals(outro);
  }
}
