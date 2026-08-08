package com.omnitribo.compartilhado.infra;

import com.omnitribo.compartilhado.api.TipoProblema;
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

  /**
   * Cadeia exclusiva dos endpoints do Actuator, na porta de gestão (8081).
   *
   * <p>Sem ela, o {@code anyRequest().authenticated()} da cadeia principal alcançava também a porta
   * de gestão e {@code GET /actuator/health} respondia 401 — um health check que exige JWT não
   * serve como health check: nem Docker, nem orquestrador, nem pessoa em plantão têm token. O
   * {@code show-details: when-authorized} do application.yml também ficava sem sentido, já que não
   * existia caminho anônimo para ele diferenciar.
   *
   * <p><b>Só health e info são anônimos.</b> {@code metrics} continua exigindo autenticação: expõe
   * contadores de uso, nomes de endpoint e tamanho de pool, que é reconhecimento barato para quem
   * está sondando. E {@code when-authorized} garante que o anônimo veja apenas {@code
   * {"status":"UP"}}, sem o estado do banco — o perfil dev sobrescreve para {@code always}, o que é
   * aceitável só porque a 8081 não é publicada fora da máquina.
   *
   * <p>{@code @Order(1)} é obrigatório: a cadeia principal casa {@code anyRequest()} e venceria no
   * empate, tornando esta inalcançável.
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
                        // O atalho /swagger-ui.html não casa com /swagger-ui/** e voltava 401
                        // antes de chegar ao redirect para /swagger-ui/index.html.
                        "/swagger-ui.html",
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
    // Obrigatório, e não cosmético: sem isto o servlet cai no default ISO-8859-1 e o corpo sai
    // com "Autenticação" em Latin-1 dentro de um application/problem+json. JSON é UTF-8 por
    // definição (RFC 8259 §8.1), então o cliente decodifica lixo — e 401 é o erro que o app mais
    // recebe, porque todo access token expira em 15 min.
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    String uri = instancia.replace("\"", "");
    // Mesmo catálogo de type do GlobalExceptionHandler. Sem isto, 401 e 403 vindos da cadeia de
    // filtros sairiam como about:blank enquanto os do controller sairiam tipados — o cliente veria
    // dois contratos de erro diferentes para a mesma situação, dependendo de onde ela foi
    // detectada.
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
