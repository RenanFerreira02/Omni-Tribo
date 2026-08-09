package com.omnitribo.integracoes.api;

import com.omnitribo.integracoes.dominio.ClimaService;
import com.omnitribo.integracoes.dominio.EnderecoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consultas a provedores externos, atrás da nossa fronteira.
 *
 * <p>O app NUNCA fala com o provedor direto, e a razão não é estética: assim a chave de API (se
 * algum dia houver uma), a política de cache e o limite de chamadas ficam do lado que controlamos —
 * um app instalado não pode ser corrigido, e uma chave embutida nele não pode ser rotacionada. Além
 * disso o vocabulário chega ao cliente já traduzido para o nosso, então trocar de provedor não
 * obriga a publicar versão nova. Ver ADR 0011.
 *
 * <p>Os dois endpoints exigem autenticação. São baratos, mas custam chamada externa: deixá-los
 * anônimos os transformaria num proxy aberto que qualquer um pode usar em nosso nome.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Integrações", description = "Clima e endereço por CEP")
@SecurityRequirement(name = "bearerAuth")
@Validated
public class IntegracoesController {

  private final ClimaService climaService;
  private final EnderecoService enderecoService;

  public IntegracoesController(ClimaService climaService, EnderecoService enderecoService) {
    this.climaService = climaService;
    this.enderecoService = enderecoService;
  }

  @GetMapping("/clima")
  @Operation(
      summary = "Condição meteorológica atual",
      description =
          "Para o card do mapa. A coordenada é arredondada a 2 casas (~1,1 km) antes de virar "
              + "chave de cache — a previsão do provedor já tem resolução de quilômetros. "
              + "Indisponibilidade do provedor responde 503 com type servico-externo-indisponivel: "
              + "o cliente deve ESCONDER o card, não exibir erro.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Condição atual"),
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "503", description = "Provedor externo indisponível")
  })
  public ClimaResponse clima(@Valid @ModelAttribute CoordenadaRequest coordenada) {
    return climaService.consultar(coordenada.lat(), coordenada.lon());
  }

  @GetMapping("/enderecos/{cep}")
  @Operation(
      summary = "Endereço por CEP",
      description =
          "Conveniência de preenchimento do formulário de missão, não fonte de verdade: o endereço "
              + "que vale é o que viaja no corpo da criação e é validado lá. CEP bem formado e "
              + "inexistente responde 404; provedor fora do ar responde 503 — e as duas coisas "
              + "pedem reações opostas do usuário, por isso não compartilham type.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Endereço resolvido"),
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado"),
    @ApiResponse(responseCode = "503", description = "Provedor externo indisponível")
  })
  public EnderecoResponse endereco(
      @PathVariable @Pattern(regexp = "\\d{8}", message = "CEP deve ter 8 dígitos, sem hífen")
          String cep) {
    return enderecoService.buscar(cep);
  }
}
