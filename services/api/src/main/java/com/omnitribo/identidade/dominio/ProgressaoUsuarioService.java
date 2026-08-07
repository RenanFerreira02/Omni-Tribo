package com.omnitribo.identidade.dominio;

import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import com.omnitribo.identidade.api.ProgressaoUsuario;
import com.omnitribo.identidade.api.ResultadoProgressao;
import com.omnitribo.identidade.infra.UsuarioRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Implementação de {@link ProgressaoUsuario}. Ver o javadoc da porta para o contrato. */
@Service
public class ProgressaoUsuarioService implements ProgressaoUsuario {

  private final UsuarioRepository usuarioRepository;

  public ProgressaoUsuarioService(UsuarioRepository usuarioRepository) {
    this.usuarioRepository = usuarioRepository;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public ResultadoProgressao concederXp(UUID usuarioId, long quantidade) {
    if (quantidade < 0) {
      // XP é monotônico por decisão de produto (ADR 0004). Recusar aqui e não silenciosamente
      // ignorar: quem passou negativo tem um bug, e mascarar transformaria o bug num dado errado.
      throw new IllegalArgumentException("XP não pode ser negativo.");
    }

    Usuario usuario =
        usuarioRepository
            .buscarParaAtualizar(usuarioId)
            .orElseThrow(() -> new RecursoNaoEncontradoException("Usuário não encontrado."));

    int nivelAnterior = usuario.getNivel();
    usuario.adicionarXp(quantidade);

    // Nível é DERIVADO do XP, sempre recalculado, nunca incrementado. Incrementar acumularia erro:
    // uma concessão perdida deixaria o nível permanentemente defasado do XP, e não haveria como
    // detectar a divergência depois. Recalcular torna a coluna `nivel` um cache verificável.
    int nivelAtual = RegraNivel.nivelPara(usuario.getXp());
    usuario.setNivel(nivelAtual);
    usuarioRepository.save(usuario);

    return new ResultadoProgressao(usuario.getXp(), nivelAnterior, nivelAtual);
  }
}
