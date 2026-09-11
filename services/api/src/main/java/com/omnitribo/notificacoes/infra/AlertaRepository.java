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
   * <p>A entrega pela outbox pode REPETIR: um evento redespachado depois de uma falha parcial chega
   * de novo aqui. Sem esta checagem o usuário receberia o alerta duplicado E o duplicado consumiria
   * o teto por hora, o que faria uma falha transitória de infraestrutura silenciar notificações
   * legítimas.
   *
   * <p>A deduplicação é em Java, e não por UNIQUE, porque a chave desta é {@code (usuario_id, tipo,
   * missao_id)} e {@code missao_id} é nulo em todo aviso sem missão associada — em PostgreSQL um
   * UNIQUE é NULLS DISTINCT por padrão, então ele não restringiria nenhuma dessas linhas. (A V29
   * criou {@code uk_alerta_operacional}, mas ele é PARCIAL e cobre só o alerta global de {@code
   * usuario_id} nulo; a caixa de entrada por usuário continua sem UNIQUE.)
   */
  boolean existsByUsuarioIdAndTipoAndMissaoId(UUID usuarioId, String tipo, UUID missaoId);

  /**
   * Grava o alerta operacional GLOBAL no máximo uma vez por {@code (tipo, referencia, janela)}.
   *
   * <p>É a correção da pendência medida em {@code docs/evidencias/f21-carga.md} §6: uma rajada de
   * webhooks contra um ponto de custódia cheio gravava uma linha por evento — 631 delas em menos de
   * 3 minutos, todas com a mesma frase sobre o mesmo ponto. O teto {@code alertasPorHora} não
   * alcança isto e está certo em não alcançar: ele conta {@code WHERE usuario_id = ?}, e estes
   * alertas têm {@code usuario_id} nulo de propósito. Ver ADR 0033.
   *
   * <p><b>Por que SQL nativo, e não a sondagem-sob-lock do resto do projeto.</b> A ordem canônica
   * de {@code services/api/CLAUDE.md} — travar, sondar, validar, escrever — existe onde há uma
   * linha para travar. Aqui não há: o conflito é entre dois INSERTs da MESMA linha inexistente, e
   * dois drenadores podem estar no mesmo instante (o {@code buscarPendentesParaPublicar} usa {@code
   * SKIP LOCKED}, então lotes disjuntos correm em paralelo). {@code ON CONFLICT DO NOTHING} resolve
   * no índice, dentro do próprio INSERT. E não viola a regra vizinha — "nada captura {@code
   * DataIntegrityViolationException} para tratar replay" —, porque nenhuma exceção é lançada: a
   * linha repetida simplesmente não entra.
   *
   * <p><b>O {@code WHERE} depois do {@code ON CONFLICT} não é decoração e não pode ser
   * encurtado.</b> Ele repete, palavra por palavra, o predicado de {@code uk_alerta_operacional}.
   * Sem isso o PostgreSQL não consegue inferir qual índice arbitra e responde {@code "there is no
   * unique or exclusion constraint matching the ON CONFLICT specification"} — verificado contra o
   * banco antes de escrever esta query, não deduzido da documentação.
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
