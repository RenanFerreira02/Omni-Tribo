package com.omnitribo.notificacoes.infra;

import com.omnitribo.notificacoes.dominio.Alerta;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertaRepository extends JpaRepository<Alerta, UUID> {

  /**
   * Caixa de entrada do dono (o {@code usuarioId} vem sempre do JWT): MAIS URGENTE primeiro, depois
   * mais recente — uma entrega difícil de três horas atrás ainda precisa de alguém. Suportada por
   * {@code idx_alerta_usuario_prioridade} (V22).
   */
  Page<Alerta> findByUsuarioIdOrderByPrioridadeDescCriadoEmDesc(UUID usuarioId, Pageable pageable);

  Page<Alerta> findByUsuarioIdAndLidoFalseOrderByPrioridadeDescCriadoEmDesc(
      UUID usuarioId, Pageable pageable);

  long countByUsuarioIdAndLidoFalse(UUID usuarioId);

  /** Busca escopada pelo dono: alerta alheio vira 404, não 403 (403 confirmaria que existe). */
  Optional<Alerta> findByIdAndUsuarioId(UUID id, UUID usuarioId);

  /**
   * Alertas recebidos desde o instante dado, para o teto por hora ({@code
   * idx_alerta_usuario_recente}).
   */
  long countByUsuarioIdAndCriadoEmAfter(UUID usuarioId, Instant desde);

  /**
   * Este alerta já existe para este usuário e esta missão? A entrega da outbox pode REPETIR, e o
   * duplicado consumiria o teto por hora. Em Java e não por UNIQUE: {@code missao_id} é nulo em
   * muitos alertas, e UNIQUE é NULLS DISTINCT.
   */
  boolean existsByUsuarioIdAndTipoAndMissaoId(UUID usuarioId, String tipo, UUID missaoId);

  /**
   * Grava o alerta operacional GLOBAL no máximo uma vez por {@code (tipo, referencia, janela)} (ADR
   * 0033). {@code ON CONFLICT DO NOTHING} decide no índice, porque o conflito é entre INSERTs de
   * uma linha que ainda não existe — não há linha para travar. O {@code WHERE} depois do {@code ON
   * CONFLICT} repete o predicado do índice parcial {@code uk_alerta_operacional}: sem ele o
   * PostgreSQL não infere o índice e a query falha.
   *
   * @return 1 se gravou, 0 se já havia alerta deste tipo para esta referência nesta janela.
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO alerta (id, usuario_id, tipo, titulo, corpo, missao_id, lido, criado_em,
                              prioridade, referencia, janela_inicio)
          VALUES (:id, NULL, :tipo, :titulo, :corpo, NULL, FALSE, :criadoEm, 0,
                  :referencia, :janelaInicio)
          ON CONFLICT (tipo, referencia, janela_inicio)
              WHERE usuario_id IS NULL
                AND referencia IS NOT NULL
                AND janela_inicio IS NOT NULL
          DO NOTHING
          """,
      nativeQuery = true)
  int inserirOperacionalSeAusente(
      @Param("id") UUID id,
      @Param("tipo") String tipo,
      @Param("titulo") String titulo,
      @Param("corpo") String corpo,
      @Param("referencia") String referencia,
      @Param("janelaInicio") Instant janelaInicio,
      @Param("criadoEm") Instant criadoEm);
}
