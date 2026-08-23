package com.omnitribo.compartilhado.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /api/v1/admin/impacto} — o painel que responde "quanto a tese economizou".
 *
 * <p><b>Toda expectativa deste arquivo é calculada por SQL no próprio teste, nunca escrita à
 * mão.</b> Constante literal aqui provaria só que alguém digitou o mesmo número duas vezes, e
 * envelheceria no primeiro seed novo — que é exatamente o que aconteceria, porque o banco de teste
 * carrega sete arquivos de seed e ganha mais a cada fase. O que precisa ser verdade é que o
 * endpoint concorda com o banco; então o teste pergunta ao banco.
 *
 * <p>A consulta de conferência é escrita de forma DIFERENTE da de produção sempre que dá (aqui:
 * {@code COUNT} com {@code WHERE} separado, contra o {@code FILTER} do repositório). Repetir a
 * mesma expressão dos dois lados só provaria que ela é determinística.
 */
@Import(JwtTestConfig.class)
@DisplayName("Painel de impacto")
class ImpactoTest extends TesteIntegracaoMvcBase {

  private static final String URL = "/api/v1/admin/impacto";

  /** Seed V900. */
  private static final UUID ADMIN = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

  private static final UUID ALICE = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

  /** Mesmo UUID da V21 — o criador de toda missão nascida de entrega falida. */
  private static final UUID SISTEMA = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  // ─── Autorização ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("sem token é 401")
  void semTokenEh401() throws Exception {
    mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("usuário comum é 403 e não vê número nenhum")
  void usuarioComumEh403() throws Exception {
    mockMvc
        .perform(get(URL).header("Authorization", bearer(ALICE, "USUARIO")))
        .andExpect(status().isForbidden())
        // Desempenho comercial da operação não é informação de produto — e o corpo do 403 não pode
        // trazer de brinde o que o status recusou.
        .andExpect(jsonPath("$.custoEvitado").doesNotExist())
        .andExpect(jsonPath("$.tokens").doesNotExist());
  }

  // ─── Os números batem com o banco ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("o funil de entregas falidas bate com a contagem por SQL")
  void funilBateComOBanco() throws Exception {
    JsonNode painel = apurar();
    JsonNode ef = painel.get("entregasFalidas");

    long recebidas = contar("SELECT COUNT(*) FROM entrega_falida");
    long convertidas = contar("SELECT COUNT(*) FROM entrega_falida WHERE missao_id IS NOT NULL");
    long lotado =
        contar("SELECT COUNT(*) FROM entrega_falida WHERE motivo_recusa = 'PONTO_LOTADO'");
    long semPatrocinio =
        contar("SELECT COUNT(*) FROM entrega_falida WHERE motivo_recusa = 'SEM_PATROCINIO'");
    long pendentes =
        contar(
            "SELECT COUNT(*) FROM entrega_falida"
                + " WHERE missao_id IS NULL AND motivo_recusa IS NULL");

    assertThat(ef.get("recebidas").asLong()).isEqualTo(recebidas);
    assertThat(ef.get("convertidas").asLong()).isEqualTo(convertidas);
    assertThat(ef.get("pendentes").asLong()).isEqualTo(pendentes);
    assertThat(ef.get("recusadasPontoLotado").asLong()).isEqualTo(lotado);
    assertThat(ef.get("recusadasSemPatrocinio").asLong()).isEqualTo(semPatrocinio);

    // ESTA asserção é a que descobriu o quarto desfecho. Na primeira versão ela somava só os três
    // do webhook e reprovou com 6 contra 22 recebidas: 16 linhas do seed V901 estão na custódia
    // sem missão e sem recusa. Contá-las virou campo do painel em vez de resto invisível — se um
    // quinto estado aparecer, é aqui que ele avisa.
    assertThat(convertidas + pendentes + lotado + semPatrocinio)
        .as("os quatro desfechos precisam somar as entregas recebidas")
        .isEqualTo(recebidas);
  }

  @Test
  @DisplayName("missões do sistema e potes batem com a contagem por SQL")
  void missoesDoSistemaBatemComOBanco() throws Exception {
    JsonNode mr = apurar().get("missoesDeRetirada");

    long criadas =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM missao WHERE criador_id = ?", Long.class, SISTEMA);
    long concluidas =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM missao WHERE criador_id = ? AND status = 'CONCLUIDA'",
            Long.class,
            SISTEMA);

    assertThat(mr.get("criadas").asLong()).isEqualTo(criadas);
    assertThat(mr.get("concluidas").asLong()).isEqualTo(concluidas);
  }

  @Test
  @DisplayName("a mediana concorda com o percentile_cont do PostgreSQL")
  void medianaConcordaComOPostgres() throws Exception {
    // DUAS IMPLEMENTAÇÕES INDEPENDENTES do mesmo número: o serviço compõe duas portas e ordena em
    // Java (para não cruzar a fronteira dos módulos dentro do SQL — ADR 0029 §3); aqui o banco faz
    // o join e o percentile_cont. Concordarem vale mais que uma conferida contra si mesma.
    JsonNode mr = apurar().get("missoesDeRetirada");

    Double esperada =
        jdbcTemplate.queryForObject(
            """
            SELECT percentile_cont(0.5) WITHIN GROUP (
                     ORDER BY EXTRACT(EPOCH FROM (c.primeiro - ef.recebido_em)))
            FROM entrega_falida ef
            JOIN (SELECT missao_id, MIN(criado_em) AS primeiro
                    FROM checkin WHERE valido = TRUE GROUP BY missao_id) c
              ON c.missao_id = ef.missao_id
            WHERE ef.missao_id IS NOT NULL AND c.primeiro >= ef.recebido_em
            """,
            Double.class);

    if (esperada == null) {
      assertThat(mr.get("medianaAteCheckinSegundos").isNull()).isTrue();
      assertThat(mr.get("amostraMediana").asInt()).isZero();
      return;
    }

    long amostra =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM entrega_falida ef
            JOIN (SELECT missao_id, MIN(criado_em) AS primeiro
                    FROM checkin WHERE valido = TRUE GROUP BY missao_id) c
              ON c.missao_id = ef.missao_id
            WHERE ef.missao_id IS NOT NULL AND c.primeiro >= ef.recebido_em
            """,
            Long.class);

    assertThat(mr.get("amostraMediana").asLong()).isEqualTo(amostra);
    // Tolerância de 1 s: percentile_cont INTERPOLA em amostra par, o Java soma os dois centrais e
    // divide inteiro. A diferença máxima é meio segundo, e arredondar dos dois lados esconderia o
    // caso em que a implementação estivesse realmente errada.
    assertThat(mr.get("medianaAteCheckinSegundos").asLong())
        .isCloseTo(Math.round(esperada), org.assertj.core.data.Offset.offset(1L));
  }

  @Test
  @DisplayName("tokens batem com o ledger, e circulação é carteiras + potes")
  void tokensBatemComOLedger() throws Exception {
    JsonNode t = apurar().get("tokens");

    long aportados =
        contar(
            "SELECT COALESCE(SUM(valor_tokens), 0) FROM lancamento"
                + " WHERE motivo = 'APORTE_PATROCINADOR' AND sinal = 'CREDITO'");
    long resgatados =
        contar(
            "SELECT COALESCE(SUM(valor_tokens), 0) FROM lancamento"
                + " WHERE motivo = 'RESGATE' AND sinal = 'DEBITO'");
    long emCarteiras = contar("SELECT COALESCE(SUM(saldo_tokens), 0) FROM carteira");
    long emPotes = contar("SELECT COALESCE(SUM(pote_tokens), 0) FROM missao");

    assertThat(t.get("aportados").asLong()).isEqualTo(aportados);
    assertThat(t.get("resgatados").asLong()).isEqualTo(resgatados);
    assertThat(t.get("emCarteiras").asLong()).isEqualTo(emCarteiras);
    assertThat(t.get("emPotes").asLong()).isEqualTo(emPotes);
    assertThat(t.get("emCirculacao").asLong())
        .as("token em pote saiu de uma carteira e não chegou na outra — continua existindo")
        .isEqualTo(emCarteiras + emPotes);
  }

  @Test
  @DisplayName("o custo evitado é a premissa vezes as concluídas, com a faixa de ±50%")
  void custoEvitadoESuaSensibilidade() throws Exception {
    JsonNode painel = apurar();
    JsonNode custo = painel.get("custoEvitado");

    long concluidas = painel.get("missoesDeRetirada").get("concluidas").asLong();
    assertThat(custo.get("reentregasEvitadas").asLong())
        .as("re-entrega evitada é a missão concluída renomeada, não uma segunda medição")
        .isEqualTo(concluidas);

    BigDecimal premissa = new BigDecimal(custo.get("premissaCustoReentregaBrl").asString());
    BigDecimal base = new BigDecimal(custo.get("baseBrl").asString());
    BigDecimal menos = new BigDecimal(custo.get("menos50Brl").asString());
    BigDecimal mais = new BigDecimal(custo.get("mais50Brl").asString());

    assertThat(base).isEqualByComparingTo(premissa.multiply(BigDecimal.valueOf(concluidas)));
    assertThat(menos).isEqualByComparingTo(base.multiply(new BigDecimal("0.5")));
    assertThat(mais).isEqualByComparingTo(base.multiply(new BigDecimal("1.5")));
  }

  @Test
  @DisplayName("a premissa vem da configuração — trocar o YAML muda o painel")
  void premissaVemDaConfiguracao() throws Exception {
    // Trava o requisito de que o valor NÃO está fixo em código. Se alguém o mover para um literal
    // no cálculo, este teste continua verde só enquanto o literal coincidir com o YAML — então ele
    // confere contra a propriedade resolvida pelo Spring, não contra um número escrito aqui.
    BigDecimal doYaml =
        new BigDecimal(ambiente.getRequiredProperty("app.impacto.custo-reentrega-brl"));

    BigDecimal doPainel =
        new BigDecimal(apurar().get("custoEvitado").get("premissaCustoReentregaBrl").asString());

    assertThat(doPainel).isEqualByComparingTo(doYaml);
  }

  @Autowired org.springframework.core.env.Environment ambiente;

  // ─── Auxiliares ─────────────────────────────────────────────────────────────────────────────

  private JsonNode apurar() throws Exception {
    String corpo =
        mockMvc
            .perform(get(URL).header("Authorization", bearer(ADMIN, "ADMIN")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JSON.readTree(corpo);
  }

  private long contar(String sql) {
    return jdbcTemplate.queryForObject(sql, Long.class);
  }

  private String bearer(UUID usuarioId, String papel) {
    return "Bearer " + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", papel);
  }
}
