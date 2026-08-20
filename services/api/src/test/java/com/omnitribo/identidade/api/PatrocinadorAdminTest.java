package com.omnitribo.identidade.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import com.omnitribo.carteira.SuporteCarteira;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Cadastro e aporte de patrocinador — o único ponto de EMISSÃO de token do sistema.
 *
 * <p>O foco destes testes é o que torna a emissão defensável: só ADMIN chega aqui, e um retry de
 * rede não pode cunhar duas vezes. Um aporte duplicado NÃO é detectável depois — ledger e projeção
 * ficariam ambos errados na mesma direção e a reconciliação continuaria respondendo {@code
 * integro=true}.
 */
@Import(JwtTestConfig.class)
@DisplayName("Patrocinador (ADMIN)")
class PatrocinadorAdminTest extends TesteIntegracaoMvcBase {

  private static final String URL = "/api/v1/admin/patrocinadores";

  /** Seed V900. */
  private static final UUID ADMIN = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

  private static final UUID ALICE = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  @AfterEach
  void limpar() {
    // Só o que ESTA suíte cria. Os patrocinadores da V905 ficam — outras suítes dependem deles.
    jdbcTemplate.update(
        "DELETE FROM lancamento WHERE carteira_id IN"
            + " (SELECT c.id FROM carteira c JOIN patrocinador p ON p.usuario_id = c.usuario_id"
            + "   WHERE p.transportadora_slug LIKE 'suite-%')");
    jdbcTemplate.update(
        "DELETE FROM carteira WHERE usuario_id IN"
            + " (SELECT usuario_id FROM patrocinador WHERE transportadora_slug LIKE 'suite-%')");
    jdbcTemplate.update("DELETE FROM auditoria WHERE entidade = 'patrocinador'");

    // A ORDEM importa: patrocinador.usuario_id é FK para usuario, então apagar o titular antes da
    // relação viola patrocinador_usuario_id_fkey. Guardar os ids ANTES de apagar a relação é o que
    // permite apagar o titular depois — a subconsulta deixaria de encontrá-los.
    List<UUID> titulares =
        jdbcTemplate.queryForList(
            "SELECT usuario_id FROM patrocinador WHERE transportadora_slug LIKE 'suite-%'",
            UUID.class);
    jdbcTemplate.update("DELETE FROM patrocinador WHERE transportadora_slug LIKE 'suite-%'");
    for (UUID titular : titulares) {
      jdbcTemplate.update("DELETE FROM usuario WHERE id = ?", titular);
    }
  }

  @Test
  @DisplayName("cadastro cria titular INATIVO e carteira zerada")
  void cadastroCriaTitularECarteira() throws Exception {
    String corpo = cadastrar("suite-alfa", "Transportadora Alfa");

    UUID usuarioId = UUID.fromString(campo(corpo, "usuarioId"));

    var titular = jdbcTemplate.queryForMap("SELECT * FROM usuario WHERE id = ?", usuarioId);
    assertThat(titular.get("papel")).isEqualTo("PATROCINADOR");
    assertThat(titular.get("status"))
        .as("a conta NUNCA autentica: AutenticacaoService recusa status != ATIVO")
        .isEqualTo("INATIVO");
    assertThat(titular.get("tribo_id"))
        .as("patrocinador não pertence a bairro — é o que o mantém fora da regra de afiliação")
        .isNull();

    Long saldo =
        jdbcTemplate.queryForObject(
            "SELECT saldo_tokens FROM carteira WHERE usuario_id = ?", Long.class, usuarioId);
    assertThat(saldo).as("carteira nasce junto com o titular, e nasce vazia").isZero();

    SuporteCarteira.assertLedgerReconcilia(jdbcTemplate);
  }

  @Test
  @DisplayName("slug repetido é 422, não 500 de constraint")
  void slugRepetidoEh422() throws Exception {
    cadastrar("suite-beta", "Transportadora Beta");

    mockMvc
        .perform(
            post(URL)
                .header("Authorization", bearer(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"Outra\",\"transportadoraSlug\":\"suite-beta\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type").value("https://omnitribo.dev/problemas/regra-negocio-violada"));
  }

  @Test
  @DisplayName("slug com maiúscula ou espaço é 400 na borda")
  void slugMalFormadoEh400() throws Exception {
    // O filtro de webhook normaliza para minúsculas antes de publicar o atributo verificado. Um
    // slug gravado com outra caixa nunca seria encontrado, e TODA entrega daquela transportadora
    // cairia em SEM_PATROCINIO — sintoma indistinguível de saldo zerado.
    mockMvc
        .perform(
            post(URL)
                .header("Authorization", bearer(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"X\",\"transportadoraSlug\":\"Suite-Gama\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("usuário comum não cadastra nem aporta")
  void usuarioComumEh403() throws Exception {
    mockMvc
        .perform(
            post(URL)
                .header("Authorization", bearer(ALICE))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"X\",\"transportadoraSlug\":\"suite-delta\"}"))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(get(URL).header("Authorization", bearer(ALICE)))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("aporte credita e o ledger continua batendo")
  void aporteCredita() throws Exception {
    UUID patrocinadorId = UUID.fromString(campo(cadastrar("suite-eps", "Eps"), "id"));

    mockMvc
        .perform(
            post(URL + "/" + patrocinadorId + "/aportes")
                .header("Authorization", bearer(ADMIN))
                .header("Idempotency-Key", "aporte-eps-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tokens\":250}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.saldoTokens").value(250))
        .andExpect(jsonPath("$.replay").value(false));

    SuporteCarteira.assertLedgerReconcilia(jdbcTemplate);
  }

  @Test
  @DisplayName("mesma Idempotency-Key não emite duas vezes")
  void aporteEhIdempotente() throws Exception {
    UUID patrocinadorId = UUID.fromString(campo(cadastrar("suite-zeta", "Zeta"), "id"));

    for (int i = 0; i < 2; i++) {
      mockMvc
          .perform(
              post(URL + "/" + patrocinadorId + "/aportes")
                  .header("Authorization", bearer(ADMIN))
                  .header("Idempotency-Key", "aporte-zeta-0001")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"tokens\":100}"))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.saldoTokens").value(100))
          .andExpect(jsonPath("$.replay").value(i == 1));
    }

    SuporteCarteira.assertLedgerReconcilia(jdbcTemplate);
  }

  /**
   * Operação de valor exige teste multi-thread — e num endpoint que CUNHA, mais ainda.
   *
   * <p>Dez requisições simultâneas com a MESMA chave. Exatamente uma emite; as outras nove leem a
   * linha do vencedor sob o lock e devolvem replay. Se a sondagem não estivesse sob {@code FOR
   * UPDATE}, várias passariam pela verificação antes de qualquer INSERT e o saldo final seria um
   * múltiplo de 100.
   */
  @Test
  @DisplayName("dez aportes simultâneos com a mesma chave emitem uma vez só")
  void aporteConcorrenteEmiteUmaVez() throws Exception {
    UUID patrocinadorId = UUID.fromString(campo(cadastrar("suite-eta", "Eta"), "id"));

    int threads = 10;
    CountDownLatch largada = new CountDownLatch(1);
    CountDownLatch fim = new CountDownLatch(threads);
    AtomicInteger criados = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(threads);

    for (int i = 0; i < threads; i++) {
      pool.submit(
          () -> {
            try {
              largada.await();
              var resposta =
                  mockMvc
                      .perform(
                          post(URL + "/" + patrocinadorId + "/aportes")
                              .header("Authorization", bearer(ADMIN))
                              .header("Idempotency-Key", "aporte-eta-concorrente")
                              .contentType(MediaType.APPLICATION_JSON)
                              .content("{\"tokens\":100}"))
                      .andReturn()
                      .getResponse();
              if (resposta.getStatus() == 201
                  && resposta.getContentAsString().contains("\"replay\":false")) {
                criados.incrementAndGet();
              }
            } catch (Exception e) {
              // Engolir aqui é intencional: a asserção que importa é o saldo final, e uma exceção
              // de thread só reduziria o número de tentativas concorrentes.
            } finally {
              fim.countDown();
            }
          });
    }

    largada.countDown();
    assertThat(fim.await(30, TimeUnit.SECONDS)).isTrue();
    pool.shutdownNow();

    UUID usuarioId =
        jdbcTemplate.queryForObject(
            "SELECT usuario_id FROM patrocinador WHERE id = ?", UUID.class, patrocinadorId);
    Long saldo =
        jdbcTemplate.queryForObject(
            "SELECT saldo_tokens FROM carteira WHERE usuario_id = ?", Long.class, usuarioId);

    assertThat(saldo).as("emissão duplicada seria invisível para a reconciliação").isEqualTo(100L);
    assertThat(criados.get()).as("exatamente uma requisição emitiu").isEqualTo(1);

    SuporteCarteira.assertLedgerReconcilia(jdbcTemplate);
  }

  @Test
  @DisplayName("aporte em patrocinador inativo é 422")
  void aporteEmInativoEh422() throws Exception {
    UUID patrocinadorId = UUID.fromString(campo(cadastrar("suite-teta", "Teta"), "id"));
    jdbcTemplate.update("UPDATE patrocinador SET ativo = FALSE WHERE id = ?", patrocinadorId);

    mockMvc
        .perform(
            post(URL + "/" + patrocinadorId + "/aportes")
                .header("Authorization", bearer(ADMIN))
                .header("Idempotency-Key", "aporte-teta-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tokens\":100}"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  @DisplayName("aporte acima do teto por operação é 400")
  void aporteAcimaDoTetoEh400() throws Exception {
    UUID patrocinadorId = UUID.fromString(campo(cadastrar("suite-iota", "Iota"), "id"));

    // Um zero a mais digitado por engano infla a oferta de forma que nenhum estorno desfaz —
    // lancamento é append-only. O teto transforma o erro de digitação em 400.
    mockMvc
        .perform(
            post(URL + "/" + patrocinadorId + "/aportes")
                .header("Authorization", bearer(ADMIN))
                .header("Idempotency-Key", "aporte-iota-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tokens\":1000001}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("aporte sem Idempotency-Key é 400")
  void aporteSemChaveEh400() throws Exception {
    UUID patrocinadorId = UUID.fromString(campo(cadastrar("suite-kapa", "Kapa"), "id"));

    mockMvc
        .perform(
            post(URL + "/" + patrocinadorId + "/aportes")
                .header("Authorization", bearer(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tokens\":100}"))
        .andExpect(status().isBadRequest());
  }

  // ─── Helpers ────────────────────────────────────────────────────────────────────────────────

  private String cadastrar(String slug, String nome) throws Exception {
    return mockMvc
        .perform(
            post(URL)
                .header("Authorization", bearer(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"%s\",\"transportadoraSlug\":\"%s\"}".formatted(nome, slug)))
        .andExpect(status().isCreated())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * O papel do token é só o ponto de partida: desde a verificação de 2026-08-11 o {@code
   * JwtAuthFilter} consulta {@code ConsultaSessao} e monta o principal a partir do BANCO, então
   * quem manda é o papel da linha de {@code usuario}. Passar "ADMIN" aqui para a Alice não a
   * promoveria — é isso que o teste de 403 exercita.
   */
  private String bearer(UUID usuarioId) {
    return "Bearer "
        + com.omnitribo.JwtTestConfig.gerarTokenValido(
            usuarioId, usuarioId + "@teste.dev", usuarioId.equals(ADMIN) ? "ADMIN" : "USUARIO");
  }

  private static String campo(String json, String nome) {
    return (String) JSON.readValue(json, java.util.Map.class).get(nome);
  }
}
