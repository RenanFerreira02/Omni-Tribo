package com.omnitribo.compartilhado.infra;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate limiting geral por usuário autenticado: 100 req/min escrita, 300 req/min leitura. Para
 * login, usa BloqueioLoginService (verificado no AutenticacaoService, não aqui). Executa ANTES do
 * JwtAuthFilter; portanto para endpoints autenticados o SecurityContext ainda está vazio — rate
 * limiting por usuário funciona apenas após o primeiro parse do JWT. Solução: o filter aplica rate
 * limit por userId quando o header Authorization está presente e o token é válido; caso contrário
 * aplica por IP.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

  private final JwtService jwtService;

  @Value("${app.rate-limit.escrita-por-minuto:100}")
  private int escritaPorMinuto;

  @Value("${app.rate-limit.leitura-por-minuto:300}")
  private int leituraPorMinuto;

  // Buckets por userId (autenticado) ou IP (anônimo)
  private final ConcurrentHashMap<String, Bucket> bucketsEscrita = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Bucket> bucketsLeitura = new ConcurrentHashMap<>();

  public RateLimitFilter(JwtService jwtService) {
    this.jwtService = jwtService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    // Isenção por rota EXATA, não por prefixo /api/v1/auth/. /login e /refresh têm controle próprio
    // e mais fino no BloqueioLoginService (5/min por sha256(ip+email), com bloqueio progressivo).
    // /registrar não tinha nenhum: como cada chamada executa um hash Argon2id (16 MB de memória e
    // ~100 ms de CPU, por ADR 0005), a rota era um amplificador de DoS não autenticado — o atacante
    // gasta uma requisição, o servidor gasta 100 ms. Agora cai no bucket geral de escrita, por IP,
    // já que ainda não há token nessa altura.
    String path = request.getRequestURI();
    if (path.equals("/api/v1/auth/login")
        || path.equals("/api/v1/auth/refresh")
        || path.startsWith("/v3/api-docs")
        || path.startsWith("/swagger-ui")
        || path.equals("/api/v1/ping")) {
      chain.doFilter(request, response);
      return;
    }

    String chave = resolverChave(request);
    boolean ehEscrita = ehMetodoEscrita(request.getMethod());

    Bucket bucket =
        ehEscrita
            ? bucketsEscrita.computeIfAbsent(chave, k -> criarBucket(escritaPorMinuto))
            : bucketsLeitura.computeIfAbsent(chave, k -> criarBucket(leituraPorMinuto));

    if (!bucket.tryConsume(1)) {
      int limite = ehEscrita ? escritaPorMinuto : leituraPorMinuto;
      responder429(response, request, 60L, limite);
      return;
    }

    chain.doFilter(request, response);
  }

  private String resolverChave(HttpServletRequest request) {
    // Tenta extrair userId do JWT para rate limiting por usuário autenticado.
    // Necessário porque este filtro executa ANTES do JwtAuthFilter popular o SecurityContext.
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ")) {
      try {
        var claims = jwtService.validar(header.substring(7));
        return "user:" + claims.getSubject();
      } catch (Exception ignored) {
        // Token inválido: cai no rate limit por IP
      }
    }
    return "ip:" + extrairIp(request);
  }

  private Bucket criarBucket(int capacidade) {
    return Bucket.builder()
        .addLimit(
            Bandwidth.builder()
                .capacity(capacidade)
                .refillGreedy(capacidade, Duration.ofMinutes(1))
                .build())
        .build();
  }

  private boolean ehMetodoEscrita(String method) {
    return "POST".equalsIgnoreCase(method)
        || "PUT".equalsIgnoreCase(method)
        || "PATCH".equalsIgnoreCase(method)
        || "DELETE".equalsIgnoreCase(method);
  }

  private String extrairIp(HttpServletRequest request) {
    String xff = request.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) {
      return xff.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }

  private void responder429(
      HttpServletResponse response, HttpServletRequest request, long retryAfter, int limite)
      throws IOException {
    // Escreve ProblemDetail (RFC 9457) manualmente: filtros não têm acesso garantido ao
    // ObjectMapper do contexto Spring (que é autoconfigured). Evita dependência de ciclo
    // de inicialização e mantém o filtro autocontido.
    String uri = request.getRequestURI().replace("\"", "");
    String detail =
        "Limite de requisições atingido (" + limite + "/min). Aguarde antes de tentar novamente.";
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    // Retry-After: informa ao cliente quanto tempo esperar (RFC 7231 §7.1.3).
    response.setHeader("Retry-After", String.valueOf(retryAfter));
    response
        .getWriter()
        .write(
            String.format(
                "{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429"
                    + ",\"detail\":\"%s\",\"instance\":\"%s\",\"retryAfter\":%d}",
                detail, uri, retryAfter));
  }
}
