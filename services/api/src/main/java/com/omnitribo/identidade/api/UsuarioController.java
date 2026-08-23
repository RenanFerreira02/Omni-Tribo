package com.omnitribo.identidade.api;

import com.omnitribo.identidade.dominio.BuscaUsuarioService;
import com.omnitribo.identidade.dominio.ConsentimentoService;
import com.omnitribo.identidade.dominio.ExclusaoContaService;
import com.omnitribo.identidade.dominio.ExportacaoDadosService;
import com.omnitribo.identidade.dominio.PerfilService;
import com.omnitribo.identidade.dominio.TipoConsentimento;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * O usuário autenticado, sobre si mesmo.
 *
 * <p>Não existe {@code GET /usuarios/{id}}, e a ausência é a regra: o único perfil que qualquer
 * pessoa pode ler é o próprio. Um endpoint por id transformaria o identificador — que já circula em
 * missões e lançamentos — na chave de um diretório de usuários.
 */
@RestController
@RequestMapping("/api/v1/usuarios")
@Tag(name = "Usuário", description = "Perfil, progressão e dados pessoais")
@SecurityRequirement(name = "bearerAuth")
@Validated
public class UsuarioController {

  private final PerfilService perfilService;
  private final ConsentimentoService consentimentoService;
  private final ExportacaoDadosService exportacaoDadosService;
  private final ExclusaoContaService exclusaoContaService;
  private final BuscaUsuarioService buscaUsuarioService;

  public UsuarioController(
      PerfilService perfilService,
      ConsentimentoService consentimentoService,
      ExportacaoDadosService exportacaoDadosService,
      ExclusaoContaService exclusaoContaService,
      BuscaUsuarioService buscaUsuarioService) {
    this.perfilService = perfilService;
    this.consentimentoService = consentimentoService;
    this.exportacaoDadosService = exportacaoDadosService;
    this.exclusaoContaService = exclusaoContaService;
    this.buscaUsuarioService = buscaUsuarioService;
  }

  @GetMapping("/busca")
  @Operation(
      summary = "Encontrar um vizinho pelo @ exato, dentro da sua tribo",
      description =
          """
          Match EXATO e sem distinção de caixa. NÃO existe busca por prefixo, por parte do nome nem
          por similaridade, e não existe listagem de membros: qualquer uma das três daria a quem
          está autenticado um mapa social do bairro — e, como a transferência é restrita à mesma
          tribo, esse mapa também seria uma lista de alvos.

          Só encontra quem está na MESMA TRIBO de quem pergunta, e a identidade de quem pergunta
          vem do JWT.

          Handle inexistente, handle de outra tribo e conta inutilizável respondem o MESMO 404 —
          são indistinguíveis de propósito. Tem teto de requisições próprio, bem abaixo do limite
          geral de leitura, porque o endpoint responde uma pergunta de sim ou não sobre existência
          e serviria para colher handles em massa. Ver ADR 0028.
          """)
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Vizinho encontrado"),
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(
        responseCode = "404",
        description =
            "Nenhum vizinho ATIVO com esse @ na sua tribo. As três causas são a mesma"
                + " resposta.",
        content = @io.swagger.v3.oas.annotations.media.Content),
    @ApiResponse(responseCode = "429", ref = "#/components/responses/LimiteExcedido")
  })
  public UsuarioBuscaResponse buscar(
      @RequestParam
          @NotBlank(message = "Informe o @ do vizinho")
          @Size(max = 50, message = "Handle tem no máximo 50 caracteres")
          String handle,
      @AuthenticationPrincipal AutenticadoPrincipal principal) {

    return buscaUsuarioService.porHandle(principal.id(), handle);
  }

  @GetMapping("/me")
  @Operation(
      summary = "Perfil completo",
      description =
          "Nome, handle, tribo, XP, nível e conquistas. Separado de GET /auth/me de propósito: "
              + "aquele é a checagem barata de identidade do boot, resolvida só dos claims do JWT, "
              + "sem tocar o banco. O nível vem DERIVADO do XP pela fórmula, não da coluna "
              + "usuario.nivel, que é cache.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Perfil do usuário autenticado"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado")
  })
  public PerfilResponse perfil(@AuthenticationPrincipal AutenticadoPrincipal principal) {
    return perfilService.perfil(principal.id());
  }

  // ─── LGPD ────────────────────────────────────────────────────────────────────────────────────

  @GetMapping("/me/dados")
  @Operation(
      summary = "Exportar meus dados (LGPD art. 18, V)",
      description =
          "Arquivo JSON com tudo o que a plataforma guarda sobre o titular: cadastro, "
              + "consentimentos, missões, lançamentos e check-ins. NÃO inclui senha, tokens nem "
              + "chaves de idempotência — o titular tem direito aos dados dele, não ao material "
              + "criptográfico que protege a conta. Sem paginação: exercer um direito não pode "
              + "exigir remontar o arquivo a partir de páginas.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Dados pessoais do titular"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado")
  })
  public Map<String, Object> exportarDados(
      @AuthenticationPrincipal AutenticadoPrincipal principal) {
    return exportacaoDadosService.exportar(principal.id());
  }

  @GetMapping("/me/consentimentos")
  @Operation(
      summary = "Meus consentimentos",
      description =
          "Estado atual de TODOS os tipos, inclusive os que nunca foram decididos — a tela precisa "
              + "desenhar o interruptor antes da primeira escolha, e 'ausente' e 'recusado' têm o "
              + "mesmo efeito prático.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Consentimentos no estado atual"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado")
  })
  public List<ConsentimentoResponse> consentimentos(
      @AuthenticationPrincipal AutenticadoPrincipal principal) {
    return consentimentoService.listar(principal.id());
  }

  @PutMapping("/me/consentimentos/{tipo}")
  @Operation(
      summary = "Conceder ou revogar um consentimento",
      description =
          "Grava uma LINHA NOVA; a tabela é append-only. Sobrescrever destruiria a evidência de "
              + "que a pessoa consentiu em tal data sob tal versão do texto — que é exatamente o "
              + "que se precisa demonstrar numa disputa.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Escolha registrada"),
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado")
  })
  public ConsentimentoResponse atualizarConsentimento(
      @PathVariable TipoConsentimento tipo,
      @Valid @RequestBody AtualizarConsentimentoRequest request,
      @AuthenticationPrincipal AutenticadoPrincipal principal) {
    return consentimentoService.registrar(principal.id(), tipo, request);
  }

  @DeleteMapping("/me")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(
      summary = "Excluir minha conta (LGPD art. 18, VI)",
      description =
          "IRREVERSÍVEL. Exige a senha atual no corpo: o access token vive 15 minutos, e um "
              + "aparelho desbloqueado esquecido em cima da mesa não pode bastar para apagar a "
              + "identidade de alguém. A conta é ANONIMIZADA, não deletada — o ledger é "
              + "append-only e apagar a linha do usuário quebraria a conservação de TOKEN. Nome, "
              + "e-mail e handle viram valores aleatórios, a sessão é revogada e nada mais liga os "
              + "lançamentos a uma pessoa identificável. Repetir devolve 204 sem efeito.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Conta anonimizada e sessões revogadas"),
    @ApiResponse(responseCode = "400", ref = "#/components/responses/RequisicaoInvalida"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "403", description = "Senha incorreta"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado")
  })
  public void excluirConta(
      @Valid @RequestBody ExclusaoContaRequest request,
      @AuthenticationPrincipal AutenticadoPrincipal principal) {
    exclusaoContaService.excluir(principal.id(), request);
  }
}
