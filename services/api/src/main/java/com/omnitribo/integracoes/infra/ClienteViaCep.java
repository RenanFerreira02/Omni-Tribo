package com.omnitribo.integracoes.infra;

import com.omnitribo.integracoes.api.EnderecoResponse;
import com.omnitribo.integracoes.api.ServicoExternoIndisponivelException;
import com.omnitribo.integracoes.dominio.FonteEndereco;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Cliente do ViaCEP. Como o Open-Meteo, não exige chave de API.
 *
 * <p><b>Pegadinha do provedor, e a razão deste comentário:</b> CEP inexistente NÃO responde 404. O
 * ViaCEP devolve <b>200</b> com o corpo {@code {"erro": "true"}}. Um cliente que confie só no
 * status trataria isso como sucesso e propagaria um endereço com todos os campos nulos até o
 * formulário, que passaria a exibir campos vazios sem explicar por quê. É por isso que a checagem
 * de {@code erro} vem antes de qualquer mapeamento.
 */
@Component
public class ClienteViaCep implements FonteEndereco {

  private static final Logger log = LoggerFactory.getLogger(ClienteViaCep.class);

  private final RestClient http;
  private final LimiteDeChamadasExternas limite;

  public ClienteViaCep(
      RestClient.Builder builder,
      @Value("${app.integracoes.endereco.base-url:https://viacep.com.br}") String baseUrl,
      @Value("${app.integracoes.endereco.chamadas-simultaneas:8}") int simultaneas) {
    this.http = builder.baseUrl(baseUrl).build();
    this.limite = new LimiteDeChamadasExternas("ViaCEP", simultaneas);
  }

  @Override
  public Optional<EnderecoResponse> buscar(String cep) {
    Resposta corpo;
    try {
      // O template de URI (`{cep}`) é o que fecha SSRF e path traversal: o valor entra como
      // parâmetro codificado, nunca concatenado. A validação \d{8} no controller vem antes.
      corpo =
          limite.executar(
              () -> http.get().uri("/ws/{cep}/json/", cep).retrieve().body(Resposta.class));
    } catch (ServicoExternoIndisponivelException e) {
      throw e; // Já é o 503 certo, vindo do bulkhead — não reembrulhar nem logar de novo.
    } catch (RuntimeException e) {
      log.warn("Falha ao consultar o provedor de CEP: {}", e.toString());
      throw new ServicoExternoIndisponivelException(
          "Não foi possível consultar o CEP agora. Preencha o endereço manualmente.");
    }

    if (corpo == null || corpo.erro() != null) {
      return Optional.empty();
    }

    return Optional.of(
        new EnderecoResponse(
            // Volta normalizado para o formato que o resto do sistema usa: 8 dígitos, sem hífen.
            // O provedor devolve "01001-000", e a validação de CriarMissaoRequest é \d{8}.
            somenteDigitos(corpo.cep(), cep),
            corpo.logradouro(),
            corpo.bairro(),
            corpo.localidade(),
            corpo.uf()));
  }

  private static String somenteDigitos(String valor, String reserva) {
    String base = valor == null ? reserva : valor;
    return base.replaceAll("\\D", "");
  }

  /**
   * {@code erro} é {@code Object}, e não {@code boolean} nem {@code String}, de propósito: o ViaCEP
   * já respondeu tanto {@code "true"} (texto) quanto {@code true} (booleano) neste campo ao longo
   * do tempo. Tipar de qualquer um dos dois jeitos faria a desserialização estourar no dia em que
   * ele trocasse — e derrubaria a consulta de TODO CEP, inclusive dos válidos, porque este campo é
   * lido antes de qualquer decisão. Só a PRESENÇA importa, e {@code Object} aceita as duas formas.
   */
  private record Resposta(
      String cep, String logradouro, String bairro, String localidade, String uf, Object erro) {}
}
