package com.omnitribo.notificacoes.infra;

import com.omnitribo.notificacoes.dominio.Alerta;
import java.time.Instant;
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
  /**
   * Caixa de entrada: MAIS URGENTE primeiro, depois mais recente.
   *
   * <p>Prioridade antes da data porque a lista é o que o usuário lê de cima para baixo, e uma
   * entrega difícil que chegou há três horas continua precisando de alguém mais que uma trivial de
   * dez minutos atrás. Suportada por {@code idx_alerta_usuario_prioridade} (V22) — sem o índice
   * casando com esta ordenação, a paginação viraria sort em memória sobre a caixa inteira.
   */
  Page<Alerta> findByUsuarioIdOrderByPrioridadeDescCriadoEmDesc(UUID usuarioId, Pageable pageable);

  Page<Alerta> findByUsuarioIdAndLidoFalseOrderByPrioridadeDescCriadoEmDesc(
      UUID usuarioId, Pageable pageable);

  long countByUsuarioIdAndLidoFalse(UUID usuarioId);

  /**
   * Busca escopada pelo dono, e não {@code findById} seguido de comparação.
   *
   * <p>A diferença aparece na resposta: assim o alerta de outra pessoa some no filtro e vira 404,
   * em vez de ser encontrado e recusado com 403 — que confirmaria a existência do id.
   */
  Optional<Alerta> findByIdAndUsuarioId(UUID id, UUID usuarioId);

  /**
   * Quantos alertas este usuário recebeu desde o instante dado. Alimenta o teto por hora.
   *
   * <p>Índice {@code idx_alerta_usuario_recente (usuario_id, criado_em DESC)} criado pela V21: os
   * índices anteriores eram {@code (usuario_id)} e {@code (usuario_id, lido)} parcial, e nenhum
   * ordena por tempo — cada notificação enviada varreria todo o histórico do destinatário.
   */
  long countByUsuarioIdAndCriadoEmAfter(UUID usuarioId, Instant desde);

  /**
   * Já existe este alerta para este usuário e esta missão?
   *
   * <p>A entrega da outbox é at-least-once: um evento redespachado depois de uma falha parcial
   * chega de novo aqui. Sem esta checagem o usuário receberia o alerta duplicado E o duplicado
   * consumiria o teto por hora, o que faria uma falha transitória de infraestrutura silenciar
   * notificações legítimas. Não há UNIQUE na tabela porque {@code alerta} também guarda avisos sem
   * missão associada.
   */
  boolean existsByUsuarioIdAndTipoAndMissaoId(UUID usuarioId, String tipo, UUID missaoId);
}
