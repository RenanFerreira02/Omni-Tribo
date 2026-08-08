package com.omnitribo.carteira.api;

import static com.omnitribo.carteira.SuporteCarteira.assertLedgerReconcilia;
import static com.omnitribo.carteira.SuporteCarteira.contarLancamentosDaMissao;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import com.omnitribo.compartilhado.api.PublicadorEventos;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Prova de ATOMICIDADE: uma exceção depois do INSERT no ledger e antes do commit não deixa resíduo
 * em tabela nenhuma.
 *
 * <p>Mecanismo: {@code PublicadorEventos} é substituído por {@code @MockitoBean} e configurado para
 * lançar. Ele é a ÚLTIMA instrução dentro de {@code MissaoService.confirmar}, então a exceção sobe
 * pelo proxy do {@code @Transactional} e o {@code TransactionInterceptor} faz rollback da transação
 * JDBC ainda aberta — com o lançamento, os saldos, o XP, o status da missão e a trilha dentro dela.
 *
 * <p>Quatro condições fazem este teste valer alguma coisa, e todas precisam continuar verdadeiras:
 *
 * <ol>
 *   <li>{@code MissaoService} injeta a INTERFACE, então o mock entra no lugar do bean real.
 *   <li>A publicação é a última instrução do método transacional, e não depois dele.
 *   <li>A classe de teste NÃO é {@code @Transactional}. Se fosse, {@code confirmar} entraria na
 *       transação do teste, o "rollback" seria só uma marca de rollback-only, e as asserções leriam
 *       estado não commitado pela mesma conexão — passariam pelo motivo errado.
 *   <li>{@code LivroRazaoService} usa {@code saveAndFlush}, não {@code save}. Com {@code save} o
 *       Hibernate adiaria o INSERT para o flush do commit, o commit nunca aconteceria, e o teste
 *       afirmaria a ausência de uma linha que jamais foi tentada — provando nada. É o detalhe mais
 *       importante deste arquivo.
 * </ol>
 */
@Import(JwtTestConfig.class)
class ConclusaoRollbackTest extends TesteIntegracaoMvcBase {

  private static final String BASE = "/api/v1/missoes";
  private static final BigDecimal VALOR_BRL = new BigDecimal("0.00");

  // Substitui o bean real no contexto. Custo assumido: muda a chave de cache do contexto Spring,
  // então esta classe roda num ApplicationContext próprio — por isso é uma classe dedicada, e não
  // testes acrescentados a uma existente.
  @MockitoBean PublicadorEventos publicadorEventos;

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  private UUID criador;
  private UUID executor;
  private UUID missaoId;

  @BeforeEach
  void montarCenario() throws Exception {
    criador = criarUsuarioComCarteira("criador-rb");
    executor = criarUsuarioComCarteira("executor-rb");
    missaoId = montarMissaoAguardandoConfirmacao();
  }

  @Test
  void falhaAntesDoCommitNaoDeixaResiduoEmTabelaNenhuma() throws Exception {
    BigDecimal brlAntes = saldoBrl(executor);
    long tokensAntes = saldoTokens(executor);
    long xpAntes = xpDe(executor);
    int nivelAntes = nivelDe(executor);

    doThrow(new IllegalStateException("falha simulada no despacho do evento"))
        .when(publicadorEventos)
        .publicar(eq("MissaoConcluida"), any(), any());

    mockMvc
        .perform(post(BASE + "/{id}/confirmar", missaoId).header("Authorization", bearer(criador)))
        .andExpect(status().isInternalServerError());

    // ─── Nada pode ter sobrado ────────────────────────────────────────────────────────────────

    assertThat(contarLancamentosDaMissao(jdbcTemplate, missaoId))
        .as("o INSERT no ledger foi emitido de verdade (saveAndFlush) e desfeito pelo rollback")
        .isZero();

    // NÃO se assere aqui a ausência de linha na outbox. O PublicadorEventos é o ÚNICO escritor dela
    // e está substituído pelo mock que lança: nenhuma linha poderia existir, com ou sem rollback, e
    // a asserção estaria verificando o próprio mock. O que a outbox precisa provar está no segundo
    // teste desta classe, onde o publicador funciona e a linha aparece exatamente uma vez.

    Long trilha =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM missao_evento WHERE missao_id = ? AND tipo = 'CONFIRMADA'",
            Long.class,
            missaoId);
    assertThat(trilha).as("trilha append-only não registra transição que deu rollback").isZero();

    assertThat(statusDaMissao())
        .as("missão permanece no estado anterior")
        .isEqualTo("AGUARDANDO_CONFIRMACAO");

    assertThat(saldoBrl(executor)).as("saldo BRL intacto").isEqualByComparingTo(brlAntes);
    assertThat(saldoTokens(executor)).as("saldo de tokens intacto").isEqualTo(tokensAntes);
    assertThat(xpDe(executor)).as("XP intacto").isEqualTo(xpAntes);
    assertThat(nivelDe(executor)).as("nível intacto").isEqualTo(nivelAntes);

    // O AuditoriaAspecto é @AfterReturning e fica POR FORA do interceptor transacional. Um método
    // que lança não retorna, então não há linha de auditoria — o que confirma, de quebra, a ordem
    // declarada no javadoc daquele aspecto.
    //
    // Filtra pela AÇÃO, não só pela entidade: o arranjo (criar → publicar → aceitar → iniciar) são
    // quatro escritas auditadas que de fato persistiram, e devem continuar lá. O que não pode
    // existir é auditoria da confirmação que deu rollback.
    Long auditoriaDaConfirmacao =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM auditoria WHERE entidade_id = ? AND acao = 'MISSAO_CONFIRMADA'",
            Long.class,
            missaoId.toString());
    assertThat(auditoriaDaConfirmacao)
        .as("nada auditado para uma escrita que não persistiu")
        .isZero();

    assertLedgerReconcilia(jdbcTemplate);
  }

  /**
   * A tentativa falha não pode envenenar a chave de idempotência.
   *
   * <p>Um teste de rollback que só prova ausência é meio teste: se o rollback tivesse deixado a
   * chave "consumida" de alguma forma, a operação seria irrecuperável e o usuário nunca receberia a
   * recompensa. Mockito é resetado entre métodos, então aqui o publicador volta a funcionar.
   */
  @Test
  void depoisDaFalhaAOperacaoContinuaRetentavel() throws Exception {
    doThrow(new IllegalStateException("falha simulada"))
        .when(publicadorEventos)
        .publicar(eq("MissaoConcluida"), any(), any());

    mockMvc
        .perform(post(BASE + "/{id}/confirmar", missaoId).header("Authorization", bearer(criador)))
        .andExpect(status().isInternalServerError());

    // Publicador volta a funcionar e o cliente repete a mesma requisição.
    doNothing().when(publicadorEventos).publicar(eq("MissaoConcluida"), any(), any());

    mockMvc
        .perform(post(BASE + "/{id}/confirmar", missaoId).header("Authorization", bearer(criador)))
        .andExpect(status().isOk());

    assertThat(contarLancamentosDaMissao(jdbcTemplate, missaoId))
        .as("a segunda tentativa credita, e credita uma vez só")
        .isEqualTo(1L);
    assertThat(saldoBrl(executor)).isEqualByComparingTo(VALOR_BRL);
    assertThat(statusDaMissao()).isEqualTo("CONCLUIDA");

    assertLedgerReconcilia(jdbcTemplate);
  }

  // ─── Apoio ───────────────────────────────────────────────────────────────────────────────────

  private String statusDaMissao() {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM missao WHERE id = ?", String.class, missaoId);
  }

  private long contarOutbox() {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM outbox WHERE agregado_id = ?", Long.class, missaoId);
  }

  private UUID montarMissaoAguardandoConfirmacao() throws Exception {
    Instant inicio = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    String corpo =
        """
        {
          "categoria": "ENTREGA",
          "titulo": "Missão para prova de rollback",
          "descricao": "Verifica que a falha antes do commit não deixa resíduo.",
          "valorBrl": 0.00,
          "tokensRecompensa": 20,
          "xpRecompensa": 150,
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
            .formatted(inicio, inicio.plus(2, ChronoUnit.DAYS));

    MvcResult criacao =
        mockMvc
            .perform(
                post(BASE)
                    .header("Authorization", bearer(criador))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(corpo))
            .andExpect(status().isCreated())
            .andReturn();

    UUID id =
        UUID.fromString(
            JSON.readTree(criacao.getResponse().getContentAsString()).get("id").asText());

    mockMvc
        .perform(post(BASE + "/{id}/publicar", id).header("Authorization", bearer(criador)))
        .andExpect(status().isOk());
    mockMvc
        .perform(post(BASE + "/{id}/aceitar", id).header("Authorization", bearer(executor)))
        .andExpect(status().isOk());
    mockMvc
        .perform(post(BASE + "/{id}/iniciar", id).header("Authorization", bearer(executor)))
        .andExpect(status().isOk());

    jdbcTemplate.update("UPDATE missao SET status = 'AGUARDANDO_CONFIRMACAO' WHERE id = ?", id);
    return id;
  }

  private UUID criarUsuarioComCarteira(String prefixo) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO usuario (id, nome, email, senha_hash, handle, xp, nivel, streak, rating,
                             papel, status, criado_em, atualizado_em, versao)
        VALUES (?, ?, ?, '{bcrypt}$2a$10$naoUsadoNesteTeste', ?, 0, 1, 0, 0.0,
                'USUARIO', 'ATIVO', NOW(), NOW(), 0)
        """,
        id,
        prefixo,
        prefixo + "-" + id + "@teste.dev",
        prefixo.charAt(0) + id.toString().substring(0, 10));
    jdbcTemplate.update(
        "INSERT INTO carteira (id, usuario_id, saldo_brl, saldo_tokens, versao)"
            + " VALUES (?, ?, 0.00, 0, 0)",
        UUID.randomUUID(),
        id);
    return id;
  }

  private BigDecimal saldoBrl(UUID usuarioId) {
    return jdbcTemplate.queryForObject(
        "SELECT saldo_brl FROM carteira WHERE usuario_id = ?", BigDecimal.class, usuarioId);
  }

  private long saldoTokens(UUID usuarioId) {
    return jdbcTemplate.queryForObject(
        "SELECT saldo_tokens FROM carteira WHERE usuario_id = ?", Long.class, usuarioId);
  }

  private long xpDe(UUID usuarioId) {
    return jdbcTemplate.queryForObject(
        "SELECT xp FROM usuario WHERE id = ?", Long.class, usuarioId);
  }

  private int nivelDe(UUID usuarioId) {
    return jdbcTemplate.queryForObject(
        "SELECT nivel FROM usuario WHERE id = ?", Integer.class, usuarioId);
  }

  private String bearer(UUID usuarioId) {
    return "Bearer "
        + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", "USUARIO");
  }
}
