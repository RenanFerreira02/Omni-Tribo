package com.omnitribo.carteira.api;

import com.omnitribo.carteira.dominio.ExtratoService;
import com.omnitribo.carteira.dominio.SaqueService;
import com.omnitribo.carteira.dominio.TransferenciaService;
import com.omnitribo.compartilhado.api.PaginaResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Carteira do usuário autenticado.
 *
 * <p>Nenhum endpoint aqui aceita identificador de carteira ou de usuário vindo do cliente: a
 * carteira é SEMPRE resolvida a partir do JWT. Um id de carteira no path ou no corpo transformaria
 * cada rota numa porta de IDOR sobre dado financeiro.
 */
@RestController
@RequestMapping("/api/v1/carteira")
@Tag(name = "Carteira", description = "Saldos, extrato, transferências e saques")
@SecurityRequirement(name = "bearerAuth")
@Validated
public class CarteiraController {

  private final ExtratoService extratoService;
  private final TransferenciaService transferenciaService;
  private final SaqueService saqueService;

  public CarteiraController(
      ExtratoService extratoService,
      TransferenciaService transferenciaService,
      SaqueService saqueService) {
    this.extratoService = extratoService;
    this.transferenciaService = transferenciaService;
    this.saqueService = saqueService;
  }

  @GetMapping
  @Operation(
      summary = "Saldo da carteira",
      description =
          "Saldos em BRL e tokens. São projeção derivada do ledger — a verdade é a soma dos "
              + "lançamentos, e o endpoint de reconciliação existe para provar que as duas batem.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Saldos correntes"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado")
  })
  public CarteiraResponse saldo(@AuthenticationPrincipal AutenticadoPrincipal principal) {
    return extratoService.saldo(principal.id());
  }

  @GetMapping("/lancamentos")
  @Operation(
      summary = "Extrato",
      description =
          "Paginado e sempre em ordem cronológica decrescente. O ledger é append-only: uma linha "
              + "publicada aqui nunca muda, e correção aparece como ESTORNO — lançamento novo com "
              + "sinal oposto, não edição do original.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Página do extrato"),
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado")
  })
  public PaginaResponse<LancamentoResponse> extrato(
      @Valid @ModelAttribute LancamentoFiltroRequest filtro,
      @AuthenticationPrincipal AutenticadoPrincipal principal) {
    return extratoService.extrato(principal.id(), filtro);
  }

  @PostMapping("/transferencias")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Transferir tokens",
      description =
          "Só entre membros da MESMA tribo — é o que faz do TOKEN uma moeda comunitária. Gera dois "
              + "lançamentos na mesma transação (ENVIADA e RECEBIDA), cada um apontando a "
              + "contraparte. Sujeita a teto por transação e por janela de tempo, que limitam o "
              + "dano de uma conta comprometida. Saldo insuficiente, tribo diferente ou teto "
              + "excedido respondem 422 SEM efeito colateral.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Transferência registrada"),
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado"),
    @ApiResponse(responseCode = "422", ref = "#/components/responses/RegraNegocioViolada"),
    @ApiResponse(responseCode = "429", ref = "#/components/responses/LimiteExcedido")
  })
  public TransferenciaResponse transferir(
      @Valid @RequestBody TransferirRequest corpo,
      // required=true faz a ausência virar 400 pelo framework. O @Size não protege o banco — a
      // coluna guarda um sha256 de tamanho fixo — e sim o cliente de si mesmo: chave vazia ou
      // constante o prenderia para sempre no replay da primeira transferência que ele fez.
      @RequestHeader("Idempotency-Key") @NotBlank @Size(min = 8, max = 200) String chaveDoCliente,
      @AuthenticationPrincipal AutenticadoPrincipal principal) {

    return transferenciaService.transferir(
        principal.id(), corpo.destinatarioId(), corpo.tokens(), corpo.mensagem(), chaveDoCliente);
  }

  @PostMapping("/saques")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Solicitar saque de BRL",
      description =
          "Registra o débito no ledger e devolve um protocolo. O saldo sai da carteira no momento "
              + "do pedido, não na liquidação — senão a mesma quantia poderia ser sacada duas "
              + "vezes na janela entre os dois. A transferência bancária depende de gateway "
              + "externo, fora do escopo do MVP.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Saque registrado"),
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado"),
    @ApiResponse(responseCode = "422", ref = "#/components/responses/RegraNegocioViolada"),
    @ApiResponse(responseCode = "429", ref = "#/components/responses/LimiteExcedido")
  })
  public SaqueResponse sacar(
      @Valid @RequestBody SacarRequest corpo,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(min = 8, max = 200) String chaveDoCliente,
      @AuthenticationPrincipal AutenticadoPrincipal principal) {

    return saqueService.sacar(principal.id(), corpo.valorBrl(), chaveDoCliente);
  }
}
