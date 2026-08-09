package com.omnitribo.logistica.api;

import com.omnitribo.logistica.dominio.PontoCustodiaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pontos de custódia.
 *
 * <p>Primeiro endpoint do módulo {@code logistica}, que até aqui tinha entidade e repositório sem
 * nenhum caminho de leitura — {@code PontoCustodiaRepository} era repositório órfão.
 */
@RestController
@RequestMapping("/api/v1/pontos-custodia")
@Tag(name = "Pontos de custódia", description = "Onde uma encomenda espera")
@SecurityRequirement(name = "bearerAuth")
@Validated
public class PontoCustodiaController {

  private final PontoCustodiaService pontoCustodiaService;

  public PontoCustodiaController(PontoCustodiaService pontoCustodiaService) {
    this.pontoCustodiaService = pontoCustodiaService;
  }

  @GetMapping("/{id}")
  @Operation(
      summary = "Detalhe do ponto de custódia",
      description =
          "Resolve o pontoCustodiaId cru que MissaoResponse expõe. Ponto INATIVO responde 404 e "
              + "não um corpo com ativo=false: uma missão antiga pode apontar para um ponto "
              + "desativado, e devolvê-lo levaria o executor a uma loja que não recebe mais nada.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Ponto de custódia ativo"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado")
  })
  public PontoCustodiaResponse buscar(@PathVariable UUID id) {
    return pontoCustodiaService.buscar(id);
  }

  @GetMapping
  @Operation(
      summary = "Pontos de custódia próximos",
      description =
          "Para os marcadores do mapa e para escolher o ponto ao criar uma ENTREGA. Só pontos "
              + "ativos, ordenados por distância medida pelo PostGIS — o cliente nunca informa "
              + "distância.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Pontos ativos no raio, do mais próximo"),
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado")
  })
  public List<PontoCustodiaResponse> proximos(
      @Valid @ModelAttribute PontoCustodiaFiltroRequest filtro,
      // Teto de 20 km e de 100 itens: os mesmos de /missoes/proximas, para o app não ter duas
      // regras diferentes de "até onde dá para pedir".
      @RequestParam(defaultValue = "2000") @Min(1) @Max(20000) int raioMetros,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limite) {
    return pontoCustodiaService.proximos(filtro.lat(), filtro.lon(), raioMetros, limite);
  }
}
