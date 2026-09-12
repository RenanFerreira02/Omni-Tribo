package com.omnitribo.compartilhado.api;

import com.omnitribo.compartilhado.dominio.OutboxAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Carta-morta da outbox. Exclusivo de ADMIN.
 *
 * <p>Restrito por dois motivos. O primeiro é que a lista descreve FALHAS INTERNAS de despacho:
 * {@code ultimo_erro} carrega a mensagem de uma exceção Java, que é exatamente o tipo de detalhe
 * que o resto do projeto se dá o trabalho de manter fora de toda resposta de erro (RFC 9457, sem
 * stack trace, sem nome de classe, sem mensagem de driver). O segundo é que reenfileirar é escrita
 * sobre a fila de notificação do sistema inteiro.
 *
 * <p>Vive em {@code compartilhado} porque é ali que a outbox mora — {@code Outbox}, {@code
 * PublicadorOutbox}, {@code DrenadorOutboxService} e {@code OutboxRepository} são todos deste
 * módulo. Nenhum import novo cruza fronteira: o precedente é {@code ImpactoController}, que já é um
 * controller em {@code compartilhado/api}. Ver ADR 0031.
 */
@RestController
@RequestMapping("/api/v1/admin/outbox")
@Tag(name = "Administração", description = "Conciliação e verificação de integridade")
@SecurityRequirement(name = "bearerAuth")
public class OutboxAdminController {

  private final OutboxAdminService outboxAdminService;

  public OutboxAdminController(OutboxAdminService outboxAdminService) {
    this.outboxAdminService = outboxAdminService;
  }

  @GetMapping("/esgotados")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Listar eventos que a outbox desistiu de entregar",
      description =
          "Eventos com publicado_em nulo e tentativas no teto (app.outbox.maximo-tentativas). "
              + "São fatos que aconteceram e nunca foram anunciados: um MissaoConcluida aqui "
              + "significa executor creditado e não avisado. totalElementos é a contagem. O "
              + "payload do evento NÃO vem na resposta — ele carrega coordenada de endereço "
              + "residencial e valor creditado, e uma listagem HTTP é o pior lugar para isso.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Página da carta-morta"),
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/AcessoNegado")
  })
  public PaginaResponse<EventoEsgotadoResponse> listarEsgotados(
      @Valid @ModelAttribute OutboxFiltroRequest filtro) {

    Pageable paginacao = PageRequest.of(filtro.paginaOuPadrao(), filtro.tamanhoOuPadrao());
    return PaginaResponse.de(outboxAdminService.listarEsgotados(paginacao));
  }

  @PostMapping("/{eventoId}/reenfileirar")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Devolver um evento esgotado à fila de despacho",
      description =
          "Zera o contador de tentativas e torna a linha elegível outra vez; o ultimo_erro é "
              + "preservado. NÃO despacha: quem entrega continua sendo o DrenadorOutboxJob, na "
              + "varredura seguinte, pelo mesmo caminho de sempre. Idempotente por estado da linha "
              + "— repetir devolve reenfileirado=false sem escrever nada, e um evento ainda em "
              + "backoff cai no mesmo no-op, porque atropelar o backoff não é o que este endpoint "
              + "faz. Evento já entregue é 409: reenfileirá-lo duplicaria a entrega.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Evento devolvido à fila, ou já estava nela"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/AcessoNegado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado"),
    @ApiResponse(responseCode = "409", ref = "#/components/responses/Conflito")
  })
  public ReenfileiramentoResponse reenfileirar(@PathVariable UUID eventoId) {
    return outboxAdminService.reenfileirar(eventoId);
  }
}
