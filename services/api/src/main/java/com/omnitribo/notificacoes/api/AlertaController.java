package com.omnitribo.notificacoes.api;

import com.omnitribo.compartilhado.api.PaginaResponse;
import com.omnitribo.identidade.api.AutenticadoPrincipal;
import com.omnitribo.notificacoes.dominio.AlertaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Caixa de entrada do usuário autenticado.
 *
 * <p>Fecha o lado de LEITURA da outbox: o {@code DespachanteAlertaService} escrevia em {@code
 * alerta} desde a F7 e ninguém lia — a caixa existia e era invisível. Nenhuma rota aqui aceita
 * identificador de usuário; o dono sai sempre do JWT.
 *
 * <p><b>A descrição OpenAPI do {@code GET} é contrato PUBLICADO</b> (/v3/api-docs e Swagger UI), e
 * por isso está sujeita à mesma regra dos comentários: não repita "at-least-once" aqui. Esta
 * descrição afirmou exatamente isso até 2026-09-09 — a varredura de 2026-08-20 corrigiu a frase em
 * três comentários e não alcançou esta, porque procurou em comentários e ela mora numa string de
 * anotação. Era a pior das quatro ocorrências: orientava o cliente a se defender de DUPLICATA
 * quando o risco real da outbox é PERDA silenciosa. Ver {@link
 * com.omnitribo.compartilhado.api.PublicadorEventos}, seção "O LIMITE desta garantia".
 */
@RestController
@RequestMapping("/api/v1/alertas")
@Tag(name = "Notificações", description = "Caixa de entrada do usuário")
@SecurityRequirement(name = "bearerAuth")
@Validated
public class AlertaController {

  private final AlertaService alertaService;

  public AlertaController(AlertaService alertaService) {
    this.alertaService = alertaService;
  }

  @GetMapping
  @Operation(
      summary = "Listar notificações",
      description =
          "Paginado, do mais recente para o mais antigo. Use apenasNaoLidos=true para a visão de "
              + "pendências. A entrega NÃO é at-least-once: o drenador da outbox desiste após 5 "
              + "tentativas e o evento é abandonado sem aviso, então esta caixa pode não conter um "
              + "fato que ocorreu — não a trate como registro completo do que aconteceu com o "
              + "usuário. Duplicata continua possível (uma tentativa pode entregar e falhar ao "
              + "marcar), e o par (tipo, missaoId) identifica o fato para deduplicar.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Página da caixa de entrada"),
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado")
  })
  public PaginaResponse<AlertaResponse> listar(
      @Valid @ModelAttribute AlertaFiltroRequest filtro,
      @AuthenticationPrincipal AutenticadoPrincipal principal) {
    return alertaService.listar(principal.id(), filtro);
  }

  @GetMapping("/nao-lidos/contagem")
  @Operation(
      summary = "Contador de não lidas",
      description = "Só o número, para o badge da barra superior, sem trazer corpo de notificação.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Quantidade de não lidas"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado")
  })
  public ContagemNaoLidosResponse contarNaoLidos(
      @AuthenticationPrincipal AutenticadoPrincipal principal) {
    return new ContagemNaoLidosResponse(alertaService.contarNaoLidos(principal.id()));
  }

  @PatchMapping("/{id}/lido")
  @Operation(
      summary = "Marcar como lida",
      description =
          "Idempotente: marcar de novo devolve o mesmo estado, sem erro. Notificação de outro "
              + "usuário responde 404, e não 403 — um 403 confirmaria que o id existe.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Notificação marcada como lida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado")
  })
  public AlertaResponse marcarLido(
      @PathVariable UUID id, @AuthenticationPrincipal AutenticadoPrincipal principal) {
    return alertaService.marcarLido(id, principal.id());
  }
}
