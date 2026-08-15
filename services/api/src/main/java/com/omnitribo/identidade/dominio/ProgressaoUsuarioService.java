package com.omnitribo.identidade.dominio;

import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import com.omnitribo.identidade.api.ProgressaoUsuario;
import com.omnitribo.identidade.api.ResultadoProgressao;
import com.omnitribo.identidade.infra.UsuarioRepository;
import java.util.Collection;
import java.util.List;
import java.util.Set;
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

  @Override
  @Transactional(readOnly = true, propagation = Propagation.REQUIRED)
  public int nivelDe(UUID usuarioId) {
    // Só o XP é lido, e o nível sai de RegraNivel. Ler usuario.getNivel() aqui usaria o cache, que
    // pode estar defasado se alguma concessão falhou — e barrar aceite por cache defasado é negar
    // acesso a quem tem o XP, sem que a pessoa tenha o que fazer a respeito.
    //
    // REQUIRED e não MANDATORY, ao contrário de concederXp: aquele ESCREVE e precisa estar dentro
    // da transação de quem chama; este é leitura e também é usado fora do caminho de valor.
    return usuarioRepository
        .findById(usuarioId)
        .map(u -> RegraNivel.nivelPara(u.getXp()))
        .orElseThrow(() -> new RecursoNaoEncontradoException("Usuário não encontrado."));
  }

  @Override
  @Transactional(readOnly = true)
  public List<UUID> filtrarPorNivelMinimo(Collection<UUID> usuarioIds, int nivelMinimo) {
    // Coleção vazia num IN gera SQL inválido no PostgreSQL. Sem a guarda, uma tribo sem candidatos
    // derrubaria o despacho com erro de sintaxe em vez de simplesmente não notificar ninguém.
    if (usuarioIds.isEmpty()) {
      return List.of();
    }
    if (nivelMinimo <= 1) {
      // Nível 1 é o de qualquer conta nova: não filtra nada, e a consulta seria desperdício.
      return List.copyOf(usuarioIds);
    }
    // O limiar é calculado UMA vez, em Java, pela mesma RegraNivel que deriva o nível — em vez de
    // reimplementar a curva quadrática em SQL, onde ela sairia de sincronia na primeira mudança.
    return usuarioRepository.idsComXpMinimo(
        Set.copyOf(usuarioIds), RegraNivel.xpParaNivel(nivelMinimo));
  }
}
