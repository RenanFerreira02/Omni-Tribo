package com.omnitribo.geolocalizacao.infra;

import com.omnitribo.geolocalizacao.dominio.Checkin;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

  /**
   * Primeiro check-in VÁLIDO de cada missão da lista (painel de impacto, ADR 0029).
   *
   * <p>{@code valido = true} aqui, ao contrário da consulta cinemática acima — e a divergência é
   * deliberada. Lá toda posição reportada conta, porque ignorar as rejeitadas permitiria lavar a
   * trilha; aqui contar as rejeitadas faria uma tentativa fraudulenta melhorar o indicador de
   * impacto do produto. A pergunta é outra, o filtro é outro.
   *
   * <p>{@code MIN} e não {@code ORDER BY ... LIMIT 1} por missão: uma agregação para o conjunto
   * inteiro, em vez de N consultas.
   */
  @Query(
      value =
          """
          SELECT missao_id AS missaoId, MIN(criado_em) AS primeiroEm
          FROM checkin
          WHERE valido = TRUE AND missao_id IN (:missaoIds)
          GROUP BY missao_id
          """,
      nativeQuery = true)
  List<PrimeiroCheckinProjecao> buscarPrimeiroValido(
      @Param("missaoIds") Collection<UUID> missaoIds);

  interface PrimeiroCheckinProjecao {
    UUID getMissaoId();

    java.time.Instant getPrimeiroEm();
  }
}
