package com.omnitribo.compartilhado.infra;

import com.omnitribo.identidade.api.AutenticadoPrincipal;
import com.omnitribo.identidade.api.ConsultaSessao;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
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
  private final ConsultaSessao consultaSessao;

  public JwtAuthFilter(JwtService jwtService, ConsultaSessao consultaSessao) {
    this.jwtService = jwtService;
    this.consultaSessao = consultaSessao;
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

      // SEGUNDA metade da autenticação: a assinatura prova que o token foi emitido por nós; esta
      // consulta prova que a conta ainda pode agir AGORA. Sem ela, uma conta anonimizada seguia
      // escrevendo pelos 15 min de TTL do token (medido: POST /missoes respondia 201 com criadorId
      // do usuário já apagado), e um ADMIN rebaixado no banco continuava ADMIN pelo mesmo prazo.
      //
      // O principal vem do BANCO, não dos claims — é o que faz `papel` e `email` serem
      // reconferidos. Montá-lo de `claims` aqui devolveria a autoridade do momento da EMISSÃO e
      // deixaria metade do defeito de pé. Custo: uma leitura por PK por usuário por minuto, servida
      // do cache de ConsultaSessaoService.
      Optional<AutenticadoPrincipal> sessao = consultaSessao.sessaoAtiva(usuarioId);
      if (sessao.isEmpty()) {
        // Conta inexistente, inativa ou anonimizada: mesmo desfecho de token inválido. Distinguir
        // os casos contaria a quem porta um token roubado o que houve com a conta.
        SecurityContextHolder.clearContext();
        chain.doFilter(request, response);
        return;
      }
      AutenticadoPrincipal principal = sessao.get();

      // ROLE_ é convenção do Spring Security. A authority sai do principal (e portanto do banco),
      // nunca do claim `papel`.
      var auth =
          new UsernamePasswordAuthenticationToken(
              principal, null, List.of(new SimpleGrantedAuthority(principal.autoridade())));
      auth.setDetails(request);

      SecurityContextHolder.getContext().setAuthentication(auth);
    } catch (JwtService.JwtValidacaoException e) {
      // Token inválido: limpa o contexto e deixa o AuthenticationEntryPoint retornar 401.
      SecurityContextHolder.clearContext();
    }

    chain.doFilter(request, response);
  }
}
