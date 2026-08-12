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
   * CSP do Swagger UI — deliberadamente mais permissiva que a da API, e SÓ para os paths dele.
   *
   * <p>A CSP da API é {@code default-src 'none'}, que é a política certa para quem só devolve JSON
   * e é fatal para qualquer página. O Swagger UI é uma SPA servida pelo webjar: sem {@code
   * script-src}, {@code style-src}, {@code img-src} e {@code connect-src} o browser recusa o {@code
   * swagger-ui-bundle.js}, o CSS e o {@code fetch} de {@code /v3/api-docs} — o HTML chega com 200 e
   * a página fica EM BRANCO, sem nenhum erro do lado do servidor. O sintoma só aparece no console
   * do DevTools, como "Refused to load the script".
   *
   * <p>{@code 'unsafe-inline'} entra porque o próprio springdoc injeta a configuração da UI num
   * bloco inline; é o mínimo que ela exige para renderizar. O alcance disso é o menor possível: a
   * política vale apenas para os três paths do {@code securityMatcher} abaixo, que em produção nem
   * existem — {@code application-prod.yml} desliga o springdoc, e a cadeia passa a responder 404
   * com um header relaxado sobre nada.
   */
  private static final String CSP_SWAGGER =
      "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
          + "img-src 'self' data:; font-src 'self' data:; connect-src 'self'; "
          + "frame-ancestors 'none'; form-action 'none'; base-uri 'self'";

  private final JwtService jwtService;
  private final RateLimitFilter rateLimitFilter;
  private final ConsultaSessao consultaSessao;

  @Value("${app.cors.origens-permitidas}")
  private String origensPermitidas;

  // EI_EXPOSE_REP2: RateLimitFilter é singleton Spring — não será mutado pelo chamador.
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public SecurityConfig(
      JwtService jwtService, RateLimitFilter rateLimitFilter, ConsultaSessao consultaSessao) {
    this.jwtService = jwtService;
    this.rateLimitFilter = rateLimitFilter;
    this.consultaSessao = consultaSessao;
  }

  /**
   * Cadeia exclusiva da documentação interativa (Swagger UI + schema OpenAPI).
   *
   * <p>Existe por UM motivo: a CSP. Autorização e rate limit já liberavam esses paths — eles estão
   * no {@code permitAll} da cadeia principal e o {@code RateLimitFilter} os isenta por prefixo. O
   * que derrubava a página era o {@code default-src 'none'} da cadeia principal, que casa {@code
   * anyRequest()} e portanto alcançava também o Swagger. Ver {@link #CSP_SWAGGER}.
   *
   * <p>{@code @Order(0)} pelo mesmo motivo que o Actuator é {@code @Order(1)}: a cadeia principal
   * casa {@code anyRequest()} e venceria o empate, tornando esta inalcançável.
   *
   * <p>{@code RateLimitFilter} e {@code JwtAuthFilter} ficam de fora de propósito. São custo por
   * requisição e superfície de código sem função nenhuma sobre asset estático anônimo — e o rate
   * limit já tratava esses paths como isentos, então nada muda de comportamento.
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
   * Cadeia exclusiva dos endpoints do Actuator, na porta de gestão (8090).
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
   * aceitável só porque a 8090 não é publicada fora da máquina.
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
        //
        // Swagger UI e /v3/api-docs NÃO aparecem aqui: são tratados inteiros pelo
        // swaggerFilterChain, que roda antes por causa da CSP. Duplicá-los nesta lista daria a
        // impressão de que esta cadeia os atende, e alguém removeria a outra achando redundante.
        .authorizeHttpRequests(
            auth ->
                // `/api/v1/webhooks/**` SAIU desta lista. Era o único `/**` da cadeia, e o
                // comentário ao lado afirmava que o HMAC "foi implementado na F10" — não foi:
                // não existe controller de webhook nem HMAC em lugar nenhum do código. Enquanto
                // não existe controller o comportamento é 404 dos dois jeitos; a diferença é que,
                // com o matcher aqui, o primeiro controller de webhook nasceria ANÔNIMO, e o
                // comentário garantiria que o revisor não desconfiasse. Quando o webhook chegar,
                // ele nasce autenticado e a isenção é reintroduzida JUNTO com o HMAC, não antes.
                auth.requestMatchers(
                        "/api/v1/auth/login",
                        "/api/v1/auth/registrar",
                        "/api/v1/auth/refresh",
                        "/api/v1/ping" // health check
                        )
                    .permitAll()
                    .anyRequest()
                    .authenticated())

        // Filtros customizados — instanciados aqui, não como @Component, para evitar
        // que o Spring Boot os registre também como filtros de servlet (dupla execução).
        // Ordem: RateLimit → Jwt (rate limiting bloqueia antes de qualquer parse de token).
        .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
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

  /**
   * CORS: lista explícita. Wildcard ({@code *}) anularia a proteção same-origin em browsers.
   *
   * <p>O {@code trim()} não é cosmético. {@code CORS_ORIGENS="https://a.com, https://b.com"} —
   * escrito com o espaço que qualquer pessoa põe depois da vírgula — produzia a origem literal
   * {@code " https://b.com"}, que jamais casa com o header {@code Origin}. E a falha só aparece no
   * console do browser: o servidor responde 200 e não loga nada.
   *
   * <p>Origem vazia é descartada em vez de virar entrada inútil na lista, para que uma vírgula
   * sobrando no fim da variável não produza uma origem {@code ""}.
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
