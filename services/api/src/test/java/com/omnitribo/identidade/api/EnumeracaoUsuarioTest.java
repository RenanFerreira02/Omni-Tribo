package com.omnitribo.identidade.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.TesteIntegracaoMvcBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Enumeração de usuários pelo login — a resposta não pode diferenciar "email não existe" de "senha
 * errada", nem no CORPO nem no TEMPO.
 *
 * <p>A parte do corpo já estava coberta; a do tempo não estava, e estava quebrada: {@code usuario
 * != null && matches(...)} curto-circuitava, então email inexistente respondia sem rodar o KDF.
 * Medido contra a API em execução: <b>~6 ms contra ~68 ms</b>. Mensagem idêntica, relógio
 * entregando a resposta.
 *
 * <p><b>Por que verificar a CHAMADA e não o tempo.</b> Um teste que cronometra as duas requisições
 * e compara seria intrinsecamente instável — GC, JIT e runner de CI compartilhado produzem ruído da
 * mesma ordem de grandeza do sinal, e o limiar escolhido para não dar falso positivo acabaria
 * grande demais para pegar a regressão. Afirmar que {@code matches} é INVOCADO no caminho do email
 * inexistente é determinístico e captura exatamente a causa: se alguém reintroduzir o {@code &&}, a
 * invocação some e este teste falha.
 */
class EnumeracaoUsuarioTest extends TesteIntegracaoMvcBase {

  @Autowired MockMvc mockMvc;

  // Spy, não mock: o encoder real continua funcionando (o login legítimo precisa validar de
  // verdade), e ainda assim as invocações ficam observáveis.
  @MockitoSpyBean PasswordEncoder passwordEncoder;

  private static String corpo(String email) {
    return """
        {"email":"%s","senha":"SenhaQualquer@12345"}
        """
        .formatted(email);
  }

  @Test
  void emailInexistente_aindaAssimExecutaOKdf() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo("nao-existe-" + UUID.randomUUID() + "@omnitribo.dev")))
        .andExpect(status().isUnauthorized());

    // O KDF tem de rodar contra o hash dummy. Sem esta chamada, o tempo de resposta denuncia que
    // a conta não existe.
    verify(passwordEncoder, times(1)).matches(any(), anyString());
  }

  @Test
  void emailExistenteComSenhaErrada_devolveMensagemIdenticaAoInexistente() throws Exception {
    // Usuário do seed. Um e-mail que existe e outro que não existe têm de ser indistinguíveis no
    // corpo — status, type e detail iguais.
    String respostaExistente =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(corpo("alice@omnitribo.dev")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.detail").value("Credenciais inválidas"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    String respostaInexistente =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(corpo("nao-existe-" + UUID.randomUUID() + "@omnitribo.dev")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.detail").value("Credenciais inválidas"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    // Comparados sem o traceId, que é único por requisição por construção.
    org.assertj.core.api.Assertions.assertThat(semTraceId(respostaInexistente))
        .isEqualTo(semTraceId(respostaExistente));
  }

  private static String semTraceId(String json) {
    return json.replaceAll(",\"traceId\":\"[^\"]*\"", "");
  }
}
