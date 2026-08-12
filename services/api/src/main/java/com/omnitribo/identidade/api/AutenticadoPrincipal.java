package com.omnitribo.identidade.api;

import com.omnitribo.identidade.dominio.PapelUsuario;
import java.util.UUID;

/**
 * Principal injetado via @AuthenticationPrincipal nos controllers. Posicionado em identidade/api/
 * (interface pública do módulo) para que outros módulos possam importá-lo sem violar a regra
 * ArchUnit, que proíbe acesso direto a identidade/dominio/ ou identidade/infra/ de fora do módulo.
 */
public record AutenticadoPrincipal(UUID id, String email, PapelUsuario papel) {

  /**
   * Cria a partir de strings extraídas de claims JWT. Usado pelo JwtAuthFilter
   * (compartilhado/infra/), que não pode referenciar PapelUsuario (identidade/dominio/) diretamente
   * — a fábrica encapsula o valueOf dentro da camada api/ do módulo.
   */
  public static AutenticadoPrincipal deClaims(UUID id, String email, String papelString) {
    return new AutenticadoPrincipal(id, email, PapelUsuario.valueOf(papelString));
  }

  /**
   * Authority no formato do Spring Security ({@code ROLE_ADMIN}).
   *
   * <p>Mora aqui pela mesma razão de {@link #deClaims}: quem monta a autenticação é o {@code
   * JwtAuthFilter}, em {@code compartilhado/infra}, que não pode importar {@code PapelUsuario} de
   * {@code identidade/dominio} — então nem o {@code .name()} pode ser chamado de lá.
   */
  public String autoridade() {
    return "ROLE_" + papel.name();
  }
}
