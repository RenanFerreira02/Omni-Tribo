package com.omnitribo.missoes.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import com.omnitribo.identidade.dominio.Auditoria;
import com.omnitribo.identidade.infra.AuditoriaRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Auditoria das escritas de missão via @Auditavel + AuditoriaAspecto (item 9 da fase de segurança).
 *
 * <p>O aspecto existia desde a F4 mas a anotação não era usada em método nenhum — era advice que
 * nunca disparava. Estes testes existem para que isso não volte a acontecer sem o build reclamar.
 */
@Import(JwtTestConfig.class)
class AuditoriaMissaoTest extends TesteIntegracaoMvcBase {

  private static final UUID ALICE_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
  private static final UUID BOB_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000003");
  private static final String BASE = "/api/v1/missoes";
  private static final String UA_TESTE = "OmniTriboTest/1.0";

  @Autowired MockMvc mockMvc;
  @Autowired AuditoriaRepository auditoriaRepository;

  @Test
  void criarEPublicar_gravamTrilhaComAtorIpEEntidadeId() throws Exception {
    String ip =
        "203.0.113." + (int) (Math.abs(UUID.randomUUID().getLeastSignificantBits() % 254) + 1);

    UUID missaoId = criarMissao(ip);

    mockMvc
        .perform(
            post(BASE + "/{id}/publicar", missaoId)
                .header("Authorization", bearer(ALICE_ID))
                .header("X-Forwarded-For", ip)
                .header("User-Agent", UA_TESTE))
        .andExpect(status().isOk());

    // entidadeId é o ponto central: sem ele a trilha diria "alguém publicou uma missão" sem dizer
    // qual, e não serviria para reconstruir um incidente.
    Auditoria criada = buscar("MISSAO_CRIADA", missaoId);
    assertThat(criada.getAtorId()).isEqualTo(ALICE_ID);
    assertThat(criada.getEntidade()).isEqualTo("missao");
    assertThat(criada.getIp()).isEqualTo(ip);
    assertThat(criada.getUserAgent()).isEqualTo(UA_TESTE);
    assertThat(criada.getCorrelationId()).isNotBlank();

    Auditoria publicada = buscar("MISSAO_PUBLICADA", missaoId);
    assertThat(publicada.getAtorId()).isEqualTo(ALICE_ID);
    assertThat(publicada.getIp()).isEqualTo(ip);
  }

  @Test
  void escritaNegada_naoGeraLinhaDeAuditoria() throws Exception {
    UUID missaoId = criarMissao("203.0.113.9");
    mockMvc
        .perform(post(BASE + "/{id}/publicar", missaoId).header("Authorization", bearer(ALICE_ID)))
        .andExpect(status().isOk());

    long antes = contar("MISSAO_CANCELADA", missaoId);

    // Bob não é o criador: 403. Trava a semântica @AfterReturning — só escrita que aconteceu de
    // fato entra na trilha. Se um dia virar @Around ou @AfterThrowing, este teste avisa.
    mockMvc
        .perform(post(BASE + "/{id}/cancelar", missaoId).header("Authorization", bearer(BOB_ID)))
        .andExpect(status().isForbidden());

    assertThat(contar("MISSAO_CANCELADA", missaoId))
        .as("Tentativa negada não pode gerar registro de auditoria")
        .isEqualTo(antes);
  }

  // ─── Helpers ───────────────────────────────────────────────────────────────────────────────

  private Auditoria buscar(String acao, UUID missaoId) {
    return registros(acao, missaoId).stream()
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError("Nenhum registro de auditoria '" + acao + "' para " + missaoId));
  }

  private long contar(String acao, UUID missaoId) {
    return registros(acao, missaoId).size();
  }

  private List<Auditoria> registros(String acao, UUID missaoId) {
    return auditoriaRepository.findAll().stream()
        .filter(a -> acao.equals(a.getAcao()) && missaoId.toString().equals(a.getEntidadeId()))
        .toList();
  }

  private String bearer(UUID usuarioId) {
    return "Bearer "
        + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", "USUARIO");
  }

  private UUID criarMissao(String ip) throws Exception {
    MvcResult resultado =
        mockMvc
            .perform(
                post(BASE)
                    .header("Authorization", bearer(ALICE_ID))
                    .header("X-Forwarded-For", ip)
                    .header("User-Agent", UA_TESTE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(corpoEntregaValido()))
            .andExpect(status().isCreated())
            .andReturn();

    return UUID.fromString(
        JSON.readTree(resultado.getResponse().getContentAsString()).get("id").asText());
  }

  private static String corpoEntregaValido() {
    Instant inicio = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Instant fim = inicio.plus(2, ChronoUnit.DAYS);
    return """
        {
          "categoria": "ENTREGA",
          "titulo": "Entrega solidária no bairro",
          "descricao": "Levar a encomenda até o ponto de custódia da Vila Madalena.",
          "valorBrl": 0.00,
          "pesoKg": 10.00,
          "volumeL": 40.00,
          "origemLat": -23.5629,
          "origemLon": -46.6996,
          "cep": "05422030",
          "logradouro": "Rua dos Pinheiros",
          "bairro": "Pinheiros",
          "cidade": "São Paulo",
          "uf": "SP",
          "raioCheckinM": 50,
          "janelaInicio": "%s",
          "janelaFim": "%s"
        }
        """
        .formatted(inicio, fim);
  }
}
