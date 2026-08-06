package com.omnitribo.missoes.api;

import com.omnitribo.compartilhado.api.PaginaResponse;
import com.omnitribo.identidade.api.AutenticadoPrincipal;
import com.omnitribo.missoes.dominio.AtorMissao;
import com.omnitribo.missoes.dominio.MissaoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/missoes")
@Tag(name = "Missões", description = "Cadastro e ciclo de vida de missões")
@SecurityRequirement(name = "bearerAuth")
public class MissaoController {

  private final MissaoService missaoService;

  public MissaoController(MissaoService missaoService) {
    this.missaoService = missaoService;
  }

  @GetMapping
  @Operation(
      summary = "Listar missões",
      description =
          "Paginado, com filtros opcionais. Rascunhos só aparecem para o próprio criador — a "
              + "regra roda dentro da consulta, então nem o totalElementos vaza rascunho alheio.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Página de missões"),
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "429", ref = "#/components/responses/LimiteExcedido")
  })
  public PaginaResponse<MissaoResponse> listar(
      @Valid @ModelAttribute MissaoFiltroRequest filtro,
      @AuthenticationPrincipal AutenticadoPrincipal principal,
      Authentication autenticacao) {
    return missaoService.listar(filtro, ator(principal, autenticacao));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Criar missão",
      description =
          "A missão nasce em RASCUNHO e pertence ao usuário do token. Missões TRIBO e COLETA não "
              + "podem ter valor em BRL — recompensam em tokens e XP.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Missão criada em RASCUNHO"),
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "429", ref = "#/components/responses/LimiteExcedido")
  })
  public MissaoResponse criar(
      @Valid @RequestBody CriarMissaoRequest request,
      @AuthenticationPrincipal AutenticadoPrincipal principal,
      Authentication autenticacao) {
    return missaoService.criar(request, ator(principal, autenticacao));
  }

  @GetMapping("/{id}")
  @Operation(
      summary = "Buscar missão por id",
      description =
          "Rascunho de outro usuário responde 404, nunca 403 — 403 confirmaria que existe.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Missão encontrada"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado"),
    @ApiResponse(responseCode = "429", ref = "#/components/responses/LimiteExcedido")
  })
  public MissaoResponse buscar(
      @PathVariable UUID id,
      @AuthenticationPrincipal AutenticadoPrincipal principal,
      Authentication autenticacao) {
    return missaoService.buscarPorId(id, ator(principal, autenticacao));
  }

  @PatchMapping("/{id}")
  @Operation(
      summary = "Editar missão",
      description =
          "Só o criador, e só enquanto RASCUNHO ou ABERTA. Recompensa, categoria, status e "
              + "executor não são editáveis: enviá-los no corpo não tem efeito.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Missão atualizada"),
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/AcessoNegado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado"),
    @ApiResponse(responseCode = "409", ref = "#/components/responses/Conflito"),
    @ApiResponse(responseCode = "429", ref = "#/components/responses/LimiteExcedido")
  })
  public MissaoResponse atualizar(
      @PathVariable UUID id,
      @Valid @RequestBody AtualizarMissaoRequest request,
      @AuthenticationPrincipal AutenticadoPrincipal principal,
      Authentication autenticacao) {
    return missaoService.atualizar(id, request, ator(principal, autenticacao));
  }

  @PostMapping("/{id}/publicar")
  @Operation(
      summary = "Publicar missão",
      description = "RASCUNHO → ABERTA. Só o criador. A partir daqui a missão é visível a todos.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Missão publicada"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/AcessoNegado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado"),
    @ApiResponse(responseCode = "409", ref = "#/components/responses/Conflito"),
    @ApiResponse(responseCode = "429", ref = "#/components/responses/LimiteExcedido")
  })
  public MissaoResponse publicar(
      @PathVariable UUID id,
      @AuthenticationPrincipal AutenticadoPrincipal principal,
      Authentication autenticacao) {
    return missaoService.publicar(id, ator(principal, autenticacao));
  }

  @PostMapping("/{id}/aceitar")
  @Operation(
      summary = "Aceitar missão",
      description =
          "ABERTA → ACEITA, vinculando o usuário do token como executor. Serializado por lock "
              + "pessimista: em disputa concorrente exatamente um aceite vence e os demais recebem "
              + "409. O criador não pode aceitar a própria missão. Aceitar NÃO credita nada — "
              + "crédito só existe em CONCLUIDA.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Missão aceita"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/AcessoNegado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado"),
    @ApiResponse(responseCode = "409", ref = "#/components/responses/Conflito"),
    @ApiResponse(responseCode = "429", ref = "#/components/responses/LimiteExcedido")
  })
  public MissaoResponse aceitar(
      @PathVariable UUID id,
      @AuthenticationPrincipal AutenticadoPrincipal principal,
      Authentication autenticacao) {
    return missaoService.aceitar(id, ator(principal, autenticacao));
  }

  @PostMapping("/{id}/iniciar")
  @Operation(summary = "Iniciar execução", description = "ACEITA → EM_ANDAMENTO. Só o executor.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Execução iniciada"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/AcessoNegado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado"),
    @ApiResponse(responseCode = "409", ref = "#/components/responses/Conflito"),
    @ApiResponse(responseCode = "429", ref = "#/components/responses/LimiteExcedido")
  })
  public MissaoResponse iniciar(
      @PathVariable UUID id,
      @AuthenticationPrincipal AutenticadoPrincipal principal,
      Authentication autenticacao) {
    return missaoService.iniciar(id, ator(principal, autenticacao));
  }

  @PostMapping("/{id}/desistir")
  @Operation(
      summary = "Desistir da missão",
      description =
          "ACEITA → ABERTA. Só o executor. A missão volta ao pool sem executor; quem desistiu "
              + "fica registrado na trilha de eventos.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Desistência registrada"),
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/AcessoNegado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado"),
    @ApiResponse(responseCode = "409", ref = "#/components/responses/Conflito"),
    @ApiResponse(responseCode = "429", ref = "#/components/responses/LimiteExcedido")
  })
  public MissaoResponse desistir(
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) MotivoRequest corpo,
      @AuthenticationPrincipal AutenticadoPrincipal principal,
      Authentication autenticacao) {
    return missaoService.desistir(id, ator(principal, autenticacao), motivo(corpo));
  }

  @PostMapping("/{id}/cancelar")
  @Operation(
      summary = "Cancelar missão",
      description = "ABERTA ou ACEITA → CANCELADA. Só o criador.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Missão cancelada"),
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/AcessoNegado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado"),
    @ApiResponse(responseCode = "409", ref = "#/components/responses/Conflito"),
    @ApiResponse(responseCode = "429", ref = "#/components/responses/LimiteExcedido")
  })
  public MissaoResponse cancelar(
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) MotivoRequest corpo,
      @AuthenticationPrincipal AutenticadoPrincipal principal,
      Authentication autenticacao) {
    return missaoService.cancelar(id, ator(principal, autenticacao), motivo(corpo));
  }

  @PostMapping("/{id}/contestar")
  @Operation(
      summary = "Contestar entrega",
      description =
          "AGUARDANDO_CONFIRMACAO → EM_DISPUTA. Só o criador. A disputa só é resolvida por ADMIN.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Contestação registrada"),
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/AcessoNegado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado"),
    @ApiResponse(responseCode = "409", ref = "#/components/responses/Conflito"),
    @ApiResponse(responseCode = "429", ref = "#/components/responses/LimiteExcedido")
  })
  public MissaoResponse contestar(
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) MotivoRequest corpo,
      @AuthenticationPrincipal AutenticadoPrincipal principal,
      Authentication autenticacao) {
    return missaoService.contestar(id, ator(principal, autenticacao), motivo(corpo));
  }

  // ─── Contratos publicados, implementação em fase futura ────────────────────────────────────
  // Estes três validam autorização (403) e transição (409) antes de responder 501: o contrato de
  // erro já é o definitivo, então o app mobile integra a ordem de checagens agora.

  @PostMapping("/{id}/checkin")
  @Operation(
      summary = "Registrar check-in geolocalizado (F6)",
      description =
          "EM_ANDAMENTO → AGUARDANDO_CONFIRMACAO. A distância até a origem é calculada no servidor "
              + "por PostGIS e comparada com raio_checkin_m — valor vindo do cliente é ignorado. "
              + "Autorização e transição já são validadas; a implementação chega em F6.")
  @ApiResponses({
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/AcessoNegado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado"),
    @ApiResponse(responseCode = "409", ref = "#/components/responses/Conflito"),
    @ApiResponse(responseCode = "501", ref = "#/components/responses/NaoImplementado")
  })
  public MissaoResponse checkin(
      @PathVariable UUID id,
      @Valid @RequestBody RegistrarCheckinRequest request,
      @AuthenticationPrincipal AutenticadoPrincipal principal,
      Authentication autenticacao) {
    return missaoService.registrarCheckin(id, ator(principal, autenticacao), request);
  }

  @PostMapping("/{id}/confirmar")
  @Operation(
      summary = "Confirmar conclusão (F7)",
      description =
          "AGUARDANDO_CONFIRMACAO → CONCLUIDA. Só o criador. É o ÚNICO caminho que credita "
              + "carteira, e por isso depende de F7. Autorização e transição já são validadas.")
  @ApiResponses({
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/AcessoNegado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado"),
    @ApiResponse(responseCode = "409", ref = "#/components/responses/Conflito"),
    @ApiResponse(responseCode = "501", ref = "#/components/responses/NaoImplementado")
  })
  public MissaoResponse confirmar(
      @PathVariable UUID id,
      @AuthenticationPrincipal AutenticadoPrincipal principal,
      Authentication autenticacao) {
    return missaoService.confirmar(id, ator(principal, autenticacao));
  }

  @PostMapping("/{id}/resolver")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Resolver disputa (F7)",
      description =
          "EM_DISPUTA → CONCLUIDA ou CANCELADA. Exclusivo de ADMIN. Autorização e transição já são "
              + "validadas; o efeito na carteira chega em F7.")
  @ApiResponses({
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/AcessoNegado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado"),
    @ApiResponse(responseCode = "409", ref = "#/components/responses/Conflito"),
    @ApiResponse(responseCode = "501", ref = "#/components/responses/NaoImplementado")
  })
  public MissaoResponse resolver(
      @PathVariable UUID id,
      @Valid @RequestBody ResolverDisputaRequest request,
      @AuthenticationPrincipal AutenticadoPrincipal principal,
      Authentication autenticacao) {
    return missaoService.resolverDisputa(id, ator(principal, autenticacao), request);
  }

  // ─── Helpers privados ──────────────────────────────────────────────────────────────────────

  /**
   * Monta o ator a partir do JWT. O papel vem da authority do SecurityContext (populada pelo
   * JwtAuthFilter a partir do claim "papel") e não de PapelUsuario: importar identidade/dominio
   * aqui violaria a regra ArchUnit de fronteira entre módulos.
   *
   * <p>Ler a authority além do @PreAuthorize é defesa em profundidade — se alguém remover a
   * anotação de /resolver, o ator continua USUARIO e a máquina de estados nega a resolução.
   */
  private static AtorMissao ator(AutenticadoPrincipal principal, Authentication autenticacao) {
    boolean admin =
        autenticacao != null
            && autenticacao.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    return admin ? AtorMissao.admin(principal.id()) : AtorMissao.usuario(principal.id());
  }

  /** Corpo é opcional nestes endpoints; sem o null-check, um POST sem corpo viraria NPE → 500. */
  private static String motivo(MotivoRequest corpo) {
    return corpo == null ? null : corpo.motivo();
  }
}
