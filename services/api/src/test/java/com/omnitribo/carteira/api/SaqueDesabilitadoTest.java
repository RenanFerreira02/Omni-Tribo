package com.omnitribo.carteira.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Comportamento do saque com {@code app.carteira.saque-habilitado: false} — que é o padrão de dev e
 * de produção desde o ADR 0009.
 *
 * <p>Precisa de classe própria porque {@code application-test.yml} liga a flag: sem isso, a
 * cobertura já existente do saque (concorrência, valor mínimo, idempotência, saldo insuficiente)
 * deixaria de exercitar o caminho real e viraria teste de uma guarda só. Aqui a propriedade é
 * sobrescrita para o valor de produção, ao custo de um contexto Spring separado.
 *
 * <p>Por que a flag e não a remoção do código: com o BRL fora das recompensas não há mais como
 * ganhá-lo, e o saque aberto permitiria retirar saldo herdado do modelo anterior. Mas a mecânica de
 * saque — débito no ledger, protocolo, idempotência — é exatamente a que a conversão patrocinada de
 * TOKEN reaproveitaria. Apagar agora e reescrever depois seria jogar fora trabalho já verificado
 * sob concorrência.
 */
@Import(JwtTestConfig.class)
@TestPropertySource(properties = "app.carteira.saque-habilitado=false")
class SaqueDesabilitadoTest extends TesteIntegracaoMvcBase {

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  @Test
  void saqueComFlagDesligada_responde422ComTipoProprioDeSaqueDesabilitado() throws Exception {
    String token =
        JwtTestConfig.gerarTokenValido(UUID.randomUUID(), "saque@omnitribo.dev", "USUARIO");

    MvcResult resultado =
        mockMvc
            .perform(
                post("/api/v1/carteira/saques")
                    .header("Authorization", "Bearer " + token)
                    .header("Idempotency-Key", "saque-desligado-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"valorBrl\":50.00}"))
            // 422, e não 501: o endpoint existe e o pedido está bem formado — o que não cabe é a
            // operação nas regras vigentes.
            .andExpect(status().isUnprocessableEntity())
            // `type` próprio (ADR 0010), e não o 422 genérico: a tela precisa distinguir "recurso
            // desligado por decisão de produto" de "saldo insuficiente". Confundi-los faria o app
            // sugerir ao usuário juntar saldo para uma operação que não vai reabrir.
            .andExpect(
                jsonPath("$.type").value("https://omnitribo.dev/problemas/saque-desabilitado"))
            .andReturn();

    // A recusa precisa dizer ao usuário o que ELE pode fazer, não apenas o que está bloqueado.
    assertThat(resultado.getResponse().getContentAsString()).contains("tokens");
  }

  @Test
  void saqueComFlagDesligada_naoTocaOLedger() throws Exception {
    // A guarda roda antes de qualquer leitura ou lock: recusa não pode deixar rastro no razão
    // append-only, nem consumir a chave de idempotência do cliente.
    String token =
        JwtTestConfig.gerarTokenValido(UUID.randomUUID(), "saque2@omnitribo.dev", "USUARIO");
    String chave = "saque-sem-rastro-" + UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/v1/carteira/saques")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", chave)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"valorBrl\":50.00}"))
        .andExpect(status().isUnprocessableEntity());

    Long lancamentos =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM lancamento WHERE chave_idempotencia LIKE ?",
            Long.class,
            "%" + chave + "%");
    assertThat(lancamentos).isZero();
  }
}
