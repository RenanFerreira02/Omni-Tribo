package com.omnitribo.identidade.api;

import com.omnitribo.identidade.dominio.TriboService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tribos do bairro.
 *
 * <p>Fecha uma lacuna que tornava impossível uma tela inteira: "sua tribo" só existia como UUID, e
 * o registro não podia deixar ninguém escolher onde mora sem pedir que digitasse um identificador.
 *
 * <p>Não expõe MEMBROS. Listar quem é de qual tribo daria a qualquer usuário autenticado um mapa
 * social do bairro — e a transferência de tokens é restrita à mesma tribo, então essa lista também
 * seria uma lista de alvos.
 */
@RestController
@RequestMapping("/api/v1/tribos")
@Tag(name = "Tribos", description = "Comunidades de bairro")
@SecurityRequirement(name = "bearerAuth")
public class TriboController {

  private final TriboService triboService;

  public TriboController(TriboService triboService) {
    this.triboService = triboService;
  }

  @GetMapping
  @Operation(
      summary = "Listar tribos",
      description =
          "Sem paginação: uma tribo é um bairro, e a lista serve para escolher um nome num "
              + "seletor. Vem sem o centro geográfico — calculá-lo por tribo aqui seria N+1 para "
              + "preencher uma lista onde nenhum mapa é desenhado.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Tribos em ordem alfabética"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado")
  })
  public List<TriboResponse> listar() {
    return triboService.listar();
  }

  @GetMapping("/{id}")
  @Operation(
      summary = "Detalhe da tribo",
      description =
          "Inclui o centro geográfico DERIVADO por PostGIS das missões e pontos de custódia da "
              + "tribo — é para onde o mapa aponta quando a localização do usuário não está "
              + "disponível. Vem nulo se a tribo ainda não tem missão nem ponto.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Tribo com centro derivado"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado")
  })
  public TriboResponse buscar(@PathVariable UUID id) {
    return triboService.buscar(id);
  }
}
