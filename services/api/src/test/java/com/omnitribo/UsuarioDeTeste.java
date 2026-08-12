package com.omnitribo;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Cria um usuário ATIVO mínimo, para testes que precisam de um token que de fato autentique.
 *
 * <p><b>Por que passou a ser necessário.</b> Vários testes montavam um JWT válido para um {@code
 * UUID.randomUUID()} sem linha em {@code usuario} — o token era bem assinado, o filtro autenticava
 * só com base na assinatura, e o teste seguia para o 400/404/422 que queria medir. Isso funcionava
 * porque a autenticação nunca perguntava ao banco se a conta existia, que é exatamente a Pendência
 * #3. Com {@code ConsultaSessao} no {@code JwtAuthFilter}, esses tokens passaram a receber 401.
 *
 * <p>Esses testes não estavam errados no que mediam; estavam apoiados num atalho que era o defeito.
 * A correção é dar a eles um usuário de verdade, não afrouxar a assertion.
 */
public final class UsuarioDeTeste {

  private UsuarioDeTeste() {}

  /** Insere um usuário ATIVO, sem tribo, e devolve o id. Remova com {@link #remover}. */
  public static UUID criarAtivo(JdbcTemplate jdbc, String prefixo) {
    UUID id = UUID.randomUUID();
    String sufixo = id.toString().substring(0, 8);
    jdbc.update(
        """
        INSERT INTO usuario (id, nome, email, senha_hash, handle, tribo_id, xp, nivel, streak,
                             rating, papel, status, criado_em, atualizado_em, versao)
        VALUES (?, 'Usuário de teste', ?, '{bcrypt}$2a$10$naoUsadoNesteTeste', ?,
                NULL, 0, 1, 0, 0.0, 'USUARIO', 'ATIVO', NOW(), NOW(), 0)
        """,
        id,
        prefixo + "+" + sufixo + "@teste.dev",
        prefixo + "_" + sufixo);
    return id;
  }

  public static void remover(JdbcTemplate jdbc, UUID usuarioId) {
    jdbc.update("DELETE FROM carteira WHERE usuario_id = ?", usuarioId);
    jdbc.update("DELETE FROM usuario WHERE id = ?", usuarioId);
  }
}
