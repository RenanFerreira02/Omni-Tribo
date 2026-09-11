package com.omnitribo.logistica.api;

import com.omnitribo.compartilhado.api.PaginaResponse;
import com.omnitribo.logistica.dominio.RecusaConsultaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Frequência de recusa por ponto de custódia. Exclusivo de ADMIN.
 *
 * <p>É a metade de LEITURA da correção do ADR 0033. A outra metade deduplicou o alerta operacional
 * — antes uma linha por evento, 631 delas em 3 minutos na medição de 2026-08-25 — para uma linha
 * por ponto por janela. Deduplicar sozinho teria apagado a contagem; este endpoint a devolve,
 * agregada de {@code entrega_falida}, que sempre a guardou.
 *
 * <p>Restrito a ADMIN porque descreve a operação de um parceiro comercial: quais lojas do bairro
 * estão sem espaço e com que frequência. É informação de negociação, não de produto — nenhum
 * executor decide nada com ela, e publicá-la exporia o movimento de um parceiro a qualquer usuário
 * autenticado.
 */
@RestController
@RequestMapping("/api/v1/admin/pontos-custodia")
@Tag(name = "Administração", description = "Conciliação e verificação de integridade")
@SecurityRequirement(name = "bearerAuth")
public class RecusaAdminController {

  private final RecusaConsultaService recusaConsultaService;

  public RecusaAdminController(RecusaConsultaService recusaConsultaService) {
    this.recusaConsultaService = recusaConsultaService;
  }

  @GetMapping("/recusas")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Quantas encomendas cada ponto recusou, por motivo",
      description =
          "Agregado de entrega_falida na janela pedida (padrão 24 h, teto 30 dias), do ponto que "
              + "mais recusou para o que menos recusou. É o dado que justifica negociar mais "
              + "capacidade — e é a contagem que o alerta de ponto lotado deixou de guardar quando "
              + "passou a ser deduplicado por janela (ADR 0033). Calculado na hora: não há tabela "
              + "de agregação nem cache, pelo mesmo motivo do painel de impacto (ADR 0029). "
              + "DETECTIVO e passivo — nada avisa que um ponto vive lotado, alguém precisa olhar. "
              + "Não há recorte por transportadora: a recusa por lotação é do ponto, não de quem "
              + "tentou entregar.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Página de recusas por ponto e motivo"),
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/AcessoNegado")
  })
  public PaginaResponse<RecusaPorPontoResponse> listar(
      @Valid @ModelAttribute RecusaFiltroRequest filtro) {

    return PaginaResponse.de(
        recusaConsultaService.listar(
            filtro.horasOuPadrao(),
            PageRequest.of(filtro.paginaOuPadrao(), filtro.tamanhoOuPadrao())));
  }
}
