package com.omnitribo.integracoes.infra;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.omnitribo.integracoes.api.ClimaResponse;
import com.omnitribo.integracoes.api.ServicoExternoIndisponivelException;
import com.omnitribo.integracoes.dominio.FonteClima;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Cliente do Open-Meteo. Escolhido por não exigir chave de API: nenhuma credencial nova entra no
 * projeto, e {@code .env.example} continua descrevendo tudo o que é preciso para rodar.
 *
 * <p>Toda falha vira {@link ServicoExternoIndisponivelException}, inclusive as de parsing. A razão
 * é que, do ponto de vista de quem chamou, "o provedor caiu" e "o provedor mudou o JSON" pedem a
 * mesma reação — seguir sem o card. Distinguir as duas só interessa ao log, e é lá que a distinção
 * fica.
 */
@Component
public class ClienteOpenMeteo implements FonteClima {

  private static final Logger log = LoggerFactory.getLogger(ClienteOpenMeteo.class);

  private final RestClient http;
  private final LimiteDeChamadasExternas limite;

  public ClienteOpenMeteo(
      RestClient.Builder builder,
      @Value("${app.integracoes.clima.base-url:https://api.open-meteo.com}") String baseUrl,
      @Value("${app.integracoes.clima.chamadas-simultaneas:8}") int simultaneas) {
    this.http = builder.baseUrl(baseUrl).build();
    this.limite = new LimiteDeChamadasExternas("Open-Meteo", simultaneas);
  }

  @Override
  public ClimaResponse consultar(BigDecimal lat, BigDecimal lon) {
    Resposta corpo;
    try {
      corpo =
          limite.executar(
              () ->
                  http.get()
                      .uri(
                          uri ->
                              uri.path("/v1/forecast")
                                  .queryParam("latitude", lat)
                                  .queryParam("longitude", lon)
                                  .queryParam(
                                      "current",
                                      "temperature_2m,apparent_temperature,weather_code,"
                                          + "precipitation")
                                  .queryParam("timezone", "UTC")
                                  .build())
                      .retrieve()
                      .body(Resposta.class));
    } catch (ServicoExternoIndisponivelException e) {
      throw e; // Já é o 503 certo, vindo do bulkhead — não reembrulhar nem logar de novo.
    } catch (RuntimeException e) {
      // Log com a causa; resposta sem ela. O cliente não precisa saber qual host falhou, e contar
      // isso descreveria a nossa topologia para qualquer um.
      log.warn("Falha ao consultar o provedor de clima: {}", e.toString());
      throw new ServicoExternoIndisponivelException(
          "Não foi possível consultar a previsão do tempo agora.");
    }

    if (corpo == null || corpo.current() == null) {
      log.warn("Provedor de clima respondeu sem o bloco 'current'");
      throw new ServicoExternoIndisponivelException(
          "Não foi possível consultar a previsão do tempo agora.");
    }

    Atual atual = corpo.current();
    int codigo = atual.weatherCode() == null ? -1 : atual.weatherCode();
    return new ClimaResponse(
        atual.temperature2m(),
        atual.apparentTemperature(),
        codigo,
        CodigosWmo.descricao(codigo),
        // Ausente vira ZERO e não null: "o provedor não informou precipitação" e "não está
        // chovendo" produzem a mesma leitura no card, e o modelo de risco precisa de um número. A
        // distinção que importa — provedor mudo por completo — já é tratada acima, com exceção.
        atual.precipitation() == null ? BigDecimal.ZERO : atual.precipitation(),
        instanteDe(atual.time()));
  }

  /**
   * O provedor devolve tempo local sem offset ({@code 2026-08-08T19:00}) e nós pedimos
   * timezone=UTC, então a leitura é em UTC. Instante inválido não derruba a consulta — o card
   * sobrevive sem o carimbo de hora, que é decoração, enquanto a temperatura é o dado.
   */
  private static Instant instanteDe(String tempoLocal) {
    if (tempoLocal == null) {
      return null;
    }
    try {
      return LocalDateTime.parse(tempoLocal).toInstant(ZoneOffset.UTC);
    } catch (RuntimeException e) {
      // Degradar em silêncio esconderia uma mudança de formato do provedor: o carimbo sumiria de
      // TODA resposta e ninguém descobriria. O card sobrevive sem ele; o log é o que avisa.
      log.warn("Formato de tempo inesperado do Open-Meteo: {}", tempoLocal);
      return null;
    }
  }

  /**
   * Só os campos que usamos: o provedor devolve muito mais, e ignorar o resto é o padrão do app.
   */
  private record Resposta(Atual current) {}

  /**
   * O provedor publica em snake_case, e o mapeamento é EXPLÍCITO por campo em vez de uma estratégia
   * global de nomes. Duas razões: uma estratégia no mapper compartilhado mudaria a serialização de
   * toda a API, e o nome literal aqui documenta o contrato externo — quando o provedor renomear um
   * campo, o diff mostra exatamente o que mudou do lado dele.
   *
   * <p>{@code weatherCode} é boxed, e não {@code int} primitivo: campo ausente no JSON viraria
   * {@code null} e estouraria a desserialização inteira num NPE, derrubando a consulta por causa de
   * um dado que só escolhe ícone.
   */
  private record Atual(
      @JsonProperty("time") String time,
      @JsonProperty("temperature_2m") BigDecimal temperature2m,
      @JsonProperty("apparent_temperature") BigDecimal apparentTemperature,
      @JsonProperty("weather_code") Integer weatherCode,
      @JsonProperty("precipitation") BigDecimal precipitation) {}
}
