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
}
