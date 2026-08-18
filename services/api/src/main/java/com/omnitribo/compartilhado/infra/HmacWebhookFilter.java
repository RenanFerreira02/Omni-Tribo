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
 * Autentica o webhook de transportadora por HMAC-SHA256 sobre o CORPO BRUTO.
 *
 * <p>Este filtro é a única razão pela qual {@code /api/v1/webhooks/**} pode voltar ao {@code
 * permitAll} do {@link SecurityConfig}. O comentário que guardava aquela lista era explícito: o
 * webhook "nasce autenticado e a isenção é reintroduzida JUNTO com o HMAC, não antes". Remover este
 * filtro sem remover a isenção deixa a rota anônima e gravável por qualquer um.
 *
 * <h2>Por que o corpo BRUTO</h2>
 *
 * <p>A assinatura cobre os bytes que chegaram no fio, não o objeto desserializado. Assinar o objeto
 * reserializado compararia o resultado de uma normalização nossa — ordem de chaves, espaços,
 * precisão de número, escapes Unicode — com uma assinatura que a transportadora calculou sobre o
 * texto dela. Qualquer divergência de serialização vira 401 intermitente, e a "correção" natural é
 * afrouxar a comparação, que é como uma verificação de integridade deixa de verificar.
 *
 * <p>Por isso a requisição é bufferizada aqui e repassada adiante por {@link CorpoBufferizado}: ler
 * o {@code InputStream} e devolvê-lo intacto ao controller é a única forma de fazer os dois.
 *
 * <h2>O que é assinado</h2>
 *
 * <p>{@code HMAC-SHA256(segredo, timestamp + "." + corpo)}. O carimbo de tempo entra DENTRO do
 * material assinado, não ao lado: fora dele o atacante trocaria o cabeçalho por um instante atual e
 * reenviaria o corpo com a assinatura original, e a janela de 5 minutos não protegeria nada.
 *
 * <p>A comparação é {@link MessageDigest#isEqual}, em tempo constante. Comparar com {@code equals}
 * de String sai no primeiro byte diferente, e a diferença de tempo é medível pela rede — dá para
 * reconstruir a assinatura byte a byte sem nunca conhecer o segredo.
 *
 * <h2>Por que toda falha é o mesmo 401</h2>
 *
 * <p>Segredo desconhecido, assinatura errada, carimbo fora da janela e cabeçalho ausente respondem
 * exatamente igual, com {@link TipoProblema#NAO_AUTENTICADO}. É a mesma doutrina de {@code
 * ConsultaSessao} e do 401 de login: distinguir as causas conta a quem não tem o segredo qual etapa
 * ele já venceu. "Timestamp velho" avisaria que a assinatura estava certa.
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

    // Transportadora desconhecida sai ANTES de qualquer HMAC: sem isso, um slug inventado custaria
    // um SHA-256 por requisição e a rota anônima viraria amplificador de CPU.
    if (segredo.isEmpty()) {
      recusar(request, response, "transportadora não integrada");
      return;
    }

    // Teto por transportadora. Quem passou daqui é identificado, então o balde é por slug e não por
    // IP — uma transportadora com problema de retry não derruba a integração das outras.
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

    // O slug VERIFICADO é publicado como atributo. O controller lê daqui e nunca de
    // request.getHeader(...): o cabeçalho é a alegação, a assinatura é a prova, e ler a alegação
    // sem a prova deixaria a transportadora A gravar encomendas em nome da B.
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

  /**
   * {@code HMAC-SHA256(segredo, timestamp + "." + corpo)}. Público para o mock e para os testes.
   */
  public static byte[] calcular(String segredo, String timestamp, byte[] corpo) {
    try {
      Mac mac = Mac.getInstance(ALGORITMO);
      mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), ALGORITMO));
      mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
      mac.update((byte) '.');
      mac.update(corpo);
      return mac.doFinal();
    } catch (java.security.GeneralSecurityException e) {
      // HmacSHA256 é obrigatório em toda JVM (JCA standard names); chegar aqui é defeito de
      // ambiente, não requisição ruim.
      throw new IllegalStateException("HMAC-SHA256 indisponível na JVM", e);
    }
  }

  private void recusar(HttpServletRequest request, HttpServletResponse response, String motivo)
      throws IOException {
    // O motivo vai para o LOG, nunca para o corpo — é o que separa diagnosticar de dar oráculo.
    // Sem payload e sem cabeçalho: a regra do CLAUDE.md proíbe logar corpo de requisição, e aqui o
    // corpo é dado de terceiro sob custódia.
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

  /**
   * ProblemDetail escrito à mão: este filtro roda antes do DispatcherServlet, então o {@code
   * GlobalExceptionHandler} não o alcança. O {@code setCharacterEncoding} é obrigatório — sem ele o
   * servlet cai em ISO-8859-1 e "requisições" sai em Latin-1 dentro de um JSON, que é UTF-8 por
   * definição (RFC 8259 §8.1).
   */
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
   * Devolve o corpo já lido, quantas vezes for preciso.
   *
   * <p>{@code ContentCachingRequestWrapper} não serve aqui: ele guarda o que foi lido para inspeção
   * POSTERIOR, e quem consome o stream primeiro deixa o controller com EOF. Como a verificação
   * precisa acontecer ANTES de qualquer desserialização, o corpo é bufferizado e reapresentado.
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
