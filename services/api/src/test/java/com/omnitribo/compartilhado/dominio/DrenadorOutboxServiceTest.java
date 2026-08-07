package com.omnitribo.compartilhado.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnitribo.TesteIntegracaoBase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Ciclo de vida de um evento na outbox: fica pendente → é despachado → é marcado.
 *
 * <p>Chama o serviço direto em vez de esperar o {@code @Scheduled}, pelo mesmo motivo de {@code
 * ExpiracaoMissoesServiceTest}: {@code app.agendamento.habilitado} é {@code false} no perfil de
 * teste, justamente para que nenhum job mude estado entre o arrange e o assert de outros testes. A
 * regra é do serviço; o job só a dispara.
 */
class DrenadorOutboxServiceTest extends TesteIntegracaoBase {

  @Autowired DrenadorOutboxService drenadorOutboxService;
  @Autowired JdbcTemplate jdbcTemplate;

  private UUID usuarioId;
  private UUID eventoId;

  @AfterEach
  void limpar() {
    if (eventoId != null) {
      jdbcTemplate.update("DELETE FROM outbox WHERE id = ?", eventoId);
    }
    if (usuarioId != null) {
      jdbcTemplate.update("DELETE FROM alerta WHERE usuario_id = ?", usuarioId);
      jdbcTemplate.update("DELETE FROM usuario WHERE id = ?", usuarioId);
    }
  }

  @Test
  void eventoPendenteEhDespachadoEMarcadoComoPublicado() {
    usuarioId = criarUsuario();
    UUID missaoId = UUID.randomUUID();
    eventoId = inserirEvento("MissaoConcluida", missaoId, payloadConclusao(usuarioId, false));

    assertThat(publicadoEm()).as("nasce pendente").isNull();

    int processados = drenadorOutboxService.drenarLote(10);

    assertThat(processados).isPositive();
    assertThat(publicadoEm()).as("marcado como publicado depois do despacho").isNotNull();
    assertThat(tentativas()).as("sucesso na primeira não incrementa tentativas").isZero();

    // O despacho desta fase grava um alerta in-app; em F10 vira push, sem mudar o drenador.
    Long alertas =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM alerta WHERE usuario_id = ? AND missao_id = ?",
            Long.class,
            usuarioId,
            missaoId);
    assertThat(alertas).as("o evento virou alerta para o executor").isEqualTo(1L);
  }

  @Test
  void eventoJaPublicadoNaoEhDespachadoDeNovo() {
    usuarioId = criarUsuario();
    eventoId =
        inserirEvento("MissaoConcluida", UUID.randomUUID(), payloadConclusao(usuarioId, true));

    drenadorOutboxService.drenarLote(10);
    long alertasDepoisDoPrimeiro = contarAlertas();

    // Segunda passagem: a linha já tem publicado_em, então sai do predicado do lote. Sem isso, o
    // drenador reentregaria o mesmo evento a cada varredura, para sempre.
    drenadorOutboxService.drenarLote(10);

    assertThat(contarAlertas())
        .as("evento publicado não volta ao lote")
        .isEqualTo(alertasDepoisDoPrimeiro);
  }

  @Test
  void despachoQueFalhaIncrementaTentativasEAdiaAProximaTentativa() {
    // Tipo sem despachante: o DespachanteAlerta lança, e é isso que o teste quer. Descartar em
    // silêncio perderia um fato que o resto do sistema já considera consumado.
    eventoId = inserirEvento("EventoDesconhecido", UUID.randomUUID(), Map.of("x", 1));

    Instant antes = proximaTentativaEm();
    int processados = drenadorOutboxService.drenarLote(10);

    assertThat(processados).isEqualTo(1);
    assertThat(publicadoEm()).as("falha não marca como publicado").isNull();
    assertThat(tentativas()).as("a falha foi contada").isEqualTo(1);
    assertThat(proximaTentativaEm())
        .as("backoff empurra a próxima tentativa para o futuro")
        .isAfter(antes);
    assertThat(ultimoErro()).as("o motivo fica registrado para diagnóstico").isNotBlank();
  }

  @Test
  void eventoEmBackoffNaoEntraNoLoteAntesDaHora() {
    eventoId = inserirEvento("EventoDesconhecido", UUID.randomUUID(), Map.of("x", 1));
    jdbcTemplate.update(
        "UPDATE outbox SET proxima_tentativa_em = ? WHERE id = ?",
        java.sql.Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS)),
        eventoId);

    // Sem o filtro por proxima_tentativa_em, um evento em espera continuaria no topo de todo lote e
    // o drenador giraria em falso contra a mesma linha a cada varredura.
    assertThat(drenadorOutboxService.drenarLote(10))
        .as("evento aguardando backoff não é reprocessado")
        .isZero();
    assertThat(tentativas()).isZero();
  }

  @Test
  void eventoQueEsgotouAsTentativasSaiDoLote() {
    eventoId = inserirEvento("EventoDesconhecido", UUID.randomUUID(), Map.of("x", 1));
    // app.outbox.maximo-tentativas = 5. Depois disso o evento para de consumir recurso e espera
    // intervenção — reprocessar para sempre um evento envenenado é o pior modo de falha da fila.
    jdbcTemplate.update("UPDATE outbox SET tentativas = 5 WHERE id = ?", eventoId);

    assertThat(drenadorOutboxService.drenarLote(10)).isZero();
    assertThat(publicadoEm()).isNull();
  }

  // ─── Apoio ───────────────────────────────────────────────────────────────────────────────────

  private Map<String, Object> payloadConclusao(UUID executorId, boolean subiuDeNivel) {
    return Map.of(
        "missaoId",
        UUID.randomUUID().toString(),
        "executorId",
        executorId.toString(),
        "tokens",
        10,
        "nivelAtual",
        2,
        "subiuDeNivel",
        subiuDeNivel);
  }

  private UUID inserirEvento(String tipo, UUID agregadoId, Map<String, Object> payload) {
    UUID id = UUID.randomUUID();
    String json =
        tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(payload);
    jdbcTemplate.update(
        """
        INSERT INTO outbox (id, tipo_evento, agregado_id, payload, criado_em, tentativas,
                            proxima_tentativa_em)
        VALUES (?, ?, ?, ?::jsonb, NOW(), 0, NOW())
        """,
        id,
        tipo,
        agregadoId,
        json);
    return id;
  }

  private UUID criarUsuario() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO usuario (id, nome, email, senha_hash, handle, xp, nivel, streak, rating,
                             papel, status, criado_em, atualizado_em, versao)
        VALUES (?, 'Destinatario', ?, '{bcrypt}$2a$10$naoUsadoNesteTeste', ?, 0, 1, 0, 0.0,
                'USUARIO', 'ATIVO', NOW(), NOW(), 0)
        """,
        id,
        "outbox-" + id + "@teste.dev",
        "o" + id.toString().substring(0, 10));
    return id;
  }

  private Instant publicadoEm() {
    java.sql.Timestamp t =
        jdbcTemplate.queryForObject(
            "SELECT publicado_em FROM outbox WHERE id = ?", java.sql.Timestamp.class, eventoId);
    return t == null ? null : t.toInstant();
  }

  private Instant proximaTentativaEm() {
    return jdbcTemplate
        .queryForObject(
            "SELECT proxima_tentativa_em FROM outbox WHERE id = ?",
            java.sql.Timestamp.class,
            eventoId)
        .toInstant();
  }

  private int tentativas() {
    return jdbcTemplate.queryForObject(
        "SELECT tentativas FROM outbox WHERE id = ?", Integer.class, eventoId);
  }

  private String ultimoErro() {
    return jdbcTemplate.queryForObject(
        "SELECT ultimo_erro FROM outbox WHERE id = ?", String.class, eventoId);
  }

  private long contarAlertas() {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM alerta WHERE usuario_id = ?", Long.class, usuarioId);
  }
}
