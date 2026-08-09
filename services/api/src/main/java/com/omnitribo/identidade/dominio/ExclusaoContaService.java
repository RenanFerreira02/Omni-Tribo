package com.omnitribo.identidade.dominio;

import com.omnitribo.compartilhado.dominio.DominioException;
import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import com.omnitribo.identidade.api.ExclusaoContaRequest;
import com.omnitribo.identidade.infra.RefreshTokenRepository;
import com.omnitribo.identidade.infra.UsuarioRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Direito ao esquecimento (LGPD art. 18, VI), implementado como ANONIMIZAÇÃO.
 *
 * <p><b>Por que não DELETE.</b> {@code lancamento}, {@code auditoria} e {@code checkin} são
 * append-only e referenciam o usuário. Apagar a linha de {@code usuario} quebraria a integridade
 * referencial do ledger e faria a conservação de TOKEN deixar de fechar — o endereço de um crédito
 * que existiu não some porque o titular pediu para ser esquecido; o que some é o vínculo entre esse
 * crédito e uma pessoa identificável. É exatamente essa a leitura do direito ao esquecimento contra
 * a obrigação de retenção contábil, e está registrada no ADR 0011.
 *
 * <p><b>Ordem das operações.</b> Senha primeiro, anonimização depois, revogação por último — todas
 * na mesma transação. Se a revogação falhasse depois de um commit da anonimização, existiria uma
 * janela de até 15 minutos em que um access token ainda válido pertence a uma conta que já não tem
 * dono.
 */
@Service
public class ExclusaoContaService {

  private final UsuarioRepository usuarioRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final PasswordEncoder passwordEncoder;

  public ExclusaoContaService(
      UsuarioRepository usuarioRepository,
      RefreshTokenRepository refreshTokenRepository,
      PasswordEncoder passwordEncoder) {
    this.usuarioRepository = usuarioRepository;
    this.refreshTokenRepository = refreshTokenRepository;
    this.passwordEncoder = passwordEncoder;
  }

  @Transactional
  public void excluir(UUID usuarioId, ExclusaoContaRequest request) {
    // FOR UPDATE: duas requisições simultâneas de exclusão gerariam dois conjuntos de valores
    // aleatórios e uma colisão de @Version que viraria 409 depois de já ter anonimizado.
    Usuario usuario =
        usuarioRepository
            .buscarParaAtualizar(usuarioId)
            .orElseThrow(() -> new RecursoNaoEncontradoException("Usuário não encontrado."));

    // Já anonimizado: no-op idempotente, e não erro. Um retry de rede sobre uma operação
    // irreversível não pode devolver falha — a pessoa acharia que o pedido não foi atendido.
    if (usuario.anonimizado()) {
      return;
    }

    if (!passwordEncoder.matches(request.senha(), usuario.getSenhaHash())) {
      // 403, e não 401: o token está válido: quem falhou foi a reconfirmação. Um 401 faria o app
      // tratar como sessão expirada e deslogar, escondendo o motivo real da recusa.
      throw new DominioException(HttpStatus.FORBIDDEN, "Senha incorreta.");
    }

    Instant agora = Instant.now();
    // Hash de um segredo aleatório e descartado: ninguém — nem quem sabe a senha antiga, nem quem
    // tem o banco — consegue reproduzi-lo.
    usuario.anonimizar(agora, passwordEncoder.encode(UUID.randomUUID().toString()));
    usuarioRepository.save(usuario);

    List<RefreshToken> vivos = refreshTokenRepository.findByUsuarioIdAndRevogadoEmIsNull(usuarioId);
    vivos.forEach(token -> token.revogar(null));
    refreshTokenRepository.saveAll(vivos);
  }
}
