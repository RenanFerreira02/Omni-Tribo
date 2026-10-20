package com.omnitribo.compartilhado.infra;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.omnitribo.compartilhado.api.AtributosWebhook;
import com.omnitribo.compartilhado.api.EnderecoDoCliente;
import com.omnitribo.compartilhado.api.TipoProblema;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autentica o webhook de transportadora por HMAC-SHA256 sobre o CORPO BRUTO (ADR 0021) — é o que
 * permite {@code /api/v1/webhooks/**} estar no {@code permitAll}.
 *
 * <p>Bytes do fio, não o objeto reserializado: qualquer diferença de serialização viraria 401
 * intermitente. Por isso o corpo é bufferizado e repassado intacto ({@link CorpoBufferizado}).
 *
 * <p>Assina {@code timestamp + "." + corpo}: com o carimbo fora do material, o atacante trocaria o
 * cabeçalho e reenviaria. Comparação em tempo constante ({@link MessageDigest#isEqual}). Toda falha
 * é o mesmo 401 — distinguir causas diria a quem não tem o segredo qual etapa ele venceu.
 */
public class HmacWebhookFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(HmacWebhookFilter.class);

  /** Prefixo protegido. Casa exatamente com o matcher isento no {@link SecurityConfig}. */
  static final String PREFIXO = "/api/v1/webhooks/";

  static final String CABECALHO_TRANSPORTADORA = "X-Transportadora";
  static final String CABECALHO_ASSINATURA = "X-Assinatura";
  static final String CABECALHO_TIMESTAMP = "X-Timestamp";

  private static final String ALGORITMO = "HmacSHA256";

  private final ParametrosWebhook parametros;

  /** Mesma política do {@link RateLimitFilter}: despejo por ociosidade, reposição gulosa. */
  private final Cache<String, Bucket> buckets =
      Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(10)).build();

  public HmacWebhookFilter(ParametrosWebhook parametros) {
    this.parametros = parametros;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(PREFIXO);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    String slug = request.getHeader(CABECALHO_TRANSPORTADORA);
    Optional<String> segredo = parametros.segredoDe(slug);

    // Transportadora desconhecida sai antes do HMAC: senão um slug inventado custaria um SHA-256.
    if (segredo.isEmpty()) {
      recusar(request, response, "transportadora não integrada");
      return;
    }

    // Teto por transportadora: uma em laço de retry não derruba as outras.
    if (!balde(slug).tryConsume(1)) {
      responder429(request, response);
      return;
    }

    // Corpo lido UMA vez e bufferizado. Tem de vir antes da verificação e antes do controller.
    byte[] corpo = request.getInputStream().readAllBytes();

    String timestamp = request.getHeader(CABECALHO_TIMESTAMP);
    if (!janelaValida(timestamp)) {
      recusar(request, response, "carimbo de tempo ausente ou fora da janela");
      return;
    }

    if (!assinaturaConfere(
        segredo.get(), timestamp, corpo, request.getHeader(CABECALHO_ASSINATURA))) {
      recusar(request, response, "assinatura inválida");
      return;
    }

    // O slug VERIFICADO vira atributo; o controller nunca lê o cabeçalho cru — senão A gravaria em
    // nome de B.
    request.setAttribute(AtributosWebhook.TRANSPORTADORA, slug.toLowerCase(java.util.Locale.ROOT));
    chain.doFilter(new CorpoBufferizado(request, corpo), response);
  }

  private Bucket balde(String slug) {
    return buckets.get(
        slug.toLowerCase(java.util.Locale.ROOT),
        k ->
            Bucket.builder()
                .addLimit(
                    Bandwidth.builder()
                        .capacity(parametros.requisicoesPorMinuto())
                        .refillGreedy(parametros.requisicoesPorMinuto(), Duration.ofMinutes(1))
                        .build())
                .build());
  }

  /** Carimbo em segundos desde a época, tolerado para os DOIS lados (deriva de relógio). */
  private boolean janelaValida(String timestamp) {
    if (timestamp == null || timestamp.isBlank()) {
      return false;
    }
    try {
      Instant enviado = Instant.ofEpochSecond(Long.parseLong(timestamp.trim()));
      Duration diferenca = Duration.between(enviado, Instant.now()).abs();
      return diferenca.compareTo(parametros.janelaTimestamp()) <= 0;
    } catch (NumberFormatException | ArithmeticException e) {
      return false;
    }
  }

  private boolean assinaturaConfere(
      String segredo, String timestamp, byte[] corpo, String assinaturaRecebida) {
    if (assinaturaRecebida == null || assinaturaRecebida.isBlank()) {
      return false;
    }
    byte[] esperada = calcular(segredo, timestamp, corpo);
    byte[] recebida;
    try {
      recebida = HexFormat.of().parseHex(assinaturaRecebida.trim());
    } catch (IllegalArgumentException e) {
      // Hex malformado é recusa, não erro: um cliente mandando lixo não deve virar 500.
      return false;
    }
    // isEqual trata tamanhos diferentes sem sair mais cedo em função do conteúdo.
    return MessageDigest.isEqual(esperada, recebida);
  }

  /** {@code HMAC-SHA256(segredo, timestamp + "." + corpo)}. Público para o mock e os testes. */
  public static byte[] calcular(String segredo, String timestamp, byte[] corpo) {
    try {
      Mac mac = Mac.getInstance(ALGORITMO);
      mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), ALGORITMO));
      mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
      mac.update((byte) '.');
      mac.update(corpo);
      return mac.doFinal();
    } catch (java.security.GeneralSecurityException e) {
      // HmacSHA256 existe em toda JVM; chegar aqui é defeito de ambiente.
      throw new IllegalStateException("HMAC-SHA256 indisponível na JVM", e);
    }
  }

  private void recusar(HttpServletRequest request, HttpServletResponse response, String motivo)
      throws IOException {
    // O motivo vai para o LOG, nunca para o corpo. Sem payload: é dado de terceiro.
    log.warn(
        "Webhook recusado em {}: {} (origem {})",
        request.getRequestURI(),
        motivo,
        EnderecoDoCliente.de(request));
    escrever(
        response,
        HttpStatus.UNAUTHORIZED,
        TipoProblema.NAO_AUTENTICADO,
        "Assinatura do webhook inválida.",
        request.getRequestURI(),
        null);
  }

  private void responder429(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    response.setHeader("Retry-After", "60");
    escrever(
        response,
        HttpStatus.TOO_MANY_REQUESTS,
        TipoProblema.LIMITE_REQUISICOES,
        "Limite de requisições do webhook atingido ("
            + parametros.requisicoesPorMinuto()
            + "/min).",
        request.getRequestURI(),
        60L);
  }

  /** ProblemDetail à mão (antes do DispatcherServlet), com UTF-8 explícito. */
  private void escrever(
      HttpServletResponse response,
      HttpStatus status,
      java.net.URI tipo,
      String detalhe,
      String instancia,
      Long retryAfter)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    String uri = instancia.replace("\"", "");
    String extra = retryAfter == null ? "" : ",\"retryAfter\":" + retryAfter;
    response
        .getWriter()
        .write(
            String.format(
                "{\"type\":\"%s\",\"title\":\"%s\",\"status\":%d"
                    + ",\"detail\":\"%s\",\"instance\":\"%s\"%s}",
                tipo,
                status.getReasonPhrase(),
                status.value(),
                detalhe.replace("\"", "'"),
                uri,
                extra));
  }

  /**
   * Devolve o corpo já lido quantas vezes for preciso. {@code ContentCachingRequestWrapper} não
   * serve: guarda o lido para depois, e quem consome primeiro deixaria o controller com EOF.
   */
  private static final class CorpoBufferizado extends HttpServletRequestWrapper {

    private final byte[] corpo;

    private CorpoBufferizado(HttpServletRequest request, byte[] corpo) {
      super(request);
      this.corpo = corpo;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream fonte = new ByteArrayInputStream(corpo);
      return new ServletInputStream() {
        @Override
        public int read() {
          return fonte.read();
        }

        @Override
        public boolean isFinished() {
          return fonte.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("leitura assíncrona não suportada no webhook");
        }
      };
    }

    @Override
    public java.io.BufferedReader getReader() {
      return new java.io.BufferedReader(
          new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public int getContentLength() {
      return corpo.length;
    }

    @Override
    public long getContentLengthLong() {
      return corpo.length;
    }
  }
}
