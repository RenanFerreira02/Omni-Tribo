package com.omnitribo.compartilhado.infra;

import com.omnitribo.identidade.api.AutenticadoPrincipal;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Extrai e valida o JWT de cada requisição. Não é @Component: instanciado em SecurityConfig para
 * evitar registro duplo pelo Spring Boot como filtro de servlet.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

  private final JwtService jwtService;

  public JwtAuthFilter(JwtService jwtService) {
    this.jwtService = jwtService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith("Bearer ")) {
      // Sem token: continua sem autenticar. O SecurityConfig decidirá se o endpoint é público.
      chain.doFilter(request, response);
      return;
    }

    String token = header.substring(7);
    try {
      Claims claims = jwtService.validar(token);

      UUID usuarioId = UUID.fromString(claims.getSubject());
      String email = claims.get("email", String.class);
      String papel = claims.get("papel", String.class);

      // AutenticadoPrincipal.deClaims() encapsula o valueOf de PapelUsuario (identidade/dominio).
      // Este filtro (compartilhado/infra) importa apenas identidade/api — respeita ArchUnit.
      AutenticadoPrincipal principal = AutenticadoPrincipal.deClaims(usuarioId, email, papel);

      // ROLE_ é convenção do Spring Security para autorização por papel.
      var auth =
          new UsernamePasswordAuthenticationToken(
              principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + papel)));
      auth.setDetails(request);

      SecurityContextHolder.getContext().setAuthentication(auth);
    } catch (JwtService.JwtValidacaoException e) {
      // Token inválido: limpa o contexto e deixa o AuthenticationEntryPoint retornar 401.
      SecurityContextHolder.clearContext();
    }

    chain.doFilter(request, response);
  }
}
