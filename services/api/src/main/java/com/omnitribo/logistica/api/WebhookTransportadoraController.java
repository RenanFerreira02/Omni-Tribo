package com.omnitribo.logistica.api;

import com.omnitribo.compartilhado.api.AtributosWebhook;
import com.omnitribo.compartilhado.dominio.Coordenadas;
import com.omnitribo.compartilhado.dominio.RegraNegocioVioladaException;
import com.omnitribo.integracoes.api.ConsultaClima;
import com.omnitribo.logistica.dominio.ConfirmacaoRetiradaService;
import com.omnitribo.logistica.dominio.DadosEntregaFalida;
import com.omnitribo.logistica.dominio.DadosParaPrevisao;
import com.omnitribo.logistica.dominio.EntregaFalidaService;
import com.omnitribo.logistica.dominio.PrevisaoRiscoService;
import com.omnitribo.logistica.dominio.ResultadoRisco;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entrada de entregas falidas reportadas por transportadora parceira.
 *
 * <p>É o único endpoint de escrita da API sem JWT, e a única razão pela qual isso é aceitável é o
 * {@code HmacWebhookFilter}: nenhuma requisição chega aqui sem assinatura HMAC-SHA256 válida sobre
 * o corpo bruto, dentro de uma janela de cinco minutos. O {@code permitAll} em {@code
 * SecurityConfig} e aquele filtro são uma coisa só — remover um sem o outro abre a rota.
 *
 * <p><b>Sem {@code @SecurityRequirement(name = "bearerAuth")}</b>, ao contrário de todos os outros
 * controllers: anunciar Bearer no OpenAPI faria o Swagger oferecer um botão de autorização que não
 * tem efeito nenhum aqui, e sugeriria ao integrador que o token é o caminho.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@Tag(
    name = "Webhooks",
    description = "Integração com transportadoras. Autenticado por HMAC, não por JWT.")
public class WebhookTransportadoraController {

  private final EntregaFalidaService entregaFalidaService;
  private final PrevisaoRiscoService previsaoRiscoService;

  /**
   * Bean SEPARADO de {@link EntregaFalidaService}, e obrigatoriamente: a confirmação chama {@code
   * missoes}, que chama de volta {@code BaixaCustodiaService} na conclusão. Juntar os dois num
   * serviço só fecharia o ciclo de beans. Ver o javadoc de {@link ConfirmacaoRetiradaService}.
   */
  private final ConfirmacaoRetiradaService confirmacaoRetiradaService;

  /**
   * {@code ConsultaClima} é injetado pela INTERFACE, e o tipo declarado no campo é o que o ArchUnit
   * inspeciona: nomear {@code ClimaService} aqui compilaria e reprovaria o teste de arquitetura,
   * porque {@code logistica} não pode alcançar {@code integracoes.dominio}.
   */
  private final ConsultaClima consultaClima;

  public WebhookTransportadoraController(
      EntregaFalidaService entregaFalidaService,
      PrevisaoRiscoService previsaoRiscoService,
      ConsultaClima consultaClima,
      ConfirmacaoRetiradaService confirmacaoRetiradaService) {
    this.entregaFalidaService = entregaFalidaService;
    this.previsaoRiscoService = previsaoRiscoService;
    this.consultaClima = consultaClima;
    this.confirmacaoRetiradaService = confirmacaoRetiradaService;
  }

  @Operation(
      summary = "Reporta uma entrega que falhou e foi deixada em ponto de custódia",
      description =
          """
          Registra a ocorrência e, havendo vaga no ponto, publica uma missão comunitária de
          retirada com recompensa em XP e TOKEN calculada pelo servidor.

          Autenticação por HMAC-SHA256 sobre o corpo BRUTO, em três cabeçalhos:
          `X-Transportadora` (slug combinado), `X-Timestamp` (segundos desde a época) e
          `X-Assinatura` (hex de HMAC(segredo, timestamp + "." + corpo)).

          Idempotente por (transportadora, codigoRastreio): reenviar devolve o mesmo corpo, sem
          criar segunda missão. É seguro fazer retry.

          A missão NUNCA remunera em reais. `valorOfertadoBrl`, se informado, é insumo do cálculo
          da recompensa em token — nunca é gravado como valor da missão.
          """)
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description =
            "Processado. O corpo diz o desfecho: CONVERTIDA (virou missão) ou RECUSADA (ponto"
                + " lotado — registrado, sem missão). Recusa não é erro: reenviar não ajuda."),
    @ApiResponse(
        responseCode = "400",
        description = "Corpo inválido",
        content = @io.swagger.v3.oas.annotations.media.Content),
    @ApiResponse(
        responseCode = "401",
        description =
            "Assinatura inválida, ausente, fora da janela de tempo, ou transportadora não"
                + " integrada. As causas são deliberadamente indistinguíveis.",
        content = @io.swagger.v3.oas.annotations.media.Content),
    @ApiResponse(
        responseCode = "404",
        description = "Ponto de custódia inexistente ou inativo",
        content = @io.swagger.v3.oas.annotations.media.Content),
    @ApiResponse(
        responseCode = "429",
        description = "Teto de requisições da transportadora",
        content = @io.swagger.v3.oas.annotations.media.Content)
  })
  @PostMapping("/transportadora")
  public EntregaFalidaWebhookResponse registrar(
      @Valid @RequestBody EntregaFalidaWebhookRequest request, HttpServletRequest http) {

    String verificada = transportadoraVerificada(http);

    if (!request.destinoConsistente()) {
      throw new RegraNegocioVioladaException(
          "Informe destinoLat e destinoLon juntos, ou nenhum dos dois.");
    }

    DadosEntregaFalida dados =
        new DadosEntregaFalida(
            verificada,
            request.codigoRastreio(),
            request.motivo(),
            request.pontoCustodiaId(),
            request.descricaoDoItem(),
            request.pesoKg(),
            request.volumeL(),
            request.valorOfertadoBrl(),
            Coordenadas.ponto(request.destinoLat(), request.destinoLon()),
            request.cep(),
            request.logradouro(),
            request.bairro(),
            request.cidade(),
            request.uf(),
            request.janelaHoraInicio() == null ? null : request.janelaHoraInicio().shortValue(),
            request.tipoEndereco(),
            (short) request.tentativasAnterioresOuZero());

    return EntregaFalidaWebhookResponse.de(
        entregaFalidaService.registrar(dados, avaliarRisco(request)));
  }

  @Operation(
      summary = "Confirma que a encomenda chegou ao destinatário",
      description =
          """
          Conclui a missão de retirada e credita o executor na hora, em vez de esperar as 72 h da
          varredura de prazo.

          Mesma autenticação do webhook de reporte: HMAC-SHA256 sobre o corpo BRUTO, nos cabeçalhos
          `X-Transportadora`, `X-Timestamp` e `X-Assinatura`. A transportadora é lida do atributo
          VERIFICADO, nunca do corpo.

          Idempotente por (transportadora, codigoRastreio): reenviar devolve `replay: true` com
          `tokensCreditados: 0`, sem creditar de novo. É seguro fazer retry.

          POR QUE A TRANSPORTADORA CONFIRMA, e não o executor no check-in: o check-in prova
          PRESENÇA, não RECEBIMENTO. Confirmar ali faria o executor confirmar a si mesmo. A
          transportadora é a contraparte com interesse oposto, e é isso que faz a confirmação
          significar alguma coisa. Ver ADR 0026.
          """)
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Retirada confirmada, ou replay de uma já confirmada"),
    @ApiResponse(
        responseCode = "400",
        description = "Corpo inválido",
        content = @io.swagger.v3.oas.annotations.media.Content),
    @ApiResponse(
        responseCode = "401",
        description = "Assinatura inválida, ausente ou fora da janela de tempo",
        content = @io.swagger.v3.oas.annotations.media.Content),
    @ApiResponse(
        responseCode = "404",
        description =
            "Código de rastreio desconhecido, ou entrega que nunca virou missão (ponto lotado, sem"
                + " patrocínio). Reenviar não muda o resultado.",
        content = @io.swagger.v3.oas.annotations.media.Content),
    @ApiResponse(
        responseCode = "409",
        description =
            "A missão não está em AGUARDANDO_CONFIRMACAO — ninguém executou ainda, ou ela foi"
                + " cancelada, expirada ou contestada.",
        content = @io.swagger.v3.oas.annotations.media.Content),
    @ApiResponse(
        responseCode = "429",
        description = "Teto de requisições da transportadora",
        content = @io.swagger.v3.oas.annotations.media.Content)
  })
  @PostMapping("/transportadora/confirmacao")
  public ConfirmacaoRetiradaResponse confirmar(
      @Valid @RequestBody ConfirmacaoRetiradaRequest request, HttpServletRequest http) {

    return ConfirmacaoRetiradaResponse.de(
        confirmacaoRetiradaService.confirmar(
            transportadoraVerificada(http), request.codigoRastreio()));
  }

  /**
   * O slug que o {@code HmacWebhookFilter} publicou DEPOIS de conferir a assinatura.
   *
   * <p>Nunca {@code request.getHeader(...)} e nunca o corpo: o cabeçalho é a alegação, a assinatura
   * é a prova, e ler a alegação sem a prova deixaria a transportadora A gravar — ou confirmar — em
   * nome da B. Extraído para cá porque os DOIS endpoints precisam da mesma garantia, e duplicar a
   * leitura é como um dos dois acaba lendo o cabeçalho cru numa refatoração futura.
   */
  private static String transportadoraVerificada(HttpServletRequest http) {
    Object verificada = http.getAttribute(AtributosWebhook.TRANSPORTADORA);
    if (verificada == null) {
      // Inalcançável enquanto o filtro estiver na cadeia. Existe para que, se alguém remover o
      // filtro e esquecer o permitAll, a rota falhe fechada em vez de gravar sem autenticação.
      throw new IllegalStateException(
          "Requisição chegou ao webhook sem transportadora verificada — HmacWebhookFilter ausente"
              + " da cadeia de segurança.");
    }
    return (String) verificada;
  }

  /**
   * Calcula o risco ANTES de qualquer transação ser aberta. A ordem é obrigatória.
   *
   * <p>{@code EntregaFalidaService.registrar} é {@code @Transactional} e a primeira coisa que faz é
   * {@code SELECT ... FOR UPDATE} no ponto de custódia. Consultar um provedor externo lá dentro
   * seguraria esse lock durante uma chamada de rede — e sob rajada de webhooks no mesmo ponto, as
   * transações enfileirariam atrás de um lock preso por I/O externo. É o mesmo desenho que derrubou
   * o check-in da F6 e levou o projeto a proibir {@code REQUIRES_NEW} no caminho de valor.
   *
   * <p>Fora da transação, o pior caso de um provedor lento é a latência do webhook — limitada pelo
   * {@code app.integracoes.timeout} de 2 s e pelo bulkhead — e nenhum lock fica esperando.
   *
   * <p>Provedor fora do ar não interrompe nada: {@code ConsultaClima} devolve vazio, as duas
   * características climáticas são imputadas, e a resposta registra isso. Recusar a entrega falida
   * porque o Open-Meteo caiu faria a transportadora reenviar em laço enquanto a encomenda continua
   * no ponto de custódia sem missão.
   */
  private ResultadoRisco avaliarRisco(EntregaFalidaWebhookRequest request) {
    Optional<ConsultaClima.CondicaoClimatica> clima =
        request.destinoLat() == null
            ? Optional.empty()
            : consultaClima.consultarParaRisco(request.destinoLat(), request.destinoLon());

    ZonedDateTime agora = ZonedDateTime.now(ZoneId.of("America/Sao_Paulo"));

    return previsaoRiscoService.prever(
        new DadosParaPrevisao(
            // A janela informada pela transportadora é o dado certo — é o horário em que a entrega
            // foi tentada. Sem ela, o instante do webhook é a melhor aproximação disponível: o
            // registro costuma chegar logo após a tentativa frustrada.
            request.janelaHoraInicio() == null ? agora.getHour() : request.janelaHoraInicio(),
            agora.getDayOfWeek(),
            request.tipoEnderecoOuPadrao(),
            request.cep(),
            request.pesoKg().doubleValue(),
            request.volumeL().doubleValue(),
            clima.map(c -> c.chuvaMm().doubleValue()).orElse(null),
            clima.map(c -> c.temperaturaC().doubleValue()).orElse(null),
            request.tentativasAnterioresOuZero()));
  }
}
