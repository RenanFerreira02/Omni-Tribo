package com.omnitribo.compartilhado.infra;

import com.omnitribo.compartilhado.api.TipoProblema;
import com.omnitribo.identidade.api.ConsultaSessao;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
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

  /**
   * CSP do Swagger UI, mais permissiva e SÓ nos paths dele: com {@code default-src 'none'} a SPA
   * fica em branco. {@code 'unsafe-inline'} é o mínimo que o springdoc exige; em produção o
   * springdoc está desligado.
   */
  private static final String CSP_SWAGGER =
      "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
          + "img-src 'self' data:; font-src 'self' data:; connect-src 'self'; "
          + "frame-ancestors 'none'; form-action 'none'; base-uri 'self'";

  private final JwtService jwtService;
  private final RateLimitFilter rateLimitFilter;
  private final ConsultaSessao consultaSessao;
  private final ParametrosWebhook parametrosWebhook;

  @Value("${app.cors.origens-permitidas}")
  private String origensPermitidas;

  // EI_EXPOSE_REP2: RateLimitFilter é singleton Spring — não será mutado pelo chamador.
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public SecurityConfig(
      JwtService jwtService,
      RateLimitFilter rateLimitFilter,
      ConsultaSessao consultaSessao,
      ParametrosWebhook parametrosWebhook) {
    this.jwtService = jwtService;
    this.rateLimitFilter = rateLimitFilter;
    this.consultaSessao = consultaSessao;
    this.parametrosWebhook = parametrosWebhook;
  }

  /**
   * Cadeia da documentação (Swagger + OpenAPI), existente só pela CSP. {@code @Order(0)}: a
   * principal casa {@code anyRequest()} e venceria. Sem RateLimit nem JWT — asset estático anônimo.
   */
  @Bean
  @Order(0)
  public SecurityFilterChain swaggerFilterChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .headers(
            h ->
                h.frameOptions(f -> f.deny())
                    .contentTypeOptions(c -> {})
                    .referrerPolicy(
                        r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                    .contentSecurityPolicy(csp -> csp.policyDirectives(CSP_SWAGGER)));
    return http.build();
  }

  /**
   * Cadeia do Actuator na porta de gestão (8090): health check que exige JWT não é health check. Só
   * health e info são anônimos; metrics exige autenticação. {@code @Order(1)} pelo mesmo motivo.
   */
  @Bean
  @Order(1)
  public SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
    http.securityMatcher(EndpointRequest.toAnyEndpoint())
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(EndpointRequest.to(HealthEndpoint.class, InfoEndpoint.class))
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(this::entryPoint401)
                    .accessDeniedHandler(this::handler403));
    return http.build();
  }

  @Bean
  @Order(2)
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        // CSRF desligado: API stateless com Bearer — browser não envia Authorization sozinho.
        .csrf(AbstractHttpConfigurer::disable)

        // Stateless: nenhuma HttpSession é criada. Cada request carrega credenciais via JWT.
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

        // CORS: origens explícitas; nunca wildcard (ver corsConfigSource()).
        .cors(c -> c.configurationSource(corsConfigSource()))

        // Só os endpoints sem sessão prévia são públicos. Swagger fica na cadeia própria acima.
        .authorizeHttpRequests(
            auth ->
                // Webhooks: permitAll NÃO é rota aberta — quem autentica é o HmacWebhookFilter, por
                // assinatura no corpo bruto, antes de qualquer controller. Tirar o filtro sem tirar
                // esta linha deixa a rota anônima e gravável.
                auth.requestMatchers(
                        "/api/v1/auth/login",
                        "/api/v1/auth/registrar",
                        "/api/v1/auth/refresh",
                        "/api/v1/webhooks/**", // autenticado por HMAC, ver HmacWebhookFilter
                        "/api/v1/ping" // health check
                        )
                    .permitAll()
                    .anyRequest()
                    .authenticated())

        // Filtros instanciados aqui, e não como @Component, para não executarem duas vezes.
        // Ordem: Hmac → RateLimit → Jwt. O HMAC tem teto por transportadora (no balde por IP uma
        // transportadora em laço esgotaria as outras). O rateLimitFilter é registrado primeiro
        // porque
        // addFilterBefore exige que o filtro de referência já tenha ordem.
        .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(new HmacWebhookFilter(parametrosWebhook), RateLimitFilter.class)
        .addFilterAfter(new JwtAuthFilter(jwtService, consultaSessao), RateLimitFilter.class)

        // Handlers customizados: retornam ProblemDetail (RFC 9457) em vez do HTML padrão.
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(this::entryPoint401)
                    .accessDeniedHandler(this::handler403))

        // Headers de segurança
        .headers(
            h ->
                h
                    // HSTS: HTTPS por 1 ano, inclusive subdomínios (só tem efeito com TLS real).
                    .httpStrictTransportSecurity(
                        hsts -> hsts.maxAgeInSeconds(31536000).includeSubDomains(true))
                    // X-Frame-Options DENY: impede clickjacking via iframe.
                    .frameOptions(f -> f.deny())
                    // X-Content-Type-Options nosniff: impede MIME sniffing pelo browser.
                    .contentTypeOptions(c -> {})
                    // Referrer-Policy: não vaza a URL da API nos cabeçalhos Referer externos.
                    .referrerPolicy(
                        r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                    // CSP: API pura — nenhum recurso externo, frame ou form.
                    .contentSecurityPolicy(
                        csp ->
                            csp.policyDirectives(
                                "default-src 'none'; frame-ancestors 'none'; form-action 'none'")));

    return http.build();
  }

  /**
   * CORS com lista explícita (wildcard anularia a same-origin). O {@code trim()} existe porque
   * "a.com, b.com" produzia " b.com", que nunca casa. Origem vazia é descartada.
   */
  @Bean
  CorsConfigurationSource corsConfigSource() {
    CorsConfiguration config = new CorsConfiguration();
    List<String> origens =
        Arrays.stream(origensPermitidas.split(","))
            .map(String::trim)
            .filter(o -> !o.isEmpty())
            .toList();
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
    // ProblemDetail escrito à mão: estes handlers rodam no filtro, antes do DispatcherServlet.
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    // UTF-8 explícito: sem ele o servlet usa ISO-8859-1 e "Autenticação" chega corrompido no 401.
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    String uri = instancia.replace("\"", "");
    // Mesmo catálogo de type do GlobalExceptionHandler: 401/403 do filtro não saem como
    // about:blank.
    response
        .getWriter()
        .write(
            String.format(
                "{\"type\":\"%s\",\"title\":\"%s\",\"status\":%d"
                    + ",\"detail\":\"%s\",\"instance\":\"%s\"}",
                TipoProblema.deStatus(status),
                status.getReasonPhrase(),
                status.value(),
                detalhe.replace("\"", "'"),
                uri));
  }
}
