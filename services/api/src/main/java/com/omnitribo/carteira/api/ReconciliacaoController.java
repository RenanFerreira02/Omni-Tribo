package com.omnitribo.carteira.api;

import com.omnitribo.carteira.dominio.ReconciliacaoService;
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
 * Conciliação do ledger. Exclusivo de ADMIN.
 *
 * <p>Restrito porque a resposta expõe saldos de todos os usuários quando há divergência — e porque
 * a consulta varre {@code lancamento} inteira, o que a torna um vetor de DoS barato se ficasse
 * aberta.
 */
@RestController
@RequestMapping("/api/v1/admin/carteiras")
@Tag(name = "Administração", description = "Conciliação e verificação de integridade")
@SecurityRequirement(name = "bearerAuth")
public class ReconciliacaoController {

  private final ReconciliacaoService reconciliacaoService;

  public ReconciliacaoController(ReconciliacaoService reconciliacaoService) {
    this.reconciliacaoService = reconciliacaoService;
  }

  @GetMapping("/reconciliacao")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Conciliar ledger com as projeções de saldo",
      description =
          "Soma os lançamentos de cada carteira e compara com carteira.saldo_*. Responde "
              + "integro=true quando nenhuma diverge. Uma única statement SQL, para que o "
              + "resultado seja um snapshot consistente mesmo com escritas concorrentes.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Relatório de conciliação"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/AcessoNegado")
  })
  public ReconciliacaoResponse conciliar() {
    return reconciliacaoService.conciliar();
  }
}
