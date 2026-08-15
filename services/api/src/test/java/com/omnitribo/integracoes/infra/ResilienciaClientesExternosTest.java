package com.omnitribo.integracoes.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omnitribo.integracoes.api.ServicoExternoIndisponivelException;
import com.omnitribo.integracoes.dominio.ClimaService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.junit5.StartStop;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Resiliência ponta a ponta, contra um servidor HTTP de verdade em socket local.
 *
 * <p><b>Por que MockWebServer aqui e {@code MockRestServiceServer} no arquivo vizinho.</b> O mock
 * do Spring casa EXPECTATIVAS declaradas; a asserção central destes casos é outra — <b>quantas
 * requisições chegaram</b>, e sobretudo que a contagem PAROU de subir depois que o disjuntor abriu.
 * Contagem de requisições reais é exatamente o que este servidor oferece.
 *
 * <p>Os dois arquivos não podem ser fundidos: {@code MockRestServiceServer.bindTo(builder)} instala
 * a própria {@code requestFactory} e apagaria a que estes casos precisam instalar. Aqui é legítimo
 * construir o builder com {@code SimpleClientHttpRequestFactory} — a restrição documentada em
 * {@link ClientesExternosConfig} existe por causa daquele mock, que não participa deste arquivo.
 *
 * <p>Sem Spring e sem Postgres de propósito: é cliente HTTP puro, e cada contexto Spring custa 40
 * conexões contra {@code max_connections=500} (a conta que {@code ContainerConfig} documenta).
 */
class ResilienciaClientesExternosTest {

  private static final int LIMIAR = 5;
  private static final Duration ESPERA_ABERTO = Duration.ofSeconds(30);

  private static final String CEP_OK =
      """
      {"cep":"01001-000","logradouro":"Praça da Sé","bairro":"Sé",
       "localidade":"São Paulo","uf":"SP"}
      """;

  private static final String CLIMA_OK =
      """
      {"current":{"time":"2026-08-08T19:00","temperature_2m":21.3,
       "apparent_temperature":20.1,"weather_code":61,"precipitation":0.4}}
      """;

  @StartStop private final MockWebServer servidor = new MockWebServer();

  private final RelogioAjustavel relogio = new RelogioAjustavel();

  /** Timeout curto: nenhum caso aqui depende de esperar, e 500 ms mantém a suíte rápida. */
  private RestClient.Builder builder() {
    SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
    fabrica.setConnectTimeout(Duration.ofMillis(500));
    fabrica.setReadTimeout(Duration.ofMillis(500));
    return RestClient.builder().requestFactory(fabrica);
  }

  private String baseUrl() {
    return "http://localhost:" + servidor.getPort();
  }

  /** Uma repetição (2 idas ao provedor por chamada lógica) e backoff zero. */
  private ProtecoesExternas protecoes() {
    return new ProtecoesExternas(LIMIAR, ESPERA_ABERTO, 1, Duration.ZERO, Duration.ZERO, relogio);
  }

  private ClienteViaCep clienteCep(ProtecoesExternas protecoes) {
    return new ClienteViaCep(builder(), baseUrl(), 8, protecoes);
  }

  private ClienteOpenMeteo clienteClima(ProtecoesExternas protecoes) {
    return new ClienteOpenMeteo(builder(), baseUrl(), 8, protecoes);
  }

  private void enfileirar(int quantas, int codigo) {
    for (int i = 0; i < quantas; i++) {
      servidor.enqueue(new MockResponse.Builder().code(codigo).body("erro do provedor").build());
    }
  }

  private static MockResponse json(String corpo) {
    return new MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body(corpo)
        .build();
  }

  @Test
  void disjuntor_abre_no_limiar_e_deixa_de_chamar_o_provedor() {
    // Folga proposital na fila: se o disjuntor NÃO abrisse, haveria resposta disponível para
    // continuar chamando — é o que faz a contagem estagnada significar alguma coisa.
    enfileirar(20, 500);
    ClienteViaCep cliente = clienteCep(protecoes());

    for (int i = 0; i < LIMIAR; i++) {
      assertThatThrownBy(() -> cliente.buscar("01001000"))
          .isInstanceOf(ServicoExternoIndisponivelException.class);
    }

    // Cada chamada lógica gastou 2 idas: 1 tentativa + 1 repetição. É a aritmética que prova que a
    // rajada de retry conta como UMA falha do disjuntor — se contasse como duas, o circuito teria
    // aberto na terceira chamada e este número seria 6, não 10.
    int aposAbrir = servidor.getRequestCount();
    assertThat(aposAbrir).isEqualTo(LIMIAR * 2);

    assertThatThrownBy(() -> cliente.buscar("01001000"))
        .isInstanceOf(ServicoExternoIndisponivelException.class);

    // A ASSERÇÃO CENTRAL: respondeu pelo fallback sem tocar o terceiro.
    assertThat(servidor.getRequestCount()).isEqualTo(aposAbrir);
  }

  @Test
  void disjuntor_volta_a_fechar_depois_da_espera() {
    // Exatamente as idas que a abertura consome — nem uma a mais. Com folga na fila, a sonda
    // pegaria
    // uma resposta de erro remanescente e o teste mediria a fila, não a recuperação.
    enfileirar(LIMIAR * 2, 500);
    ClienteViaCep cliente = clienteCep(protecoes());

    for (int i = 0; i < LIMIAR; i++) {
      assertThatThrownBy(() -> cliente.buscar("01001000"))
          .isInstanceOf(ServicoExternoIndisponivelException.class);
    }
    int aposAbrir = servidor.getRequestCount();

    // Relógio avançado, não dormido: a espera é de 30 s e o teste roda em milissegundos.
    relogio.avancar(ESPERA_ABERTO.plusSeconds(1));
    servidor.enqueue(json(CEP_OK));

    assertThat(cliente.buscar("01001000")).get().extracting("cidade").isEqualTo("São Paulo");
    assertThat(servidor.getRequestCount()).isEqualTo(aposAbrir + 1);

    // Fechado de novo: a chamada seguinte também passa.
    servidor.enqueue(json(CEP_OK));
    assertThat(cliente.buscar("01001000")).isPresent();
    assertThat(servidor.getRequestCount()).isEqualTo(aposAbrir + 2);
  }

  @Test
  void retry_nao_acontece_em_404() {
    servidor.enqueue(new MockResponse.Builder().code(404).body("nao encontrado").build());
    ClienteViaCep cliente = clienteCep(protecoes());

    assertThatThrownBy(() -> cliente.buscar("01001000"))
        .isInstanceOf(ServicoExternoIndisponivelException.class);

    // Exatamente UMA ida. 4xx é resposta definitiva do provedor: repetir só gastaria o dobro do
    // tempo para falhar igual.
    assertThat(servidor.getRequestCount()).isEqualTo(1);
  }

  @Test
  void retry_acontece_em_500_e_a_segunda_tentativa_salva_a_chamada() {
    // Controle positivo, e ele é obrigatório: sem este caso, o "== 1" do teste acima passaria
    // igualzinho num sistema que simplesmente não tem retry nenhum.
    servidor.enqueue(new MockResponse.Builder().code(500).body("instabilidade").build());
    servidor.enqueue(json(CEP_OK));

    assertThat(clienteCep(protecoes()).buscar("01001000")).isPresent();
    assertThat(servidor.getRequestCount()).isEqualTo(2);
  }

  @Test
  void cep_inexistente_nao_conta_como_falha_do_provedor() {
    // O ViaCEP responde 200 com {"erro":"true"}. Se essa checagem migrasse para dentro da região
    // protegida, LIMIAR usuários digitando CEP errado abririam o disjuntor de um provedor
    // perfeitamente saudável — e a busca de CEP cairia para todo mundo.
    for (int i = 0; i < LIMIAR * 2; i++) {
      servidor.enqueue(json("{\"erro\":\"true\"}"));
    }
    ClienteViaCep cliente = clienteCep(protecoes());

    for (int i = 0; i < LIMIAR * 2; i++) {
      assertThat(cliente.buscar("99999999")).isEmpty();
    }

    // Continuou chamando: uma ida por consulta, nenhuma repetição, circuito fechado.
    assertThat(servidor.getRequestCount()).isEqualTo(LIMIAR * 2);
  }

  @Test
  void cache_evita_a_segunda_chamada_ao_provedor() {
    servidor.enqueue(json(CLIMA_OK));
    ClimaService servico = new ClimaService(clienteClima(protecoes()), Duration.ofMinutes(10));

    servico.consultar(new BigDecimal("-23.5505"), new BigDecimal("-46.6333"));
    // Coordenada vizinha: cai na mesma chave, porque o serviço arredonda para 2 casas.
    servico.consultar(new BigDecimal("-23.5511"), new BigDecimal("-46.6338"));

    // Isto é o que CacheIntegracoesTest não consegue provar: lá a fonte é um dublê, então nada
    // garantia que o cache estivesse de fato ACIMA do cliente HTTP.
    assertThat(servidor.getRequestCount()).isEqualTo(1);
  }

  @Test
  void disjuntor_aberto_devolve_vazio_para_o_modelo_de_risco_em_vez_de_lancar() {
    enfileirar(20, 500);
    ClimaService servico = new ClimaService(clienteClima(protecoes()), Duration.ofMinutes(10));

    // Caminho do webhook de entrega falida. Se uma exceção escapasse por aqui, a transportadora
    // receberia 5xx ao registrar uma entrega que JÁ falhou, e reenviaria em laço contra um ponto de
    // custódia que continua lotado. É a regra do ADR 0022, e o disjuntor não pode quebrá-la.
    for (int i = 0; i < LIMIAR + 3; i++) {
      BigDecimal lat =
          new BigDecimal("-23.55").add(new BigDecimal("0.1").multiply(new BigDecimal(i)));
      assertThat(servico.consultarParaRisco(lat, new BigDecimal("-46.63"))).isEmpty();
    }

    // E, depois de aberto, deixou de consultar — degradando sem custo.
    int aposAbrir = servidor.getRequestCount();
    assertThat(servico.consultarParaRisco(new BigDecimal("-10.00"), new BigDecimal("-40.00")))
        .isEmpty();
    assertThat(servidor.getRequestCount()).isEqualTo(aposAbrir);
  }

  /** Relógio que anda quando o teste manda, e só então. */
  private static final class RelogioAjustavel extends Clock {

    private Instant agora = Instant.parse("2026-08-15T12:00:00Z");

    void avancar(Duration quanto) {
      agora = agora.plus(quanto);
    }

    @Override
    public Instant instant() {
      return agora;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zona) {
      return this;
    }
  }
}
