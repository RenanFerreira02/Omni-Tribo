package com.omnitribo.identidade.api;

import com.omnitribo.identidade.dominio.AutenticacaoService;
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
    return autenticacaoService.registrar(request, extrairIp(http), http.getHeader("User-Agent"));
  }

  @PostMapping("/login")
  public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
    return autenticacaoService.login(request, extrairIp(http), http.getHeader("User-Agent"));
  }

  @PostMapping("/refresh")
  public LoginResponse refresh(
      @Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
    return autenticacaoService.refresh(
        request.refreshToken(), extrairIp(http), http.getHeader("User-Agent"));
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(
      @Valid @RequestBody RefreshRequest request,
      @AuthenticationPrincipal AutenticadoPrincipal principal,
      HttpServletRequest http) {
    // usuarioId vem do JWT — nunca do corpo. Previne logout forçado de outro usuário (IDOR).
    autenticacaoService.logout(
        principal.id(), request.refreshToken(), extrairIp(http), http.getHeader("User-Agent"));
  }

  /**
   * Retorna o perfil do usuário autenticado diretamente dos claims do JWT, sem consulta ao banco.
   * Demonstra o fluxo stateless e serve como endpoint de verificação de token nos testes.
   */
  @GetMapping("/me")
  public MeResponse me(@AuthenticationPrincipal AutenticadoPrincipal principal) {
    return new MeResponse(principal.id(), principal.email(), principal.papel().name());
  }

  private String extrairIp(HttpServletRequest request) {
    String xff = request.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
    return request.getRemoteAddr();
  }

  public record MeResponse(UUID id, String email, String papel) {}
}
