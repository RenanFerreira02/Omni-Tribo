package com.omnitribo.integracoes.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.omnitribo.integracoes.api.ClimaResponse;
import com.omnitribo.integracoes.api.EnderecoResponse;
import com.omnitribo.integracoes.api.ServicoExternoIndisponivelException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Contrato com os provedores externos, SEM tocar a rede.
 *
 * <p>Testes de unidade puros: o cliente recebe um {@code RestClient.Builder} no construtor, e aqui
 * ele vem com {@link MockRestServiceServer} instalado. É por isso que o timeout mora na
 * configuração e não dentro do cliente — um {@code requestFactory} no construtor apagaria este
 * mock, e a suíte passaria a bater na internet sem avisar.
 *
 * <p>O que estes casos protegem é o mapeamento de borda: o vocabulário do provedor ({@code
 * localidade}, {@code weather_code}, CEP com hífen) virando o nosso, e as duas formas de falha
 * ficando distinguíveis.
 */
class ClientesExternosTest {

  /**
   * Proteção NEUTRA: sem repetição e com limiar de disjuntor inalcançável.
   *
   * <p>É o que mantém estes casos medindo exatamente o que mediam antes do disjuntor existir — o
   * mapeamento de borda. Uma repetição aqui quebraria a contagem de {@link MockRestServiceServer},
   * que espera uma requisição por expectativa declarada, e o teste falharia por um motivo que nada
   * tem a ver com o vocabulário do provedor. Resiliência tem arquivo próprio: {@code
   * ResilienciaClientesExternosTest}.
   */
  private static ProtecoesExternas semResiliencia() {
    return new ProtecoesExternas(
        Integer.MAX_VALUE,
        Duration.ofSeconds(30),
        0,
        Duration.ZERO,
        Duration.ZERO,
        Clock.systemUTC());
  }

  private static ClienteOpenMeteo clima(RestClient.Builder builder) {
    return new ClienteOpenMeteo(builder, "http://provedor.teste", 8, semResiliencia());
  }

  private static ClienteViaCep cep(RestClient.Builder builder) {
    return new ClienteViaCep(builder, "http://provedor.teste", 8, semResiliencia());
  }

  // ─── Clima ─────────────────────────────────────────────────────────────────────────────────

  @Test
  void clima_traduz_o_vocabulario_do_provedor_para_o_nosso() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
    servidor
        .expect(requestTo(Matchers.containsString("/v1/forecast")))
        .andRespond(
            withSuccess(
                """
                {"current":{"time":"2026-08-08T19:00","temperature_2m":21.3,
                 "apparent_temperature":20.1,"weather_code":61}}
                """,
                MediaType.APPLICATION_JSON));

    ClimaResponse clima =
        clima(builder).consultar(new BigDecimal("-23.56"), new BigDecimal("-46.69"));

    assertThat(clima.temperaturaC()).isEqualByComparingTo("21.3");
    assertThat(clima.sensacaoC()).isEqualByComparingTo("20.1");
    assertThat(clima.codigo()).isEqualTo(61);
    // A tradução do código WMO acontece no SERVIDOR: trocar de provedor não deve exigir publicar
    // versão nova do app na loja.
    assertThat(clima.descricao()).isEqualTo("Chuva");
    assertThat(clima.medidoEm()).isNotNull();
  }

  @Test
  void codigo_wmo_desconhecido_nao_derruba_a_consulta() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
    servidor
        .expect(requestTo(Matchers.containsString("/v1/forecast")))
        .andRespond(
            withSuccess(
                """
                {"current":{"time":"2026-08-08T19:00","temperature_2m":18.0,
                 "apparent_temperature":18.0,"weather_code":123}}
                """,
                MediaType.APPLICATION_JSON));

    ClimaResponse clima =
        clima(builder).consultar(new BigDecimal("-23.56"), new BigDecimal("-46.69"));

    // A temperatura é o dado; a legenda é decoração. Um código novo do provedor não pode custar a
    // consulta inteira.
    assertThat(clima.descricao()).isEqualTo("Condição indisponível");
    assertThat(clima.temperaturaC()).isEqualByComparingTo("18.0");
  }

  @Test
  void falha_do_provedor_de_clima_vira_503_sem_vazar_o_provedor() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
    servidor
        .expect(requestTo(Matchers.containsString("/v1/forecast")))
        .andRespond(withServerError());

    assertThatThrownBy(
            () -> clima(builder).consultar(new BigDecimal("-23.56"), new BigDecimal("-46.69")))
        .isInstanceOf(ServicoExternoIndisponivelException.class)
        // A mensagem não pode conter host, status nem corpo do provedor: isso descreveria a nossa
        // topologia de dependências para qualquer cliente.
        .hasMessageNotContainingAny("provedor.teste", "500", "open-meteo");
  }

  @Test
  void resposta_de_clima_sem_o_bloco_current_vira_503() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
    servidor
        .expect(requestTo(Matchers.containsString("/v1/forecast")))
        .andRespond(withSuccess("{\"latitude\":-23.5}", MediaType.APPLICATION_JSON));

    assertThatThrownBy(
            () -> clima(builder).consultar(new BigDecimal("-23.56"), new BigDecimal("-46.69")))
        .isInstanceOf(ServicoExternoIndisponivelException.class);
  }

  // ─── CEP ───────────────────────────────────────────────────────────────────────────────────

  @Test
  void cep_traduz_localidade_para_cidade_e_normaliza_o_numero() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
    servidor
        .expect(requestTo(Matchers.containsString("/ws/01001000/json/")))
        .andRespond(
            withSuccess(
                """
                {"cep":"01001-000","logradouro":"Praça da Sé","bairro":"Sé",
                 "localidade":"São Paulo","uf":"SP"}
                """,
                MediaType.APPLICATION_JSON));

    Optional<EnderecoResponse> endereco = cep(builder).buscar("01001000");

    assertThat(endereco).isPresent();
    // Sem hífen: a validação de CriarMissaoRequest é \d{8}, e devolver "01001-000" faria o
    // formulário preencher um valor que o próprio servidor recusa depois.
    assertThat(endereco.get().cep()).isEqualTo("01001000");
    assertThat(endereco.get().cidade()).isEqualTo("São Paulo");
    assertThat(endereco.get().uf()).isEqualTo("SP");
  }

  /**
   * A pegadinha do ViaCEP: CEP inexistente responde <b>200</b> com {@code {"erro": ...}}. Quem
   * confiar só no status devolve um endereço com todos os campos nulos até o formulário.
   */
  @Test
  void cep_inexistente_responde_200_com_erro_e_vira_vazio() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
    servidor
        .expect(requestTo(Matchers.containsString("/ws/99999999/json/")))
        .andRespond(withSuccess("{\"erro\":\"true\"}", MediaType.APPLICATION_JSON));

    assertThat(cep(builder).buscar("99999999")).isEmpty();
  }

  /** E o mesmo campo já veio como booleano do provedor — por isso ele é tipado como Object. */
  @Test
  void cep_inexistente_com_erro_booleano_tambem_vira_vazio() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
    servidor
        .expect(requestTo(Matchers.containsString("/ws/99999999/json/")))
        .andRespond(withSuccess("{\"erro\":true}", MediaType.APPLICATION_JSON));

    assertThat(cep(builder).buscar("99999999")).isEmpty();
  }

  @Test
  void falha_do_provedor_de_cep_vira_503_e_nao_vazio() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
    servidor.expect(requestTo(Matchers.containsString("/ws/"))).andRespond(withServerError());

    // A distinção importa na tela: "CEP não encontrado" manda o usuário reescrever um número
    // correto várias vezes, quando o problema é a internet.
    assertThatThrownBy(() -> cep(builder).buscar("01001000"))
        .isInstanceOf(ServicoExternoIndisponivelException.class);
  }
}
