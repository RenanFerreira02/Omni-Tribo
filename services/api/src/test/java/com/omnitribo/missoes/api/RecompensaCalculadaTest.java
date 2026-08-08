package com.omnitribo.missoes.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * A recompensa é do SERVIDOR: calculada, congelada e imune ao que o cliente enviar.
 *
 * <p>Guarda a correção do defeito de maior impacto das auditorias F0–F7. Antes do ADR 0009 o corpo
 * de criação trazia {@code xpRecompensa} e {@code tokensRecompensa}, e o único controle era um
 * {@code @Max} — medido contra a API: uma missão AJUDA sem peso, sem volume e sem destino nasceu
 * com <b>5.000 XP e 1.000 tokens</b>, o teto. Com ENTREGA e AJUDA ainda cunhando (lacuna do §4.4),
 * isso era emissão sem contrapartida: 656 → 2.656 tokens em dois ciclos.
 *
 * <p>O primeiro teste aqui é a prova do defeito INVERTIDA: manda exatamente o mesmo payload da
 * auditoria e verifica que o valor persistido é o calculado, não o pedido.
 */
@Import(JwtTestConfig.class)
class RecompensaCalculadaTest extends TesteIntegracaoMvcBase {

  private static final String BASE = "/api/v1/missoes";
  private static final UUID ALICE_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  private final List<UUID> criadas = new ArrayList<>();

  @AfterEach
  void limpar() {
    criadas.forEach(
        id -> {
          jdbcTemplate.update("DELETE FROM missao_evento WHERE missao_id = ?", id);
          jdbcTemplate.update("DELETE FROM missao WHERE id = ?", id);
        });
    criadas.clear();
  }

  @Test
  void recompensaEnviadaPeloClienteEhIgnorada() throws Exception {
    // O payload EXATO que a auditoria F5 usou como prova do defeito.
    MvcResult resultado =
        mockMvc
            .perform(
                post(BASE)
                    .header("Authorization", bearer(ALICE_ID))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        corpo(
                            """
                            "complexidade": "LEVE",
                            "tokensRecompensa": 1000,
                            "xpRecompensa": 5000,""")))
            .andExpect(status().isCreated())
            .andReturn();

    JsonNode criada = JSON.readTree(resultado.getResponse().getContentAsString());
    criadas.add(UUID.fromString(criada.get("id").asText()));

    // Campo desconhecido é descartado em silêncio (fail-on-unknown-properties: false), do mesmo
    // jeito que status e executorId já eram — não é 400, e é isso que mantém o app antigo
    // funcionando enquanto migra.
    assertThat(criada.get("tokensRecompensa").asLong())
        .as("missão trivial não pode valer o teto só porque o cliente pediu")
        .isLessThan(1000L);
    assertThat(criada.get("xpRecompensa").asInt()).isLessThan(5000);

    // AJUDA LEVE, sem peso, sem volume, sem destino: base 20 × 1.0, nenhum adicional.
    assertThat(criada.get("tokensRecompensa").asLong()).isEqualTo(20L);
    assertThat(criada.get("xpRecompensa").asInt()).isEqualTo(60);

    // E o que foi PERSISTIDO é o mesmo — não só o que a resposta mostra.
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT tokens_recompensa FROM missao WHERE id = ?",
                Long.class,
                UUID.fromString(criada.get("id").asText())))
        .isEqualTo(20L);
  }

  @Test
  void criacaoCongelaComplexidadeEVersaoDaFormula() throws Exception {
    UUID id = criar("\"complexidade\": \"PESADA\",");

    var linha =
        jdbcTemplate.queryForMap(
            "SELECT complexidade, versao_formula, multiplicador_risco FROM missao WHERE id = ?",
            id);

    assertThat(linha.get("complexidade")).isEqualTo("PESADA");
    // Sem a versão, mudar um parâmetro amanhã reinterpretaria esta missão retroativamente.
    assertThat(linha.get("versao_formula")).isNotNull();
    // Reservado para a F11: entrou na V16 para o congelamento nascer completo, e ninguém escreve
    // nele hoje. Se deixar de ser nulo sem que a F11 tenha chegado, alguém o preencheu por engano.
    assertThat(linha.get("multiplicador_risco")).isNull();
  }

  @Test
  void previaBateExatamenteComACriacao() throws Exception {
    String insumos = "\"pesoKg\": 18.00,\n          \"volumeL\": 55.00,";

    MvcResult previa =
        mockMvc
            .perform(
                post(BASE + "/previa-recompensa")
                    .header("Authorization", bearer(ALICE_ID))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(corpo(insumos)))
            .andExpect(status().isOk())
            .andReturn();

    JsonNode p = JSON.readTree(previa.getResponse().getContentAsString());
    UUID id = criar(insumos);
    var m =
        jdbcTemplate.queryForMap(
            "SELECT xp_recompensa, tokens_recompensa, complexidade FROM missao WHERE id = ?", id);

    // Divergência aqui significaria fórmula duplicada — exatamente o que a prévia existe para
    // evitar. O app mostra um número antes de publicar; se o servidor pagasse outro, a confiança
    // no valor iria junto.
    assertThat(p.get("tokensRecompensa").asLong())
        .isEqualTo(((Number) m.get("tokens_recompensa")).longValue());
    assertThat(p.get("xpRecompensa").asInt())
        .isEqualTo(((Number) m.get("xp_recompensa")).intValue());
    assertThat(p.get("complexidade").asText()).isEqualTo(m.get("complexidade"));
  }

  @Test
  void previaNaoCriaMissao() throws Exception {
    long antes = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM missao", Long.class);

    mockMvc
        .perform(
            post(BASE + "/previa-recompensa")
                .header("Authorization", bearer(ALICE_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo("\"complexidade\": \"MEDIA\",")))
        .andExpect(status().isOk());

    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM missao", Long.class))
        .as("prévia é consulta, não escrita")
        .isEqualTo(antes);
  }

  @Test
  void entregaSemPesoEVolumeDa400ApontandoOsCampos() throws Exception {
    // ENTREGA move objeto físico: sem peso e volume não há como dimensionar o esforço, e a
    // alternativa seria o criador declarar a complexidade — o arbítrio que esta mudança fecha.
    mockMvc
        .perform(
            post(BASE)
                .header("Authorization", bearer(ALICE_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoCategoria("ENTREGA", "")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[?(@.campo == 'pesoKg')]").exists())
        .andExpect(jsonPath("$.errors[?(@.campo == 'volumeL')]").exists());
  }

  @Test
  void semPesoEVolumeAComplexidadeEhObrigatoria() throws Exception {
    mockMvc
        .perform(
            post(BASE)
                .header("Authorization", bearer(ALICE_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoCategoria("TRIBO", "")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[?(@.campo == 'complexidade')]").exists());
  }

  @Test
  void complexidadeJuntoComPesoEVolumeEhRecusada() throws Exception {
    // Recusar, e não ignorar: ignorar em silêncio faria o app acreditar que declarou algo que não
    // teve efeito, e o usuário veria uma recompensa que não bate com o que escolheu na tela.
    mockMvc
        .perform(
            post(BASE)
                .header("Authorization", bearer(ALICE_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    corpoCategoria(
                        "ENTREGA",
                        """
                        "pesoKg": 10.00,
                          "volumeL": 40.00,
                          "complexidade": "LEVE",""")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[?(@.campo == 'complexidade')]").exists());
  }

  @Test
  void pesoEVolumeMaioresProduzemRecompensaMaior() throws Exception {
    // Monotonicidade ponta a ponta: a propriedade já é testada na unidade, mas aqui prova que os
    // insumos chegam à calculadora pelo caminho HTTP real, e não se perdem no meio.
    UUID leve = criar("\"pesoKg\": 2.00,\n          \"volumeL\": 5.00,");
    UUID pesada = criar("\"pesoKg\": 60.00,\n          \"volumeL\": 300.00,");

    long tokensLeve = tokensDe(leve);
    long tokensPesada = tokensDe(pesada);

    assertThat(tokensPesada).isGreaterThan(tokensLeve);
  }

  // ─── Helpers ────────────────────────────────────────────────────────────────────────────────

  private long tokensDe(UUID id) {
    return jdbcTemplate.queryForObject(
        "SELECT tokens_recompensa FROM missao WHERE id = ?", Long.class, id);
  }

  private UUID criar(String insumos) throws Exception {
    MvcResult r =
        mockMvc
            .perform(
                post(BASE)
                    .header("Authorization", bearer(ALICE_ID))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(corpo(insumos)))
            .andExpect(status().isCreated())
            .andReturn();
    UUID id =
        UUID.fromString(JSON.readTree(r.getResponse().getContentAsString()).get("id").asText());
    criadas.add(id);
    return id;
  }

  private static String corpo(String insumos) {
    return corpoCategoria("AJUDA", insumos);
  }

  private static String corpoCategoria(String categoria, String insumos) {
    Instant inicio = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    return """
        {
          "categoria": "%s",
          "titulo": "Missao trivial: apertar um parafuso",
          "descricao": "Trabalho de 30 segundos. Payload da auditoria F5.",
          "valorBrl": 0.00,
          %s
          "origemLat": -23.5640,
          "origemLon": -46.6934,
          "cep": "05416000",
          "logradouro": "Rua Teodoro Sampaio",
          "bairro": "Pinheiros",
          "cidade": "São Paulo",
          "uf": "SP",
          "raioCheckinM": 50,
          "janelaInicio": "%s",
          "janelaFim": "%s"
        }
        """
        .formatted(categoria, insumos, inicio, inicio.plus(2, ChronoUnit.DAYS));
  }

  private static String bearer(UUID usuarioId) {
    return "Bearer "
        + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", "USUARIO");
  }
}
