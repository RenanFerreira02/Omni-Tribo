package com.omnitribo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Garante as invariantes de schema introduzidas pela V11 (ciclo de vida de missões). Sem estas
 * assertions, uma migration futura poderia reintroduzir os status antigos ou estreitar a coluna sem
 * que nenhum teste percebesse — o validate do Hibernate compara o nome do tipo por prefixo e deixa
 * divergência de tamanho passar em silêncio.
 *
 * <p>A classe não é transacional (como MigracaoTest): cada teste que insere limpa o que inseriu.
 */
class MigracaoCicloVidaTest extends TesteIntegracaoBase {

  @Autowired JdbcTemplate jdbcTemplate;

  @Test
  void nenhuma_linha_permanece_com_status_antigo() {
    Long missoesAntigas =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM missao WHERE status IN ('CRIADA', 'DISPONIVEL')", Long.class);
    Long eventosAntigos =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM missao_evento
            WHERE de_status IN ('CRIADA', 'DISPONIVEL')
               OR para_status IN ('CRIADA', 'DISPONIVEL')
            """,
            Long.class);

    assertThat(missoesAntigas).as("V11 deve ter renomeado todo status antigo em missao").isZero();
    assertThat(eventosAntigos)
        .as("V11 deve ter renomeado todo status antigo na trilha de eventos")
        .isZero();
  }

  @Test
  void seed_migrado_para_aberta() {
    Long abertas =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM missao WHERE status = 'ABERTA'", Long.class);
    assertThat(abertas)
        .as("as 6 missões DISPONIVEL do seed viraram ABERTA")
        .isGreaterThanOrEqualTo(6);
  }

  @Test
  void status_antigo_e_rejeitado_pelo_check_novo() {
    UUID algumaMissao = jdbcTemplate.queryForObject("SELECT id FROM missao LIMIT 1", UUID.class);

    // A constraint rejeita antes de qualquer escrita — nenhuma linha muda, nada a limpar.
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE missao SET status = 'DISPONIVEL' WHERE id = ?", algumaMissao))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void status_novo_de_22_caracteres_cabe_na_coluna() {
    // Prova o ALTER COLUMN TYPE VARCHAR(30): em VARCHAR(15) este INSERT falharia.
    UUID missaoId = inserirMissao("AGUARDANDO_CONFIRMACAO", "ENTREGA", "0.00");
    try {
      String status =
          jdbcTemplate.queryForObject(
              "SELECT status FROM missao WHERE id = ?", String.class, missaoId);
      assertThat(status).isEqualTo("AGUARDANDO_CONFIRMACAO");
    } finally {
      jdbcTemplate.update("DELETE FROM missao WHERE id = ?", missaoId);
    }
  }

  @Test
  void em_disputa_e_aceito_pelo_check_novo() {
    UUID missaoId = inserirMissao("EM_DISPUTA", "AJUDA", "0.00");
    try {
      // Ler de volta, e não só "não lançou": um INSERT que a constraint recusasse já teria
      // estourado
      // acima, mas sem assertion nenhuma este teste passaria mesmo que o valor gravado fosse outro
      // —
      // e a intenção dele (o CHECK da V11 aceita EM_DISPUTA) existiria só no nome do método.
      String status =
          jdbcTemplate.queryForObject(
              "SELECT status FROM missao WHERE id = ?", String.class, missaoId);
      assertThat(status).isEqualTo("EM_DISPUTA");
    } finally {
      jdbcTemplate.update("DELETE FROM missao WHERE id = ?", missaoId);
    }
  }

  @Test
  void invariante_economica_continua_valendo_apos_v11() {
    // ck_missao_economia nasceu na V3 e a V11 não a tocou — mas a V15 a SUBSTITUIU, e a versão que
    // roda hoje exige valor_brl = 0 em TODA categoria, não só em TRIBO e COLETA (ADR 0009). É a
    // barreira de banco da regra econômica, então fica coberta aqui também.
    assertThatThrownBy(() -> inserirMissao("ABERTA", "TRIBO", "10.00"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void tipo_de_evento_novo_e_aceito() {
    UUID missaoId = inserirMissao("ABERTA", "AJUDA", "0.00");
    UUID eventoId = UUID.randomUUID();
    try {
      jdbcTemplate.update(
          """
          INSERT INTO missao_evento (id, missao_id, tipo, ator_id, de_status, para_status, criado_em)
          VALUES (?, ?, 'DISPUTA_RESOLVIDA', NULL, 'EM_DISPUTA', 'AGUARDANDO_CONFIRMACAO', ?)
          """,
          eventoId,
          missaoId,
          Timestamp.from(Instant.now()));

      String paraStatus =
          jdbcTemplate.queryForObject(
              "SELECT para_status FROM missao_evento WHERE id = ?", String.class, eventoId);
      assertThat(paraStatus).isEqualTo("AGUARDANDO_CONFIRMACAO");
    } finally {
      // missao_evento é append-only para omnitribo_app, mas o teste conecta como dono do schema.
      jdbcTemplate.update("DELETE FROM missao_evento WHERE id = ?", eventoId);
      jdbcTemplate.update("DELETE FROM missao WHERE id = ?", missaoId);
    }
  }

  private UUID inserirMissao(String status, String categoria, String valorBrl) {
    UUID id = UUID.randomUUID();
    UUID criadorId = jdbcTemplate.queryForObject("SELECT id FROM usuario LIMIT 1", UUID.class);
    Instant agora = Instant.now();

    jdbcTemplate.update(
        """
        INSERT INTO missao (id, criador_id, categoria, titulo, descricao, status,
            xp_recompensa, valor_brl, tokens_recompensa, origem,
            cep, logradouro, bairro, cidade, uf, raio_checkin_m,
            janela_inicio, janela_fim, criada_em, versao)
        VALUES (?, ?, ?, 'Missao de teste de migration', 'Descricao', ?,
            10, CAST(? AS NUMERIC(12,2)), 5,
            ST_SetSRID(ST_MakePoint(-46.6996, -23.5629), 4326)::geography,
            '05422030', 'Rua Teste', 'Pinheiros', 'Sao Paulo', 'SP', 50,
            ?, ?, ?, 0)
        """,
        id,
        criadorId,
        categoria,
        status,
        valorBrl,
        Timestamp.from(agora),
        Timestamp.from(agora.plus(2, ChronoUnit.DAYS)),
        Timestamp.from(agora));

    return id;
  }
}
