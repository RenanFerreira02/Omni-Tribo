package com.omnitribo.carteira.api;

import com.omnitribo.carteira.dominio.Beneficio;
import com.omnitribo.carteira.dominio.ResgateService;
import com.omnitribo.carteira.infra.BeneficioRepository;
import com.omnitribo.carteira.infra.ParceiroRepository;
import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administração do catálogo e baixa de resgates. Exclusivo de ADMIN.
 *
 * <p>A baixa é ADMIN e não do próprio parceiro porque parceiro não é usuário do sistema: ele não
 * autentica, não tem carteira e não tem app. Dar credencial a cada comércio do bairro seria um
 * onboarding inteiro — e o balcão real funciona com alguém do Omni-Tribo confirmando a retirada.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Administração", description = "Conciliação e verificação de integridade")
@SecurityRequirement(name = "bearerAuth")
public class BeneficioAdminController {

  private final BeneficioRepository beneficioRepository;
  private final ParceiroRepository parceiroRepository;
  private final ResgateService resgateService;

  public BeneficioAdminController(
      BeneficioRepository beneficioRepository,
      ParceiroRepository parceiroRepository,
      ResgateService resgateService) {
    this.beneficioRepository = beneficioRepository;
    this.parceiroRepository = parceiroRepository;
    this.resgateService = resgateService;
  }

  @PostMapping("/beneficios")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ADMIN')")
  @Transactional
  @Operation(
      summary = "Cadastrar um benefício no catálogo de um parceiro",
      description =
          "O benefício se expressa em BEM ou PERCENTUAL, NUNCA em reais: preço em moeda corrente "
              + "publica uma cotação token→real implícita, e token conversível é dinheiro (ADR "
              + "0009 §6). A recusa vem em 400 aqui, e ck_beneficio_sem_reais (V24) é a barreira "
              + "final no banco.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Benefício cadastrado"),
    @ApiResponse(
        responseCode = "400",
        description = "Corpo inválido — inclusive título ou descrição anunciando reais"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/AcessoNegado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado")
  })
  public BeneficioResponse cadastrar(@Valid @RequestBody CadastrarBeneficioRequest corpo) {
    var parceiro =
        parceiroRepository
            .findById(corpo.parceiroId())
            .orElseThrow(() -> new RecursoNaoEncontradoException("Parceiro não encontrado."));

    Beneficio beneficio =
        new Beneficio(
            UUID.randomUUID(),
            corpo.parceiroId(),
            corpo.titulo(),
            corpo.descricao(),
            corpo.custoTokens(),
            corpo.tipo(),
            Instant.now());

    return BeneficioResponse.de(beneficioRepository.save(beneficio), parceiro, null);
  }

  @PatchMapping("/resgates/{resgateId}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Dar baixa num resgate (o parceiro entregou o benefício)",
      description =
          "PENDENTE → UTILIZADO. Idempotente: dar baixa num resgate já utilizado devolve o mesmo "
              + "estado, porque é o retry de quem não recebeu a resposta. NÃO existe caminho de "
              + "volta — reverter um resgate ressuscitaria token já queimado, o que é emitir moeda "
              + "fora do aporte do patrocinador.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Resgate utilizado"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/NaoAutenticado"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/AcessoNegado"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NaoEncontrado")
  })
  public ResgateResponse darBaixa(@PathVariable UUID resgateId) {
    return resgateService.darBaixa(resgateId);
  }
}
