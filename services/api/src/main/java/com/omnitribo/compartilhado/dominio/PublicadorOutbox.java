package com.omnitribo.compartilhado.dominio;

import com.omnitribo.compartilhado.api.PublicadorEventos;
import com.omnitribo.compartilhado.infra.OutboxRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Implementação de {@link PublicadorEventos}. Ver o javadoc da porta para o porquê da outbox. */
@Service
public class PublicadorOutbox implements PublicadorEventos {

  /**
   * Mapper próprio, não injetado, no mesmo padrão de {@code MissaoService.MAPPER_TRILHA}.
   *
   * <p>Técnico: o Spring Boot 4.1 autoconfigura o mapper do Jackson 3 (tools.jackson) e não existe
   * bean de {@code com.fasterxml.jackson.databind.ObjectMapper} para injetar.
   *
   * <p>De projeto: o payload gravado na outbox é registro histórico de um evento que já aconteceu.
   * Ele não pode mudar de formato porque alguém ajustou a serialização da API meses depois — o
   * consumidor que ler esta linha amanhã precisa do mesmo JSON que o produtor escreveu hoje.
   */
  private static final JsonMapper MAPPER_EVENTO = JsonMapper.builder().build();

  private final OutboxRepository outboxRepository;

  public PublicadorOutbox(OutboxRepository outboxRepository) {
    this.outboxRepository = outboxRepository;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void publicar(String tipoEvento, UUID agregadoId, Map<String, Object> payload) {
    Outbox evento =
        new Outbox(
            UUID.randomUUID(),
            tipoEvento,
            agregadoId,
            MAPPER_EVENTO.writeValueAsString(payload == null ? Map.of() : payload),
            Instant.now());
    outboxRepository.save(evento);
  }
}
