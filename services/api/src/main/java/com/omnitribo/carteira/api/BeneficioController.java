package com.omnitribo.carteira.api;

import com.omnitribo.carteira.dominio.CatalogoBeneficiosService;
import com.omnitribo.compartilhado.api.PaginaResponse;
import com.omnitribo.compartilhado.dominio.RegraNegocioVioladaException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Catálogo de benefícios de parceiros — o que o token compra. */
@RestController
@RequestMapping("/api/v1/beneficios")
@Tag(name = "Benefícios", description = "Catálogo de parceiros do bairro e resgate de tokens")
@SecurityRequirement(name = "bearerAuth")
public class BeneficioController {

  private final CatalogoBeneficiosService catalogoBeneficiosService;

  public BeneficioController(CatalogoBeneficiosService catalogoBeneficiosService) {
    this.catalogoBeneficiosService = catalogoBeneficiosService;
  }

  @GetMapping
  @Operation(
      summary = "Listar benefícios ativos",
      description =
          "Por PROXIMIDADE (lat, lon, raioMetros) ou por TRIBO (triboId) — nunca os dois. Só "
              + "benefício ativo de parceiro ativo aparece. A distância vem do PostGIS a cada "
              + "consulta e nunca é armazenada; ela é nula no recorte por tribo.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Página do catálogo"),
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "422", ref = "#/components/responses/RegraNegocioViolada")
  })
  public PaginaResponse<BeneficioResponse> listar(
      @Valid @ModelAttribute BeneficioFiltroRequest filtro) {

    if (!filtro.geoConsistente()) {
      throw new RegraNegocioVioladaException(
          "Informe lat, lon e raioMetros juntos, ou nenhum dos três.");
    }
    if (filtro.porProximidade() == filtro.porTribo()) {
      throw new RegraNegocioVioladaException(
          "Escolha UM recorte: proximidade (lat, lon, raioMetros) ou tribo (triboId).");
    }

    Pageable pagina = PageRequest.of(filtro.paginaOuPadrao(), filtro.tamanhoOuPadrao());

    return PaginaResponse.de(
        filtro.porProximidade()
            ? catalogoBeneficiosService.porProximidade(
                filtro.lat(), filtro.lon(), filtro.raioMetros(), pagina)
            : catalogoBeneficiosService.porTribo(filtro.triboId(), pagina));
  }
}
