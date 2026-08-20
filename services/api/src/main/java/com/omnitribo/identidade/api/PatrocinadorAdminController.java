package com.omnitribo.identidade.api;

import com.omnitribo.identidade.dominio.PatrocinadorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Patrocinadores. Exclusivo de ADMIN.
 *
 * <p>Aqui mora o único ponto de EMISSÃO de token do sistema. Até a V23 a moeda era cunhada na
 * conclusão de toda missão ENTREGA e AJUDA, implicitamente e sem registro de que uma emissão tinha
 * acontecido — a reconciliação não via, porque ledger e projeção continuavam batendo. O aporte não
 * elimina a cunhagem: concentra-a num evento explícito, com ator identificado, trilha de auditoria
 * e chave de idempotência. Ver ADR 0024.
 *
 * <p><b>Por que ADMIN e não um papel PATROCINADOR.</b> A conta do patrocinador é titular de
 * carteira e nunca autentica ({@code status = INATIVO}); dar a ela credencial de operação criaria
 * uma porta de login para uma identidade que existe só para ser alvo de chave estrangeira. Quem
 * opera em nome do patrocinador é um administrador do Omni-Tribo, e é a identidade DELE que a
 * auditoria grava.
 */
@RestController
@RequestMapping("/api/v1/admin/patrocinadores")
@Tag(name = "Administração", description = "Conciliação e verificação de integridade")
@SecurityRequirement(name = "bearerAuth")
@Validated
public class PatrocinadorAdminController {

  private final PatrocinadorService patrocinadorService;

  public PatrocinadorAdminController(PatrocinadorService patrocinadorService) {
    this.patrocinadorService = patrocinadorService;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Cadastrar patrocinador de uma transportadora",
      description =
          "Cria a conta-titular (que nunca autentica), a carteira e a relação com o slug da "
              + "transportadora. O slug precisa casar com a chave de app.webhooks.segredos, senão "
              + "as entregas daquela transportadora caem em SEM_PATROCINIO.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Patrocinador cadastrado"),
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/AcessoNegado"),
    @ApiResponse(responseCode = "422", ref = "#/components/responses/RegraNegocioViolada")
  })
  public PatrocinadorResponse cadastrar(@Valid @RequestBody CadastrarPatrocinadorRequest corpo) {
    return patrocinadorService.cadastrar(corpo.nome(), corpo.transportadoraSlug(), Instant.now());
  }

  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Listar patrocinadores",
      description =
          "Sem saldo no corpo, de propósito: saldo muda sob lock e uma listagem devolveria uma "
              + "leitura que envelhece antes de chegar à tela. O saldo atual vem na resposta do "
              + "aporte.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Patrocinadores cadastrados"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/AcessoNegado")
  })
  public List<PatrocinadorResponse> listar() {
    return patrocinadorService.listar();
  }

  @PostMapping("/{patrocinadorId}/aportes")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Aportar tokens na carteira do patrocinador",
      description =
          "EMITE tokens. É o único ponto de cunhagem do sistema, e o único lugar onde a soma "
              + "SUM(carteiras) + SUM(potes) muda. Idempotente pelo header Idempotency-Key: "
              + "repetir a mesma chave devolve o saldo atual sem emitir de novo.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Aporte registrado"),
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/AcessoNegado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado"),
    @ApiResponse(responseCode = "422", ref = "#/components/responses/RegraNegocioViolada")
  })
  public AporteResponse aportar(
      @PathVariable UUID patrocinadorId,
      @Valid @RequestBody AportarRequest corpo,
      // Obrigatório, e num endpoint que cunha moeda isso é a defesa principal. Um retry de rede sem
      // chave emitiria duas vezes, e a duplicata NÃO seria detectável depois: ledger e projeção
      // ficariam ambos errados na mesma direção e a reconciliação continuaria respondendo
      // integro=true. Ver ChaveIdempotencia.aportePatrocinador.
      @RequestHeader("Idempotency-Key") @NotBlank @Size(min = 8, max = 200) String chaveDoCliente) {

    return patrocinadorService.aportar(
        patrocinadorId, corpo.tokens(), chaveDoCliente, Instant.now());
  }

  @DeleteMapping("/{patrocinadorId}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Encerrar o patrocínio",
      description =
          "Desativa sem apagar: os lançamentos do patrocinador continuam no ledger, e apagar a "
              + "relação deixaria o extrato sem explicação. A partir daqui as entregas daquela "
              + "transportadora respondem SEM_PATROCINIO.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Patrocínio encerrado"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/AcessoNegado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado")
  })
  public PatrocinadorResponse desativar(@PathVariable UUID patrocinadorId) {
    return patrocinadorService.desativar(patrocinadorId);
  }
}
