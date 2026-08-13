package com.omnitribo.identidade.dominio;

import java.time.Instant;
import java.util.UUID;

/**
 * Projeção escalar do que a autenticação precisa saber sobre uma conta.
 *
 * <p><b>Projeção, e não a entidade {@code Usuario}</b> — pelo mesmo motivo documentado em {@code
 * UsuarioRepository.buscarTriboId}, e aqui o motivo é mais grave. Esta leitura acontece no FILTRO,
 * antes de qualquer método de negócio. Materializar {@code Usuario} o poria no persistence context
 * da requisição, e um {@code buscarParaAtualizar} posterior — que é como toda operação de valor
 * adquire o lock — devolveria a instância em cache <b>sem reemitir o {@code SELECT ... FOR
 * UPDATE}</b>. O teste passaria e o lock nunca teria existido, em toda requisição autenticada do
 * sistema.
 */
public record EstadoDaConta(
    UUID id, String email, PapelUsuario papel, StatusUsuario status, Instant anonimizadoEm) {

  /**
   * Conta apta a agir. Anonimizada é barrada mesmo se {@code status} divergir: são duas colunas, e
   * exigir as duas evita que uma correção manual em só uma delas reabra a janela.
   */
  public boolean ativa() {
    return status == StatusUsuario.ATIVO && anonimizadoEm == null;
  }
}
