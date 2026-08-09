package com.omnitribo.notificacoes.infra;

import com.omnitribo.notificacoes.dominio.Alerta;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertaRepository extends JpaRepository<Alerta, UUID> {

  /**
   * Caixa de entrada, sempre do MAIS RECENTE para o mais antigo e sempre filtrada pelo dono.
   *
   * <p>O {@code usuarioId} nunca vem do cliente — o controller o tira do JWT. Um filtro vindo da
   * query string transformaria este método na caixa de entrada alheia.
   */
  Page<Alerta> findByUsuarioIdOrderByCriadoEmDesc(UUID usuarioId, Pageable pageable);

  Page<Alerta> findByUsuarioIdAndLidoFalseOrderByCriadoEmDesc(UUID usuarioId, Pageable pageable);

  long countByUsuarioIdAndLidoFalse(UUID usuarioId);

  /**
   * Busca escopada pelo dono, e não {@code findById} seguido de comparação.
   *
   * <p>A diferença aparece na resposta: assim o alerta de outra pessoa some no filtro e vira 404,
   * em vez de ser encontrado e recusado com 403 — que confirmaria a existência do id.
   */
  Optional<Alerta> findByIdAndUsuarioId(UUID id, UUID usuarioId);
}
