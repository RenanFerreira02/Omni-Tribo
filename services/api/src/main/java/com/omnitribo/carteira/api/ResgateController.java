package com.omnitribo.carteira.api;

import com.omnitribo.carteira.dominio.ResgateService;
import com.omnitribo.identidade.api.AutenticadoPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Resgate de benefício — o SUMIDOURO do TOKEN.
 *
 * <p>É a única operação do sistema que QUEIMA moeda: o lançamento debita e não credita ninguém. Com
 * ela a economia deixa de ser estoque e vira ciclo — entra por aporte de patrocinador (ADR 0024),
 * circula por missões, sai aqui. Ver ADR 0027.
 */
@RestController
@RequestMapping("/api/v1/resgates")
@Tag(name = "Benefícios", description = "Catálogo de parceiros do bairro e resgate de tokens")
@SecurityRequirement(name = "bearerAuth")
@Validated
public class ResgateController {

  private final ResgateService resgateService;

  public ResgateController(ResgateService resgateService) {
    this.resgateService = resgateService;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Resgatar um benefício",
      description =
          "QUEIMA tokens da carteira e devolve um código de retirada de 8 caracteres para "
              + "apresentar no parceiro. O custo é lido do catálogo no servidor e congelado no "
              + "resgate — não vem do corpo. Idempotente pelo header Idempotency-Key: repetir a "
              + "mesma chave devolve o mesmo código sem queimar de novo.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Resgatado; tokens queimados"),
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado"),
    @ApiResponse(responseCode = "422", ref = "#/components/responses/RegraNegocioViolada"),
    @ApiResponse(responseCode = "429", ref = "#/components/responses/LimiteExcedido")
  })
  public ResgateResponse resgatar(
      @Valid @RequestBody ResgatarRequest corpo,
      // Obrigatório, como em toda escrita de valor do projeto. Num sumidouro isso protege o
      // USUÁRIO: um retry sem chave queimaria duas vezes o saldo que ele gastou uma vez só.
      @RequestHeader("Idempotency-Key") @NotBlank @Size(min = 8, max = 200) String chaveDoCliente,
      @AuthenticationPrincipal AutenticadoPrincipal principal) {

    return resgateService.resgatar(principal.id(), corpo.beneficioId(), chaveDoCliente);
  }
}
