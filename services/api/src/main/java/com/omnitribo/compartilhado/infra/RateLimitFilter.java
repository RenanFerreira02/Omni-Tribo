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
 * Rate limiting geral por usuário autenticado: 100 req/min escrita, 300 req/min leitura. Para
 * login, usa BloqueioLoginService (verificado no AutenticacaoService, não aqui). Executa ANTES do
 * JwtAuthFilter; portanto para endpoints autenticados o SecurityContext ainda está vazio — rate
 * limiting por usuário funciona apenas após o primeiro parse do JWT. Solução: o filter aplica rate
 * limit por userId quando o header Authorization está presente e o token é válido; caso contrário
 * aplica por IP.
 *
 * <p><b>Contagem em memória, e isso é uma decisão de escopo, não um esquecimento.</b> Os buckets
 * vivem em mapas locais do processo, o que só é correto porque o MVP roda em UMA instância. Com
 * duas ou mais atrás de um balanceador, cada uma passaria a contar o seu próprio tráfego e o limite
 * efetivo viraria N × 100/min — ou seja, o controle degradaria em silêncio, sem erro nenhum
 * aparecer. A migração é trocar estes mapas por um contador distribuído (o módulo {@code
 * bucket4j-redis} já cobre exatamente este caso, mantendo a mesma API de Bucket). Mesma observação
 * vale para {@code BloqueioLoginService}. Ver CLAUDE.md, seção Escopo.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

  private final JwtService jwtService;

  @Value("${app.rate-limit.escrita-por-minuto:100}")
  private int escritaPorMinuto;

  @Value("${app.rate-limit.leitura-por-minuto:300}")
  private int leituraPorMinuto;

  /**
   * Buckets por userId (autenticado) ou IP (anônimo), com DESPEJO por ociosidade.
   *
   * <p>Eram {@code ConcurrentHashMap} sem remoção nenhuma: toda chave já vista — cada IP anônimo,
   * cada usuário — permanecia até o processo reiniciar. Como a chave de quem não está autenticado é
   * o IP, e o IP sai de {@code X-Forwarded-For} quando presente (cabeçalho que o cliente controla),
   * bastava variar esse cabeçalho para inflar os mapas indefinidamente.
   *
   * <p>{@code expireAfterAccess} de 10 minutos não afrouxa o limite: {@code refillGreedy} repõe a
   * capacidade inteira a cada minuto, então um bucket parado há dez já está cheio e recriá-lo
   * produz exatamente o mesmo estado. Descartar entrada ociosa é equivalente, não aproximado.
   */
  private final Cache<String, Bucket> bucketsEscrita =
      Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(10)).build();

  private final Cache<String, Bucket> bucketsLeitura =
      Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(10)).build();

  public RateLimitFilter(JwtService jwtService) {
    this.jwtService = jwtService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    // Isenção por rota EXATA, não por prefixo /api/v1/auth/. Só /login sai daqui, porque só ele tem
    // controle próprio e mais fino no BloqueioLoginService (5/min por sha256(ip+email), com
    // bloqueio
    // progressivo).
    //
    // /refresh ERA isento pela mesma justificativa, e a justificativa era falsa: `refresh()` nunca
    // chama bloqueioLoginService — os três call sites do serviço estão todos dentro de `login()`.
    // O resultado é que /refresh era o ÚNICO endpoint público da API sem teto algum: sem
    // autenticação, cada chamada custa um SHA-256 e um lookup indexado, e nada limitava a taxa.
    // Adivinhar o token é inviável (256 bits), mas exaurir conexão e CPU não era.
    //
    // /registrar também não tinha nenhum: como cada chamada executa um hash Argon2id (19 MB de
    // memória e ~100 ms de CPU, por ADR 0005), a rota era um amplificador de DoS não autenticado —
    // o atacante gasta uma requisição, o servidor gasta 100 ms. Agora cai no bucket geral de
    // escrita, por IP, já que ainda não há token nessa altura.
    // /api/v1/webhooks/ é isento AQUI porque tem teto PRÓPRIO no HmacWebhookFilter, que roda antes
    // e já recusou quem não tem assinatura válida. Deixar cair no balde geral aplicaria um segundo
    // limite com a chave errada: sem JWT, a chave é o IP, e várias transportadoras atrás do mesmo
    // gateway dividiriam uma cota pensada para usuário final.
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
    // Tenta extrair userId do JWT para rate limiting por usuário autenticado.
    // Necessário porque este filtro executa ANTES do JwtAuthFilter popular o SecurityContext.
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ")) {
      try {
        var claims = jwtService.validar(header.substring(7));
        return "user:" + claims.getSubject();
      } catch (JwtService.JwtValidacaoException e) {
        // Token expirado ou malformado é o caso NORMAL — todo cliente passa por ele a cada 15 min.
        // Em debug, e não em warn, senão o log vira ruído e ninguém lê mais nenhum dos dois.
        log.debug("Token inválido no rate limit; caindo em bucket por IP: {}", e.getMessage());
      } catch (RuntimeException e) {
        // Qualquer OUTRA falha aqui é defeito de configuração, não requisição ruim — chave RSA
        // ilegível é o caso concreto. Antes isto era engolido junto com o caso normal, e o sintoma
        // era TODO MUNDO compartilhar silenciosamente o bucket do IP, sem uma linha de log.
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
    // Escreve ProblemDetail (RFC 9457) manualmente: filtros não têm acesso garantido ao
    // ObjectMapper do contexto Spring (que é autoconfigured). Evita dependência de ciclo
    // de inicialização e mantém o filtro autocontido.
    String uri = request.getRequestURI().replace("\"", "");
    String detail =
        "Limite de requisições atingido (" + limite + "/min). Aguarde antes de tentar novamente.";
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    // Mesma razão do SecurityConfig: o detail tem "requisições", e sem UTF-8 explícito o corpo
    // sai em ISO-8859-1 dentro de um JSON.
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
