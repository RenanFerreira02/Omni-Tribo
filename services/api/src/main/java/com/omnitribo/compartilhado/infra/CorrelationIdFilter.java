package com.omnitribo.compartilhado.infra;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Propaga um identificador de correlação por requisição, aceitando o do cliente quando ele é
 * utilizável.
 *
 * <p><b>O header é entrada não confiável e passa por allowlist antes de chegar ao MDC.</b> Aceitar
 * qualquer coisa custava duas falhas concretas:
 *
 * <ul>
 *   <li><b>Forja de log.</b> O pattern de console é {@code [%X{correlationId}]}, então CR/LF no
 *       header fabrica linhas de log inteiras — inclusive linhas falsas de bloqueio de login,
 *       contra quem for reconstituir um incidente depois.
 *   <li><b>500 sem autenticação.</b> {@code auditoria.correlation_id} era {@code VARCHAR(36)} e
 *       ninguém truncava; um valor mais longo estourava com SQLState 22001 durante o flush da
 *       transação de login, e o login inteiro respondia 500 em vez de 200 ou 401. Não exigia nem
 *       má-fé: um {@code traceparent} do W3C tem 55 caracteres.
 * </ul>
 *
 * <p>O formato aceito é o dos identificadores que a gente de fato quer correlacionar — UUID, {@code
 * traceparent}, ids de APM — e o teto de 64 casa com a coluna alargada pela V20. Valor fora disso
 * não é rejeitado com erro: seria transformar telemetria malformada em falha de requisição. Gera-se
 * um id novo, e o cliente perde só a correlação que ele mesmo estragou.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

  private static final String HEADER = "X-Correlation-Id";
  private static final String MDC_KEY = "correlationId";

  /** Sem espaço, sem controle, sem CR/LF. Cobre UUID, traceparent e ids de APM. */
  private static final Pattern ACEITAVEL = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    String correlationId = sanitizar(request.getHeader(HEADER));

    MDC.put(MDC_KEY, correlationId);
    response.setHeader(HEADER, correlationId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  private static String sanitizar(String recebido) {
    if (recebido != null && ACEITAVEL.matcher(recebido).matches()) {
      return recebido;
    }
    return UUID.randomUUID().toString();
  }
}
