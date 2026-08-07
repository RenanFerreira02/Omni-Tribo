package com.omnitribo.compartilhado.dominio;

import com.omnitribo.compartilhado.infra.AlertaRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Entrega de eventos da outbox como alerta in-app.
 *
 * <p>Destino provisório e assumido como tal: nesta fase "despachar" significa gravar uma linha em
 * {@code alerta}, a caixa de entrada do app. O push real chega em F10 e troca só o corpo deste
 * despachante — o contrato do drenador, o backoff e a garantia de entrega at-least-once não mudam,
 * porque é exatamente essa separação que o padrão outbox compra.
 */
@Component
public class DespachanteAlerta {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final AlertaRepository alertaRepository;

  public DespachanteAlerta(AlertaRepository alertaRepository) {
    this.alertaRepository = alertaRepository;
  }

  /**
   * Traduz um evento em alerta. Tipo desconhecido lança, e isso é intencional: o evento volta para
   * a outbox com backoff em vez de ser descartado, e {@code ultimo_erro} registra o que faltou.
   * Descartar em silêncio perderia um fato que o resto do sistema já considera consumado.
   */
  public void despachar(Outbox evento) {
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = MAPPER.readValue(evento.getPayload(), Map.class);

    switch (evento.getTipoEvento()) {
      case "MissaoConcluida" -> gravarConclusao(evento, payload);
      default ->
          throw new IllegalStateException(
              "Nenhum despachante para o evento " + evento.getTipoEvento() + ".");
    }
  }

  private void gravarConclusao(Outbox evento, Map<String, Object> payload) {
    UUID executorId = UUID.fromString((String) payload.get("executorId"));
    boolean subiuDeNivel = Boolean.TRUE.equals(payload.get("subiuDeNivel"));

    String corpo =
        subiuDeNivel
            ? "Missão concluída e recompensa creditada. Você subiu para o nível "
                + payload.get("nivelAtual")
                + "."
            : "Missão concluída. A recompensa já está na sua carteira.";

    alertaRepository.save(
        new Alerta(
            UUID.randomUUID(),
            executorId,
            "MISSAO_CONCLUIDA",
            "Recompensa creditada",
            corpo,
            evento.getAgregadoId(),
            Instant.now()));
  }
}
