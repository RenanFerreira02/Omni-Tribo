package com.omnitribo.identidade.api;

import com.omnitribo.compartilhado.api.EnderecoDoCliente;
import com.omnitribo.identidade.dominio.AutenticacaoService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private final AutenticacaoService autenticacaoService;

  public AuthController(AutenticacaoService autenticacaoService) {
    this.autenticacaoService = autenticacaoService;
  }

  @PostMapping("/registrar")
  @ResponseStatus(HttpStatus.CREATED)
  public LoginResponse registrar(
      @Valid @RequestBody RegistrarRequest request, HttpServletRequest http) {
    return autenticacaoService.registrar(
        request, EnderecoDoCliente.de(http), http.getHeader("User-Agent"));
  }

  @PostMapping("/login")
  public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
    return autenticacaoService.login(
        request, EnderecoDoCliente.de(http), http.getHeader("User-Agent"));
  }

  @PostMapping("/refresh")
  public LoginResponse refresh(
      @Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
    return autenticacaoService.refresh(
        request.refreshToken(), EnderecoDoCliente.de(http), http.getHeader("User-Agent"));
  }

  // Este controller é o único sem @SecurityRequirement na CLASSE, porque a maioria dos endpoints
  // dele é pública de verdade (login, registrar, refresh). Estes dois não são: a cadeia principal
  // só isenta /auth/login, /auth/registrar e /auth/refresh, então logout e me exigem JWT. Sem a
  // anotação, o schema os descrevia como anônimos e quem integrasse pela documentação escreveria um
  // cliente que toma 401. Achado por ContratoOpenApiTest.
  @SecurityRequirement(name = "bearerAuth")
  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(
      @Valid @RequestBody RefreshRequest request,
      @AuthenticationPrincipal AutenticadoPrincipal principal,
      HttpServletRequest http) {
    // usuarioId vem do JWT — nunca do corpo. Previne logout forçado de outro usuário (IDOR).
    autenticacaoService.logout(
        principal.id(),
        request.refreshToken(),
        EnderecoDoCliente.de(http),
        http.getHeader("User-Agent"));
  }

  /**
   * Retorna o perfil do usuário autenticado diretamente dos claims do JWT, sem consulta ao banco.
   * Demonstra o fluxo stateless e serve como endpoint de verificação de token nos testes.
   */
  @SecurityRequirement(name = "bearerAuth")
  @GetMapping("/me")
  public MeResponse me(@AuthenticationPrincipal AutenticadoPrincipal principal) {
    return new MeResponse(principal.id(), principal.email(), principal.papel().name());
  }

  public record MeResponse(UUID id, String email, String papel) {}
}
