package com.omnitribo.identidade.infra;

import com.omnitribo.identidade.dominio.Consentimento;
import com.omnitribo.identidade.dominio.TipoConsentimento;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsentimentoRepository extends JpaRepository<Consentimento, UUID> {

  List<Consentimento> findByUsuarioIdOrderByCriadoEmDesc(UUID usuarioId);

  /**
   * Estado ATUAL de um consentimento: a linha mais recente daquele tipo.
   *
   * <p>{@code findFirst...OrderBy}, e não {@code findByUsuarioIdAndTipo}. A tabela é append-only —
   * cada mudança de escolha grava uma linha nova, para que "quando ele consentiu, e sob qual versão
   * do texto?" continue respondível. Um finder sem ordenação e sem limite estouraria com {@code
   * NonUniqueResultException} na PRIMEIRA revogação de qualquer usuário.
   */
  Optional<Consentimento> findFirstByUsuarioIdAndTipoOrderByCriadoEmDesc(
      UUID usuarioId, TipoConsentimento tipo);
}
