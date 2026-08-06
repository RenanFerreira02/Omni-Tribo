package com.omnitribo.missoes.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnitribo.TesteIntegracaoBase;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Expiração de janelas vencidas.
 *
 * <p>Chama expirarLote() diretamente em vez de esperar o @Scheduled: o job está desligado no perfil
 * de teste justamente para não mudar status no meio das assertions dos outros testes, e depender do
 * relógio do agendador tornaria este teste lento e intermitente.
 */
class ExpiracaoMissoesServiceTest extends TesteIntegracaoBase {

  @Autowired ExpiracaoMissoesService expiracaoMissoesService;
  @Autowired JdbcTemplate jdbcTemplate;

  @Test
  void abertaComJanelaVencidaViraExpiradaERegistraEventoDoSistema() {
    UUID vencida = inserirMissao("ABERTA", Instant.now().minus(3, ChronoUnit.HOURS));

    try {
      int expiradas = expiracaoMissoesService.expirarLote(200);
      assertThat(expiradas).isGreaterThanOrEqualTo(1);

      String status =
          jdbcTemplate.queryForObject(
              "SELECT status FROM missao WHERE id = ?", String.class, vencida);
      assertThat(status).isEqualTo("EXPIRADA");

      List<Map<String, Object>> eventos =
          jdbcTemplate.queryForList(
              "SELECT tipo, ator_id, de_status, para_status FROM missao_evento WHERE missao_id = ?",
              vencida);

      assertThat(eventos).hasSize(1);
      assertThat(eventos.get(0).get("tipo")).isEqualTo("EXPIRADA");
      assertThat(eventos.get(0).get("de_status")).isEqualTo("ABERTA");
      assertThat(eventos.get(0).get("para_status")).isEqualTo("EXPIRADA");
      // Evento do sistema: sem ator humano, a coluna fica NULL.
      assertThat(eventos.get(0).get("ator_id")).isNull();
    } finally {
      limpar(vencida);
    }
  }

  @Test
  void janelaNoFuturoNaoExpira() {
    UUID futura = inserirMissao("ABERTA", Instant.now().plus(3, ChronoUnit.DAYS));

    try {
      expiracaoMissoesService.expirarLote(200);

      String status =
          jdbcTemplate.queryForObject(
              "SELECT status FROM missao WHERE id = ?", String.class, futura);
      assertThat(status).isEqualTo("ABERTA");
    } finally {
      limpar(futura);
    }
  }

  @Test
  void missaoJaAceitaNaoExpiraMesmoComJanelaVencida() {
    // Quem já assumiu a missão não pode perdê-la para o relógio: só o pool ABERTA expira.
    UUID aceita = inserirMissao("ACEITA", Instant.now().minus(5, ChronoUnit.HOURS));

    try {
      expiracaoMissoesService.expirarLote(200);

      String status =
          jdbcTemplate.queryForObject(
              "SELECT status FROM missao WHERE id = ?", String.class, aceita);
      assertThat(status).isEqualTo("ACEITA");
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT COUNT(*) FROM missao_evento WHERE missao_id = ?", Long.class, aceita))
          .isZero();
    } finally {
      limpar(aceita);
    }
  }

  @Test
  void loteRespeitaOLimite() {
    UUID a = inserirMissao("ABERTA", Instant.now().minus(2, ChronoUnit.HOURS));
    UUID b = inserirMissao("ABERTA", Instant.now().minus(1, ChronoUnit.HOURS));

    try {
      assertThat(expiracaoMissoesService.expirarLote(1)).isEqualTo(1);
    } finally {
      expiracaoMissoesService.expirarLote(200);
      limpar(a);
      limpar(b);
    }
  }

  private UUID inserirMissao(String status, Instant janelaFim) {
    UUID id = UUID.randomUUID();
    UUID criadorId = jdbcTemplate.queryForObject("SELECT id FROM usuario LIMIT 1", UUID.class);

    jdbcTemplate.update(
        """
        INSERT INTO missao (id, criador_id, categoria, titulo, descricao, status,
            xp_recompensa, valor_brl, tokens_recompensa, origem,
            cep, logradouro, bairro, cidade, uf, raio_checkin_m,
            janela_inicio, janela_fim, criada_em, versao)
        VALUES (?, ?, 'AJUDA', 'Missao com janela para expirar', 'Descricao', ?,
            10, 0.00, 5,
            ST_SetSRID(ST_MakePoint(-46.6996, -23.5629), 4326)::geography,
            '05422030', 'Rua Teste', 'Pinheiros', 'Sao Paulo', 'SP', 50,
            ?, ?, ?, 0)
        """,
        id,
        criadorId,
        status,
        Timestamp.from(janelaFim.minus(1, ChronoUnit.DAYS)),
        Timestamp.from(janelaFim),
        Timestamp.from(Instant.now()));

    return id;
  }

  private void limpar(UUID missaoId) {
    jdbcTemplate.update("DELETE FROM missao_evento WHERE missao_id = ?", missaoId);
    jdbcTemplate.update("DELETE FROM missao WHERE id = ?", missaoId);
  }
}
