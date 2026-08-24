package com.omnitribo.compartilhado.api;

import com.omnitribo.compartilhado.dominio.ImpactoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Painel de impacto. Exclusivo de ADMIN.
 *
 * <p>Restrito por dois motivos, e nenhum deles é dado pessoal — não há: a resposta são agregados do
 * sistema inteiro, sem um único identificador de usuário. O primeiro é que o desempenho comercial
 * da operação (quantas transportadoras foram recusadas por falta de saldo, quanto token circula) é
 * informação de negócio, não de produto. O segundo é que a consulta varre {@code lancamento},
 * {@code missao} e {@code checkin} inteiras, o que a torna um vetor de DoS barato se ficasse aberta
 * — a mesma razão que restringe {@code ReconciliacaoController}.
 *
 * <p>Vive em {@code compartilhado} porque o relatório é a composição de quatro módulos e não
 * pertence a nenhum deles. Ver {@code ImpactoService} e ADR 0029.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Administração", description = "Conciliação e verificação de integridade")
@SecurityRequirement(name = "bearerAuth")
public class ImpactoController {

  private final ImpactoService impactoService;

  public ImpactoController(ImpactoService impactoService) {
    this.impactoService = impactoService;
  }

  @GetMapping("/impacto")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Apurar o impacto do ciclo de entrega falida",
      description =
          "Funil (recebidas → convertidas → concluídas), tempo mediano até o check-in do "
              + "executor, custo evitado estimado e circulação do token. Tudo agregado na hora, "
              + "sem tabela de agregação e sem cache. O custo evitado depende de "
              + "app.impacto.custo-reentrega-brl, que é PREMISSA e não medição — por isso a "
              + "resposta ecoa o valor usado e traz a mesma conta com ele em ±50%.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Indicadores apurados"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/AcessoNegado")
  })
  public ImpactoResponse apurar() {
    return impactoService.apurar();
  }
}
