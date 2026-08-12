package com.omnitribo.identidade.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Perfil completo do usuário autenticado.
 *
 * <p>Usa a Alice do seed, que tem {@code xp = 320} e {@code nivel = 3} gravado na coluna — e a
 * fórmula dá 2. Essa divergência é proposital no seed e é o caso mais interessante daqui: prova que
 * o perfil DERIVA o nível em vez de ler o cache.
 */
@Import(JwtTestConfig.class)
class PerfilControllerTest extends TesteIntegracaoMvcBase {

  private static final UUID ALICE_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
  private static final UUID TRIBO_PINHEIROS =
      UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

  private static final String ME = "/api/v1/usuarios/me";

  @Autowired MockMvc mockMvc;

  @Test
  void perfil_traz_o_que_auth_me_nunca_teve() throws Exception {
    mockMvc
        .perform(autenticado(get(ME), ALICE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nome").value("Alice Ferreira"))
        .andExpect(jsonPath("$.handle").value("alice"))
        .andExpect(jsonPath("$.email").value("alice@omnitribo.dev"))
        .andExpect(jsonPath("$.papel").value("USUARIO"))
        .andExpect(jsonPath("$.xp").value(320))
        // Nome da tribo, não o UUID: era essa a razão de a tela de perfil ser mínima.
        .andExpect(jsonPath("$.tribo.id").value(TRIBO_PINHEIROS.toString()))
        .andExpect(jsonPath("$.tribo.nome").value("Tribo Pinheiros"))
        .andExpect(jsonPath("$.tribo.bairro").value("Pinheiros"));
  }

  /**
   * O seed grava {@code nivel = 3} para 320 de XP, e a curva de {@code RegraNivel} dá 2. Quem está
   * certo é a fórmula: a coluna é cache recalculado a cada concessão, e uma divergência ali não
   * pode virar um nível inventado na tela do usuário.
   */
  @Test
  void nivel_vem_da_formula_e_nao_da_coluna_cache() throws Exception {
    mockMvc
        .perform(autenticado(get(ME), ALICE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nivel").value(2))
        // Os dois marcos da barra de progresso: entrada no nível atual e meta do próximo.
        .andExpect(jsonPath("$.xpNivelAtual").value(100))
        .andExpect(jsonPath("$.xpProximoNivel").value(400));
  }

  @Test
  void conquistas_vem_o_catalogo_inteiro_com_progresso() throws Exception {
    mockMvc
        .perform(autenticado(get(ME), ALICE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.conquistas.length()").value(5))
        .andExpect(jsonPath("$.conquistas[0].codigo").value("INICIANTE"))
        .andExpect(jsonPath("$.conquistas[0].conquistada").value(true))
        // Ainda não conquistada, mas COM progresso: é o que diz ao usuário o que fazer em seguida.
        .andExpect(jsonPath("$.conquistas[1].codigo").value("VIZINHO_PRESENTE"))
        .andExpect(jsonPath("$.conquistas[1].conquistada").value(false))
        .andExpect(jsonPath("$.conquistas[1].progresso").value(320))
        .andExpect(jsonPath("$.conquistas[1].meta").value(500))
        // streak 7 no seed fecha exatamente a meta de 7.
        .andExpect(jsonPath("$.conquistas[4].codigo").value("CONSTANTE"))
        .andExpect(jsonPath("$.conquistas[4].conquistada").value(true));
  }

  @Test
  void perfil_nao_vaza_hash_de_senha_nem_campo_interno() throws Exception {
    mockMvc
        .perform(autenticado(get(ME), ALICE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.senhaHash").doesNotExist())
        .andExpect(jsonPath("$.senha").doesNotExist())
        .andExpect(jsonPath("$.versao").doesNotExist())
        .andExpect(jsonPath("$.status").doesNotExist());
  }

  @Test
  void sem_token_responde_401() throws Exception {
    mockMvc
        .perform(get(ME))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/nao-autenticado"));
  }

  /**
   * Token bem assinado cujo {@code sub} não existe responde <b>401</b>, e a mudança de 404 para 401
   * é a correção, não uma regressão.
   *
   * <p>Este teste esperava 404 porque a validação parava na assinatura: o filtro autenticava
   * qualquer token íntegro e o controller é que descobria, no banco, que não havia usuário. Agora o
   * {@code JwtAuthFilter} consulta {@code ConsultaSessao} e a requisição nem chega ao controller.
   *
   * <p>401 é a resposta certa: assinatura válida prova que o token foi emitido por nós, não que
   * existe uma sessão. O 404 anterior ainda confirmava a quem portasse o token que ele fora aceito.
   * O mesmo caminho é o que barra conta anonimizada — ver {@code LgpdControllerTest}.
   */
  @Test
  void token_valido_de_usuario_inexistente_responde_401() throws Exception {
    mockMvc
        .perform(autenticado(get(ME), UUID.randomUUID()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/nao-autenticado"));
  }

  private MockHttpServletRequestBuilder autenticado(
      MockHttpServletRequestBuilder builder, UUID usuarioId) {
    return builder.header(
        "Authorization",
        "Bearer " + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", "USUARIO"));
  }
}
