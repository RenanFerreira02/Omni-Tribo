package com.omnitribo.notificacoes.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnitribo.ContadorDeQueries;
import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * O fan-out do alerta de entrega falida emite queries em proporção ao número de destinatários.
 *
 * <p><b>Este teste registra um defeito, não uma garantia — e é de propósito.</b> {@code
 * DespachanteAlertaService.anunciarMissaoDeRetirada} resolve tribo, consentimento e nível em
 * consultas em MASSA (custo fixo, quantos destinatários houver), e depois entra num laço que, por
 * destinatário, emite {@code existsByUsuarioIdAndTipoAndMissaoId}, {@code
 * countByUsuarioIdAndCriadoEmAfter} e {@code save}. O custo do arranjo não cresce; o do laço, sim.
 *
 * <p><b>Por que a medição de carga de 2026-08-25 não viu isto.</b> O seed tem <b>um</b> usuário com
 * NOTIFICACAO e LOCALIZACAO vigentes na área ({@code fernanda}, V904), então o laço rodava uma vez.
 * A rajada do cenário 3 mediu o teto por hora funcionando — e um laço com N=1 é indistinguível de
 * um fan-out em lote. É o tipo de defeito que só aparece quando o bairro tem gente.
 *
 * <p><b>Quando o laço for colapsado em duas consultas em massa mais um {@code saveAll}, a asserção
 * abaixo passa a falhar</b> — e é assim que ela deve falhar: inverta-a para exigir invariância em
 * N. Um teste que afirma "cresce" e continua verde depois da correção estaria medindo outra coisa.
 */
/*
 * `@Import(JwtTestConfig.class)` sem que nenhum caso use JWT, e isso é DELIBERADO: a anotação entra
 * na chave de cache de contexto do Spring, então declará-la faz esta classe compartilhar o contexto
 * já usado pela maioria das suítes MVC em vez de abrir um décimo oitavo pool.
 *
 * Sem ela, medido: a suíte passou de 17 para 18 pools Hikari e `MigracaoTest` quebrou com SQLState
 * 53300 — `too many clients`, não `permission denied`. O sintoma aparece numa classe que não tem
 * relação nenhuma com esta, e a mensagem não menciona contexto, pool nem cache. É a conta que
 * `ContainerConfig` documenta: (nº de contextos × 40) contra `max_connections=500`.
 */
@Import(JwtTestConfig.class)
@DisplayName("Fan-out de entrega falida: queries por destinatário")
class FanOutContagemDeQueriesTest extends TesteIntegracaoMvcBase {

  private static final Logger log = LoggerFactory.getLogger(FanOutContagemDeQueriesTest.class);

  /**
   * Manaus — longe do seed de Pinheiros e de Cidade Líder, para não colher tribo alheia no raio.
   */
  private static final String LAT = "-3.11900";

  private static final String LON = "-60.02170";

  @Autowired DespachanteAlertaService despachante;
  @Autowired JdbcTemplate jdbcTemplate;

  private final List<UUID> usuarios = new ArrayList<>();
  private UUID triboId;
  private UUID pontoId;

  @AfterEach
  void limpar() {
    for (UUID id : usuarios) {
      jdbcTemplate.update("DELETE FROM alerta WHERE usuario_id = ?", id);
      jdbcTemplate.update("DELETE FROM consentimento WHERE usuario_id = ?", id);
      jdbcTemplate.update("DELETE FROM usuario WHERE id = ?", id);
    }
    usuarios.clear();
    if (pontoId != null) {
      jdbcTemplate.update("DELETE FROM ponto_custodia WHERE id = ?", pontoId);
      pontoId = null;
    }
    if (triboId != null) {
      jdbcTemplate.update("DELETE FROM tribo WHERE id = ?", triboId);
      triboId = null;
    }
  }

  @Test
  @DisplayName("o custo cresce com o número de destinatários — três queries por pessoa")
  void custo_do_fanout_cresce_linearmente_com_os_destinatarios() {
    criarTriboComPonto();
    criarMembrosConsentidos(5);
    UUID missaoA = missaoDoSeed();

    int comCinco = contarDespacho(missaoA);

    criarMembrosConsentidos(15); // 20 no total
    UUID missaoB = outraMissaoDoSeed(missaoA);

    int comVinte = contarDespacho(missaoB);

    // O arranjo (tribo + consentimento + nível) é idêntico nos dois; só o laço muda de tamanho.
    int porDestinatarioAMais = (comVinte - comCinco) / 15;

    log.info(
        "fan-out: {} queries para 5 destinatários, {} para 20 — {} por destinatário",
        comCinco,
        comVinte,
        porDestinatarioAMais);

    assertThat(comVinte)
        .as(
            "fan-out linear: %d queries para 5 destinatários, %d para 20 — %d por pessoa a mais",
            comCinco, comVinte, porDestinatarioAMais)
        .isGreaterThan(comCinco);

    // Duas leituras (deduplicação e teto por hora) mais a escrita. O número medido é MAIOR que
    // três, e a diferença tem nome: `Alerta` tem `@Id` atribuído à mão, sem `@Version` e sem
    // implementar `Persistable`, então `SimpleJpaRepository.save` decide que a entidade não é nova
    // e
    // chama `merge()` — que emite um SELECT antes do INSERT. O laço paga essa ida a mais por
    // pessoa,
    // e ela é invisível na leitura do código do despachante.
    // QUATRO, medido: 22 queries para 5 destinatários e 82 para 20 (base 2 + 4N, ajuste exato).
    // Três vêm do laço que se lê no código — dedup, teto por hora e escrita. A quarta não se lê em
    // lugar nenhum: `Alerta` tem `@Id` atribuído à mão, sem `@Version` e sem implementar {@code
    // Persistable}, então `SimpleJpaRepository.save` conclui que a entidade não é nova e chama
    // `merge()`, que emite um SELECT antes do INSERT.
    //
    // Em escala: um fan-out para 200 vizinhos de um bairro custa 802 idas ao banco.
    assertThat(porDestinatarioAMais)
        .as("dedup + teto + escrita, mais o SELECT que o merge acrescenta")
        .isEqualTo(4);
  }

  // ─── Apoio ─────────────────────────────────────────────────────────────────────────────────

  private int contarDespacho(UUID missaoId) {
    String payload =
        """
        {"missaoId":"%s","lat":%s,"lon":%s,"apelidoPonto":"Ponto de contagem",\
        "tokensRecompensa":30,"faixaRisco":"BAIXO","nivelMinimo":1}
        """
            .formatted(missaoId, LAT, LON);

    try (ContadorDeQueries.Captura captura = ContadorDeQueries.iniciar()) {
      despachante.despachar("EntregaFalidaConvertida", missaoId, payload);
      return captura.total();
    }
  }

  private void criarTriboComPonto() {
    triboId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO tribo (id, nome, bairro, criada_em) VALUES (?, ?, 'Centro', NOW())",
        triboId,
        "Tribo de contagem " + triboId.toString().substring(0, 8));

    // O raio do fan-out mede do evento até um ponto de custódia ATIVO ou uma missão de membro da
    // tribo — ver SQL_TRIBOS_NO_RAIO. Sem o ponto, a tribo não tem centro derivado e o despacho
    // termina em "nenhuma tribo no raio", que é um caminho legítimo e mediria zero.
    pontoId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO ponto_custodia (id, codigo, tipo, apelido, ponto, tribo_id, capacidade,
                                    ocupacao, ativo, criado_em)
        VALUES (?, ?, 'LOCKER', 'Ponto de contagem',
                ST_SetSRID(ST_MakePoint(%s, %s), 4326)::geography, ?, 50, 0, true, NOW())
        """
            .formatted(LON, LAT),
        pontoId,
        "CNT-" + pontoId.toString().substring(0, 8),
        triboId);
  }

  private void criarMembrosConsentidos(int quantidade) {
    for (int i = 0; i < quantidade; i++) {
      UUID id = UUID.randomUUID();
      String sufixo = id.toString().substring(0, 8);
      jdbcTemplate.update(
          """
          INSERT INTO usuario (id, nome, email, senha_hash, handle, tribo_id, xp, nivel, streak,
                               rating, papel, status, criado_em, atualizado_em, versao)
          VALUES (?, 'Membro de contagem', ?, '{bcrypt}$2a$10$naoUsadoNesteTeste', ?,
                  ?, 0, 1, 0, 0.0, 'USUARIO', 'ATIVO', NOW(), NOW(), 0)
          """,
          id,
          "contagem-fanout+" + sufixo + "@teste.dev",
          "contagem_fanout_" + sufixo,
          triboId);

      // Os DOIS consentimentos: o despacho exige NOTIFICACAO e LOCALIZACAO vigentes.
      for (String tipo : List.of("NOTIFICACAO", "LOCALIZACAO")) {
        jdbcTemplate.update(
            """
            INSERT INTO consentimento (id, usuario_id, tipo, concedido, versao_texto, criado_em)
            VALUES (?, ?, ?, true, 'v1', NOW())
            """,
            UUID.randomUUID(),
            id,
            tipo);
      }
      usuarios.add(id);
    }
  }

  private UUID missaoDoSeed() {
    return jdbcTemplate.queryForObject(
        "SELECT id FROM missao ORDER BY criada_em LIMIT 1", UUID.class);
  }

  private UUID outraMissaoDoSeed(UUID diferenteDe) {
    // Missão DIFERENTE no segundo despacho, e isso não é detalhe: a deduplicação do laço é por
    // (usuário, tipo, missão). Repetindo a missão, os cinco primeiros seriam pulados no `continue`
    // e o segundo despacho mediria um laço mais curto — o contrário do que o teste quer medir.
    return jdbcTemplate.queryForObject(
        "SELECT id FROM missao WHERE id <> ? ORDER BY criada_em LIMIT 1", UUID.class, diferenteDe);
  }
}
