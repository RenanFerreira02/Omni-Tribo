package com.omnitribo.compartilhado.infra;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.omnitribo.compartilhado.api.EnderecoDoCliente;
import com.omnitribo.compartilhado.api.TipoProblema;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate limit geral: 100 req/min de escrita e 300 de leitura, por usuário (do JWT, que este filtro
 * lê antes do JwtAuthFilter) ou por IP. Login tem controle próprio ({@code BloqueioLoginService}).
 *
 * <p>Contagem em memória — correto porque o MVP roda em UMA instância. Com várias, o limite viraria
 * N × 100/min em silêncio; a troca seria um contador distribuído ({@code bucket4j-redis}).
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

  /** Rota EXATA, não prefixo: só a busca tem teto próprio, e `/usuarios/me` não pode cair nele. */
  private static final String BUSCA_HANDLE = "/api/v1/usuarios/busca";

  private final JwtService jwtService;

  @Value("${app.rate-limit.escrita-por-minuto:100}")
  private int escritaPorMinuto;

  @Value("${app.rate-limit.leitura-por-minuto:300}")
  private int leituraPorMinuto;

  /**
   * Teto PRÓPRIO da busca por handle (ADR 0028): ela é oráculo de existência, e no balde de leitura
   * caberiam 18 mil consultas por hora. Doze por minuto é folgado para gente.
   */
  @Value("${app.rate-limit.busca-handle-por-minuto:12}")
  private int buscaHandlePorMinuto;

  /**
   * Buckets com despejo por ociosidade: sem remoção, variar o IP inflaria os mapas para sempre. O
   * despejo é equivalente, não aproximado — um bucket parado há 10 min já está cheio.
   */
  private final Cache<String, Bucket> bucketsEscrita =
      Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(10)).build();

  private final Cache<String, Bucket> bucketsLeitura =
      Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(10)).build();

  private final Cache<String, Bucket> bucketsBuscaHandle =
      Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(10)).build();

  public RateLimitFilter(JwtService jwtService) {
    this.jwtService = jwtService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    // Isenção por rota EXATA: só /login, que tem bloqueio progressivo próprio. /refresh e
    // /registrar
    // caem no balde geral — o registro custa um Argon2id (~100 ms), e sem teto seria amplificador
    // de
    // DoS. Webhooks têm teto próprio no HmacWebhookFilter, por transportadora, não por IP.
    String path = request.getRequestURI();
    if (path.equals("/api/v1/auth/login")
        || path.startsWith("/v3/api-docs")
        || path.startsWith("/swagger-ui")
        || path.startsWith("/api/v1/webhooks/")
        || path.equals("/api/v1/ping")) {
      chain.doFilter(request, response);
      return;
    }

    String chave = resolverChave(request);

    // Teto próprio SUBSTITUI o geral em vez de somar: uma varredura barrada não pode impedir a
    // pessoa
    // de abrir a carteira.
    if (BUSCA_HANDLE.equals(request.getRequestURI())) {
      Bucket balde = bucketsBuscaHandle.get(chave, k -> criarBucket(buscaHandlePorMinuto));
      if (!balde.tryConsume(1)) {
        responder429(response, request, 60L, buscaHandlePorMinuto);
        return;
      }
      chain.doFilter(request, response);
      return;
    }

    boolean ehEscrita = ehMetodoEscrita(request.getMethod());

    Bucket bucket =
        ehEscrita
            ? bucketsEscrita.get(chave, k -> criarBucket(escritaPorMinuto))
            : bucketsLeitura.get(chave, k -> criarBucket(leituraPorMinuto));

    if (!bucket.tryConsume(1)) {
      int limite = ehEscrita ? escritaPorMinuto : leituraPorMinuto;
      responder429(response, request, 60L, limite);
      return;
    }

    chain.doFilter(request, response);
  }

  private String resolverChave(HttpServletRequest request) {
    // userId do JWT: este filtro roda antes de o JwtAuthFilter preencher o SecurityContext.
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ")) {
      try {
        var claims = jwtService.validar(header.substring(7));
        return "user:" + claims.getSubject();
      } catch (JwtService.JwtValidacaoException e) {
        // Token expirado é o caso normal (a cada 15 min): debug, não warn.
        log.debug("Token inválido no rate limit; caindo em bucket por IP: {}", e.getMessage());
      } catch (RuntimeException e) {
        // Outra falha aqui é defeito de configuração (ex.: chave RSA ilegível) — precisa de log.
        log.error("Falha inesperada ao resolver identidade no rate limit", e);
      }
    }
    return "ip:" + EnderecoDoCliente.de(request);
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

  private void responder429(
      HttpServletResponse response, HttpServletRequest request, long retryAfter, int limite)
      throws IOException {
    // ProblemDetail escrito à mão: o filtro roda antes do DispatcherServlet.
    String uri = request.getRequestURI().replace("\"", "");
    String detail =
        "Limite de requisições atingido (" + limite + "/min). Aguarde antes de tentar novamente.";
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    // UTF-8 explícito, como no SecurityConfig.
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    // Retry-After: informa ao cliente quanto tempo esperar (RFC 7231 §7.1.3).
    response.setHeader("Retry-After", String.valueOf(retryAfter));
    response
        .getWriter()
        .write(
            String.format(
                "{\"type\":\"%s\",\"title\":\"Too Many Requests\",\"status\":429"
                    + ",\"detail\":\"%s\",\"instance\":\"%s\",\"retryAfter\":%d}",
                TipoProblema.LIMITE_REQUISICOES, detail, uri, retryAfter));
  }
}
