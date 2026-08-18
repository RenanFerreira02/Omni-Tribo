package com.omnitribo.identidade.dominio;

import com.omnitribo.identidade.api.ConsultaAfiliacao;
import com.omnitribo.identidade.infra.UsuarioRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implementação de {@link ConsultaAfiliacao}. */
@Service
public class ConsultaAfiliacaoService implements ConsultaAfiliacao {

  private final UsuarioRepository usuarioRepository;

  public ConsultaAfiliacaoService(UsuarioRepository usuarioRepository) {
    this.usuarioRepository = usuarioRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<UUID> triboDe(UUID usuarioId) {
    return usuarioRepository.buscarTriboId(usuarioId);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean mesmaTribo(UUID usuarioA, UUID usuarioB) {
    Optional<UUID> triboA = usuarioRepository.buscarTriboId(usuarioA);
    Optional<UUID> triboB = usuarioRepository.buscarTriboId(usuarioB);
    // isPresent() nos dois ANTES de comparar: sem essa guarda, dois Optional.empty() (usuário
    // inexistente, ou existente e sem tribo) se igualariam e a resposta viraria "mesma tribo".
    // Optional.equals compara conteúdo, então empty().equals(empty()) é true — é exatamente esse
    // resultado que precisa ser recusado aqui.
    return triboA.isPresent() && triboB.isPresent() && triboA.get().equals(triboB.get());
  }
}
