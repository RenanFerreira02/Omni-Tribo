package com.omnitribo.compartilhado.infra;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
// Ativa @PreAuthorize / @PostAuthorize nos controllers — garante autorização por papel.
@EnableMethodSecurity
public class SecurityConfig {

  private final JwtService jwtService;
  private final RateLimitFilter rateLimitFilter;

  @Value("${app.cors.origens-permitidas}")
  private String origensPermitidas;

  // EI_EXPOSE_REP2: RateLimitFilter é singleton Spring — não será mutado pelo chamador.
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public SecurityConfig(JwtService jwtService, RateLimitFilter rateLimitFilter) {
    this.jwtService = jwtService;
    this.rateLimitFilter = rateLimitFilter;
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        // CSRF desabilitado: API stateless com Bearer token em Authorization header.
        // Browsers não enviam Authorization automaticamente (diferente de cookies de sessão),
        // portanto o vetor CSRF não existe. OWASP CSRF Prevention §3.5 (token-based mitigation).
        .csrf(AbstractHttpConfigurer::disable)

        // Stateless: nenhuma HttpSession é criada. Cada request carrega credenciais via JWT.
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

        // CORS: origens explícitas; nunca wildcard (ver corsConfigSource()).
        .cors(c -> c.configurationSource(corsConfigSource()))

        // Autorização por endpoint.
        // Apenas os endpoints sem sessão prévia são públicos. /me e /logout exigem token válido.
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/api/v1/auth/login",
                        "/api/v1/auth/registrar",
                        "/api/v1/auth/refresh",
                        "/api/v1/ping", // health check
                        "/v3/api-docs/**", // OpenAPI schema
                        "/swagger-ui/**", // Swagger UI
                        "/api/v1/webhooks/**" // HMAC próprio implementado na F10
                        )
                    .permitAll()
                    .anyRequest()
                    .authenticated())

        // Filtros customizados — instanciados aqui, não como @Component, para evitar
        // que o Spring Boot os registre também como filtros de servlet (dupla execução).
        // Ordem: RateLimit → Jwt (rate limiting bloqueia antes de qualquer parse de token).
        .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(new JwtAuthFilter(jwtService), RateLimitFilter.class)

        // Handlers customizados: retornam ProblemDetail (RFC 9457) em vez do HTML padrão.
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(this::entryPoint401)
                    .accessDeniedHandler(this::handler403))

        // Headers de segurança
        .headers(
            h ->
                h
                    // HSTS: força HTTPS por 1 ano em todos os subdomínios.
                    // Só ativo em produção com TLS real; em dev o browser ignora HTTP.
                    .httpStrictTransportSecurity(
                        hsts -> hsts.maxAgeInSeconds(31536000).includeSubDomains(true))
                    // X-Frame-Options DENY: impede clickjacking via iframe.
                    .frameOptions(f -> f.deny())
                    // X-Content-Type-Options nosniff: impede MIME sniffing pelo browser.
                    .contentTypeOptions(c -> {})
                    // Referrer-Policy: não vaza a URL da API nos cabeçalhos Referer externos.
                    .referrerPolicy(
                        r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                    // CSP: API pura — nenhum recurso externo, nenhum frame, nenhum form.
                    // Em produção com frontend servido pelo mesmo domínio, ajustar default-src.
                    .contentSecurityPolicy(
                        csp ->
                            csp.policyDirectives(
                                "default-src 'none'; frame-ancestors 'none'; form-action 'none'")));

    return http.build();
  }

  /** CORS: lista explícita. Wildcard ('*') anularia a proteção same-origin em browsers. */
  @Bean
  CorsConfigurationSource corsConfigSource() {
    CorsConfiguration config = new CorsConfiguration();
    List<String> origens = Arrays.asList(origensPermitidas.split(","));
    config.setAllowedOrigins(origens);
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(
        List.of("Authorization", "Content-Type", "X-Correlation-Id", "X-Requested-With"));
    config.setExposedHeaders(List.of("X-Correlation-Id"));
    config.setAllowCredentials(false); // Bearer token — sem cookies de sessão
    config.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }

  /** 401 em ProblemDetail — chamado quando não há autenticação válida. */
  private void entryPoint401(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
      throws IOException {
    escreverProblemDetail(
        response, HttpStatus.UNAUTHORIZED, "Autenticação necessária", request.getRequestURI());
  }

  /** 403 em ProblemDetail — chamado quando a autenticação existe mas a autorização falha. */
  private void handler403(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
      throws IOException {
    escreverProblemDetail(response, HttpStatus.FORBIDDEN, "Acesso negado", request.getRequestURI());
  }

  private void escreverProblemDetail(
      HttpServletResponse response, HttpStatus status, String detalhe, String instancia)
      throws IOException {
    // Escreve ProblemDetail (RFC 9457) diretamente: AuthenticationEntryPoint e
    // AccessDeniedHandler executam no filtro, antes do DispatcherServlet, onde ObjectMapper
    // pode não estar disponível no ciclo de inicialização. Autocontido por design.
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    String uri = instancia.replace("\"", "");
    response
        .getWriter()
        .write(
            String.format(
                "{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d"
                    + ",\"detail\":\"%s\",\"instance\":\"%s\"}",
                status.getReasonPhrase(), status.value(), detalhe.replace("\"", "'"), uri));
  }
}
