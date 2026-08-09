package com.omnitribo.notificacoes.dominio;

import com.omnitribo.notificacoes.api.DespachoAlerta;
import com.omnitribo.notificacoes.infra.AlertaRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Entrega de eventos da outbox como alerta in-app. Implementação da porta {@link DespachoAlerta}.
 *
 * <p>Destino provisório e assumido como tal: nesta fase "despachar" significa gravar uma linha em
 * {@code alerta}, a caixa de entrada do app. O push real troca só o corpo deste despachante — o
 * contrato do drenador, o backoff e a garantia de entrega at-least-once não mudam, porque é
 * exatamente essa separação que o padrão outbox compra.
 *
 * <p>O mapper é construído aqui, sem injeção: Jackson é o 3 (tools.jackson) em todo o repositório e
 * não existe bean de ObjectMapper para injetar. Mesmo padrão de {@code
 * MissaoService.MAPPER_TRILHA}.
 */
@Service
public class DespachanteAlertaService implements DespachoAlerta {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final AlertaRepository alertaRepository;

  public DespachanteAlertaService(AlertaRepository alertaRepository) {
    this.alertaRepository = alertaRepository;
  }

  /**
   * Traduz um evento em alerta. Tipo desconhecido lança, e isso é intencional: o evento volta para
   * a outbox com backoff em vez de ser descartado, e {@code ultimo_erro} registra o que faltou.
   * Descartar em silêncio perderia um fato que o resto do sistema já considera consumado.
   */
  @Override
  public void despachar(String tipoEvento, UUID agregadoId, String payloadJson) {
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = MAPPER.readValue(payloadJson, Map.class);

    switch (tipoEvento) {
      case "MissaoConcluida" -> gravarConclusao(agregadoId, payload);
      default ->
          throw new IllegalStateException("Nenhum despachante para o evento " + tipoEvento + ".");
    }
  }

  private void gravarConclusao(UUID missaoId, Map<String, Object> payload) {
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
            missaoId,
            Instant.now()));
  }
}
