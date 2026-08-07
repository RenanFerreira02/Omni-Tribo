package com.omnitribo.missoes.api;

import com.omnitribo.identidade.api.AutenticadoPrincipal;
import com.omnitribo.missoes.dominio.FinanciamentoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Financiamento de missões comunitárias, no escopo de uma tribo.
 *
 * <p>Tensão de modelagem, deliberada e vale registrar: o recurso MUTADO é {@code
 * missao.pote_tokens} e a carteira do financiador, não a tribo. A rota é tribo-escopada porque
 * financiar é um ato de pertencimento — quem financia o faz COMO membro daquela tribo, e o {@code
 * triboId} do path é conferido contra a tribo real do usuário no serviço. Por isso o controller
 * vive em {@code missoes} apesar do prefixo {@code /tribos}: é onde mora o agregado que ele altera.
 */
@RestController
@RequestMapping("/api/v1/tribos")
@Tag(name = "Tribos", description = "Financiamento comunitário de missões")
@SecurityRequirement(name = "bearerAuth")
@Validated
public class TriboFinanciamentoController {

  private final FinanciamentoService financiamentoService;

  public TriboFinanciamentoController(FinanciamentoService financiamentoService) {
    this.financiamentoService = financiamentoService;
  }

  @PostMapping("/{triboId}/financiamentos")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Financiar uma missão da tribo",
      description =
          "Debita tokens da carteira do financiador e credita o pote da missão. É o sumidouro da "
              + "moeda: a conclusão paga o executor A PARTIR do pote, sem cunhar token novo. "
              + "Idempotente pelo header Idempotency-Key — repetir a mesma chave devolve o estado "
              + "atual sem debitar de novo.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Pote atualizado"),
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado"),
    @ApiResponse(responseCode = "422", ref = "#/components/responses/RegraNegocioViolada"),
    @ApiResponse(responseCode = "429", ref = "#/components/responses/LimiteExcedido")
  })
  public FinanciamentoResponse financiar(
      @PathVariable UUID triboId,
      @Valid @RequestBody FinanciarMissaoRequest corpo,
      // required=true faz a ausência virar 400 pelo próprio framework. O @Size não protege o banco
      // — a coluna guarda um sha256 de tamanho fixo — e sim o cliente de si mesmo: uma chave vazia
      // ou constante o prenderia para sempre no replay da primeira operação que ele fez.
      @RequestHeader("Idempotency-Key") @NotBlank @Size(min = 8, max = 200) String chaveDoCliente,
      @AuthenticationPrincipal AutenticadoPrincipal principal) {

    return financiamentoService.financiar(
        triboId, corpo.missaoId(), corpo.tokens(), principal.id(), chaveDoCliente);
  }
}
