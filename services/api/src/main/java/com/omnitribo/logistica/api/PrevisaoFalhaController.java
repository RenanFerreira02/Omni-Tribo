package com.omnitribo.logistica.api;

import com.omnitribo.logistica.dominio.DadosParaPrevisao;
import com.omnitribo.logistica.dominio.PrevisaoRiscoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Previsão de risco de falha de entrega.
 *
 * <p><b>POST que não escreve nada.</b> É POST e não GET porque o contexto de uma entrega tem nove
 * campos, alguns opcionais — espremê-los em query string produziria URLs longas, logadas por
 * proxies, com CEP e peso do destinatário no caminho. O corpo mantém isso fora do log de acesso.
 *
 * <p>Exige JWT como todo o resto da API. O único endpoint sem autenticação do projeto continua
 * sendo o webhook de transportadora, que tem HMAC no lugar.
 */
@RestController
@RequestMapping("/api/v1/logistica")
@Tag(name = "Previsão de risco", description = "Probabilidade de uma entrega falhar, e por quê")
@SecurityRequirement(name = "bearerAuth")
public class PrevisaoFalhaController {

  private final PrevisaoRiscoService previsaoRiscoService;

  public PrevisaoFalhaController(PrevisaoRiscoService previsaoRiscoService) {
    this.previsaoRiscoService = previsaoRiscoService;
  }

  @PostMapping("/previsao-falha")
  @Operation(
      summary = "Estimar risco de falha de uma entrega",
      description =
          "Regressão logística interpretável sobre janela horária, tipo de endereço, histórico da "
              + "faixa de CEP, peso, volume, dia da semana, clima e tentativas anteriores. Devolve "
              + "a probabilidade, a faixa e os fatores que mais pesaram — a explicação é o produto, "
              + "tanto quanto o número.\n\n"
              + "Não escreve nada e não tem efeito colateral: a mesma entrada devolve sempre a "
              + "mesma saída.\n\n"
              + "**Os coeficientes foram aprendidos de dados SINTÉTICOS**, com correlações "
              + "documentadas em docs/qualidade/modelo-previsao.md. Validação contra dados reais da "
              + "operação é o próximo passo — ver ADR 0022.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Risco estimado, com os fatores principais"),
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "429", ref = "#/components/responses/LimiteExcedido")
  })
  public PrevisaoFalhaResponse prever(@Valid @RequestBody PrevisaoFalhaRequest request) {
    return PrevisaoFalhaResponse.de(
        previsaoRiscoService.prever(
            new DadosParaPrevisao(
                request.janelaHoraInicio(),
                request.diaSemana(),
                request.tipoEndereco(),
                request.cep(),
                request.pesoKg().doubleValue(),
                request.volumeL().doubleValue(),
                paraDouble(request.chuvaMm()),
                paraDouble(request.temperaturaC()),
                request.tentativasAnteriores())));
  }

  /**
   * {@code BigDecimal} no DTO, {@code double} no modelo — e a fronteira é aqui.
   *
   * <p>O DTO usa {@code BigDecimal} porque é o que o binder do Jackson dá com precisão previsível
   * para número decimal vindo de JSON; o modelo usa {@code double} porque nada nele é dinheiro. A
   * regra "nunca double" do projeto protege dinheiro e token, que participam de uma invariante de
   * conservação — probabilidade não participa de nenhuma.
   */
  private static Double paraDouble(BigDecimal valor) {
    return valor == null ? null : valor.doubleValue();
  }
}
