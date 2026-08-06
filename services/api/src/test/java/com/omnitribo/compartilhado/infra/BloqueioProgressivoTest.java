package com.omnitribo.compartilhado.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import com.omnitribo.identidade.infra.AuditoriaRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Bloqueio progressivo: 10 falhas em 15 min bloqueiam por 15 min (item 7 da fase de segurança).
 *
 * <p>Por que o contador é semeado chamando registrarFalha() em vez de disparar 10 logins HTTP: o
 * BloqueioLoginService.verificar() consome o bucket de 5/min ANTES de o AutenticacaoService chegar
 * na linha que chama registrarFalha(). Da 6ª tentativa em diante o login morre com
 * BloqueioException sem contabilizar falha nenhuma — então, por HTTP, só se acumulam 5 falhas por
 * minuto e chegar a 10 exigiria mais de dois minutos de espera real dentro do teste.
 *
 * <p>registrarFalha() é o mesmo método que o AutenticacaoService chama no caminho de senha errada,
 * não um atalho de teste. O efeito é verificado nas três pontas que importam: o serviço passa a
 * recusar, o endpoint real devolve 429, e a trilha append-only registra o evento.
 */
@Import(JwtTestConfig.class)
class BloqueioProgressivoTest extends TesteIntegracaoMvcBase {

  @Autowired MockMvc mockMvc;
  @Autowired BloqueioLoginService bloqueioLoginService;
  @Autowired AuditoriaRepository auditoriaRepository;

  private static final int FALHAS_PARA_BLOQUEIO = 10;

  /**
   * Chave (ip,email) exclusiva por teste. Os contadores do BloqueioLoginService são
   * ConcurrentHashMap de escopo de JVM, compartilhados por todas as classes que reusam o mesmo
   * contexto Spring — chaves distintas são a única forma de isolamento, já que não há API para
   * limpá-los.
   */
  private String ipUnico() {
    return "10.bloq." + UUID.randomUUID().toString().substring(0, 8);
  }

  private String emailUnico() {
    return "bloqueio-" + UUID.randomUUID() + "@omnitribo.dev";
  }

  @Test
  void dezFalhas_bloqueiam_eOEfeitoChegaAoHttpEaAuditoria() throws Exception {
    String ip = ipUnico();
    String email = emailUnico();

    for (int i = 0; i < FALHAS_PARA_BLOQUEIO; i++) {
      bloqueioLoginService.registrarFalha(ip, email);
    }

    // (1) O serviço recusa — e com o tempo do BLOQUEIO, não o do bucket. Esta assertion é o que dá
    // sentido ao teste: sem ela, um bucket estourado (60 s) passaria como se fosse bloqueio.
    var bloqueio = bloqueioLoginService.verificar(ip, email);
    assertThat(bloqueio).as("10 falhas devem bloquear a chave (ip,email)").isNotNull();
    assertThat(bloqueio.segundosRestantes())
        .as("Deve ser o bloqueio de 15 min (~900 s), não o bucket de 1 min (60 s)")
        .isGreaterThan(800L);

    // (2) O bloqueio se traduz em 429 com Retry-After no endpoint real de login.
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"senha\":\"SenhaQualquer!123\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.status").value(429));

    // (3) A trilha append-only registra o bloqueio, com atorId nulo: revelar o usuário aqui
    // confirmaria a existência do email a quem está justamente tentando descobrir isso.
    assertThat(auditoriaRepository.findAll())
        .anySatisfy(
            registro -> {
              assertThat(registro.getAcao()).isEqualTo("LOGIN_BLOQUEADO");
              assertThat(registro.getIp()).isEqualTo(ip);
              assertThat(registro.getAtorId()).isNull();
            });
  }

  @Test
  void noveFalhas_naoBloqueiam() {
    String ip = ipUnico();
    String email = emailUnico();

    // Trava o limiar em >= 10. Um off-by-one que bloqueasse na 9ª passaria despercebido sem isto.
    for (int i = 0; i < FALHAS_PARA_BLOQUEIO - 1; i++) {
      bloqueioLoginService.registrarFalha(ip, email);
    }

    assertThat(bloqueioLoginService.verificar(ip, email))
        .as("9 falhas ainda não atingem o limiar de bloqueio")
        .isNull();
  }

  @Test
  void loginBemSucedido_zeraOContadorDeFalhas() {
    String ip = ipUnico();
    String email = emailUnico();

    for (int i = 0; i < FALHAS_PARA_BLOQUEIO - 1; i++) {
      bloqueioLoginService.registrarFalha(ip, email);
    }
    bloqueioLoginService.registrarSucesso(ip, email);

    // Sem o reset, estas 9 somariam 18 e bloqueariam. O usuário legítimo que erra a senha algumas
    // vezes e acerta não pode ficar a uma tentativa do bloqueio.
    for (int i = 0; i < FALHAS_PARA_BLOQUEIO - 1; i++) {
      bloqueioLoginService.registrarFalha(ip, email);
    }

    assertThat(bloqueioLoginService.verificar(ip, email))
        .as("Sucesso zera o contador; 9 falhas posteriores não bloqueiam")
        .isNull();
  }
}
