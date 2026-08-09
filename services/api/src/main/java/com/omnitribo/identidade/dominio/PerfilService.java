package com.omnitribo.identidade.dominio;

import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import com.omnitribo.identidade.api.ConquistaResponse;
import com.omnitribo.identidade.api.PerfilResponse;
import com.omnitribo.identidade.infra.UsuarioRepository;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Perfil completo: identidade, progressão e conquistas. */
@Service
public class PerfilService {

  private final UsuarioRepository usuarioRepository;
  private final TriboService triboService;
  private final CalculadoraDeConquistas.Calibracao calibracao;

  public PerfilService(
      UsuarioRepository usuarioRepository,
      TriboService triboService,
      @Value("${app.identidade.conquistas.xp-iniciante:1}") long xpIniciante,
      @Value("${app.identidade.conquistas.xp-vizinho-presente:500}") long xpVizinhoPresente,
      @Value("${app.identidade.conquistas.xp-pilar-da-tribo:2000}") long xpPilarDaTribo,
      @Value("${app.identidade.conquistas.nivel-veterano:10}") int nivelVeterano,
      @Value("${app.identidade.conquistas.streak-constante:7}") int streakConstante,
      @Value("${app.identidade.conquistas.versao:1}") int versao) {
    this.usuarioRepository = usuarioRepository;
    this.triboService = triboService;
    this.calibracao =
        new CalculadoraDeConquistas.Calibracao(
            xpIniciante, xpVizinhoPresente, xpPilarDaTribo, nivelVeterano, streakConstante, versao);
  }

  @Transactional(readOnly = true)
  public PerfilResponse perfil(UUID usuarioId) {
    Usuario usuario =
        usuarioRepository
            .findById(usuarioId)
            // Conta anonimizada some daqui como se não existisse. O access token vive 15 minutos e
            // sobrevive à exclusão; sem este filtro, a tela de perfil exibiria "Usuário removido"
            // com o XP intacto — um fantasma da conta que a pessoa acabou de pedir para apagar.
            .filter(u -> !u.anonimizado())
            // 404 e não 401: o token é válido, o usuário é que não está mais lá.
            .orElseThrow(() -> new RecursoNaoEncontradoException("Usuário não encontrado."));

    // Nível DERIVADO do XP, não lido de usuario.nivel — aquela coluna é cache recalculado a cada
    // concessão, e se as duas divergirem quem está certo é a fórmula.
    int nivel = RegraNivel.nivelPara(usuario.getXp());

    return new PerfilResponse(
        usuario.getId(),
        usuario.getNome(),
        usuario.getEmail(),
        usuario.getHandle(),
        usuario.getPapel().name(),
        triboService.daUsuario(usuario.getTriboId()).orElse(null),
        usuario.getXp(),
        nivel,
        RegraNivel.xpParaNivel(nivel),
        RegraNivel.xpParaNivel(nivel + 1),
        usuario.getStreak(),
        CalculadoraDeConquistas.avaliar(usuario.getXp(), nivel, usuario.getStreak(), calibracao)
            .stream()
            .map(
                c ->
                    new ConquistaResponse(
                        c.codigo(),
                        c.titulo(),
                        c.descricao(),
                        c.conquistada(),
                        c.progresso(),
                        c.meta()))
            .toList());
  }
}
