package com.omnitribo.identidade.infra;

import com.omnitribo.identidade.dominio.RefreshToken;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  // PESSIMISTIC_WRITE: serializa rotação e detecção de reuso sob concorrência.
  // Sem este lock, duas requisições paralelas com o mesmo token veem revogado_em=null e ambas
  // rotacionam — o token atômico deixa de ser garantido.
  // Timeout de 5s: evita espera indefinida em cenário de contenção extrema.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
  Optional<RefreshToken> findByTokenHash(String tokenHash);

  List<RefreshToken> findByFamiliaId(UUID familiaId);
}
