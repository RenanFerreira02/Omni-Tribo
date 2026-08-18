package com.omnitribo.geolocalizacao.infra;

import com.omnitribo.geolocalizacao.dominio.Checkin;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckinRepository extends JpaRepository<Checkin, UUID> {

  /**
   * Último check-in do usuário, para a checagem de plausibilidade cinemática.
   *
   * <p>Considera check-ins REJEITADOS também, de propósito. Filtrar por valido=true permitiria
   * lavar a trilha: teleportar para longe e ser rejeitado (linha não contaria), voltar, e o segundo
   * check-in nunca teria contra o que ser comparado. Toda posição reportada conta como posição
   * reportada.
   *
   * <p>Apoiado por idx_checkin_usuario_criado (V12), senão o ORDER BY viraria sort em memória.
   */
  Optional<Checkin> findFirstByUsuarioIdOrderByCriadoEmDesc(UUID usuarioId);

  /** Base do replay de idempotência. A unicidade real é garantida por uk_checkin_idempotencia. */
  Optional<Checkin> findByChaveIdempotencia(String chaveIdempotencia);
}
