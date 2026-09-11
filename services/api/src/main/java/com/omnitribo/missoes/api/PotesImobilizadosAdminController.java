package com.omnitribo.missoes.api;

import com.omnitribo.missoes.dominio.DiagnosticoPotesAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Diagnóstico de pote imobilizado. Exclusivo de ADMIN.
 *
 * <p>Restrito por dois motivos, e nenhum deles é dado pessoal — a resposta não traz nenhum. O
 * primeiro é que a lista descreve dinheiro comunitário travado: quanto, onde e há quanto tempo. É
 * informação de operação, do mesmo tipo que restringe o painel de impacto. O segundo é que a
 * consulta varre {@code missao} com um predicado que não é coberto por índice parcial existente, o
 * que a torna um vetor de DoS barato se ficasse aberta — mesma razão do {@code
 * ReconciliacaoController}.
 *
 * <p>Vive em {@code missoes} porque é ali que a tabela {@code missao} mora, seguindo o critério que
 * o {@code OutboxAdminController} usou para ficar em {@code compartilhado}. Não há composição entre
 * módulos aqui — ao contrário do {@code ImpactoController}, que compõe quatro —, então nada
 * justificaria hospedá-lo fora do dono do dado.
 *
 * <p><b>Este endpoint não conserta nada, e isso está dito na descrição publicada.</b> Quem tira a
 * missão do limbo é a varredura por prazo e {@code POST /missoes/{id}/destravar} (ADR 0015), e as
 * duas juntas não alcançam RASCUNHO, ACEITA nem EM_DISPUTA. Ver ADR 0032.
 */
@RestController
@RequestMapping("/api/v1/admin/missoes")
@Tag(name = "Administração", description = "Conciliação e verificação de integridade")
@SecurityRequirement(name = "bearerAuth")
public class PotesImobilizadosAdminController {

  private final DiagnosticoPotesAdminService diagnosticoPotesAdminService;

  public PotesImobilizadosAdminController(
      DiagnosticoPotesAdminService diagnosticoPotesAdminService) {
    this.diagnosticoPotesAdminService = diagnosticoPotesAdminService;
  }

  @GetMapping("/potes-imobilizados")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Listar token preso em missão parada",
      description =
          "Missões NÃO-terminais com pote_tokens > 0 paradas há mais que "
              + "app.missoes.diagnostico.pote-imobilizado-apos. É a metade da CONSERVAÇÃO que a "
              + "reconciliação não enxerga: ela compara o saldo de cada carteira com a soma do "
              + "ledger dela, e um pote preso não quebra essa igualdade — por isso integro=true e "
              + "esta lista não-vazia coexistem, e não se contradizem. O marco muda por status: "
              + "ABERTA mede por janela_fim, os demais por estado_desde. varreduraCobre=false "
              + "indica estado que varredura nenhuma alcança. Este endpoint NÃO corrige nada: quem "
              + "libera o pote é POST /missoes/{id}/destravar, que só aceita EM_ANDAMENTO e "
              + "AGUARDANDO_CONFIRMACAO.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Resumo e página do diagnóstico"),
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/AcessoNegado")
  })
  public PotesImobilizadosResponse listarPotesImobilizados(
      @Valid @ModelAttribute PotesImobilizadosFiltroRequest filtro) {

    Pageable paginacao = PageRequest.of(filtro.paginaOuPadrao(), filtro.tamanhoOuPadrao());
    return diagnosticoPotesAdminService.apurar(paginacao);
  }
}
