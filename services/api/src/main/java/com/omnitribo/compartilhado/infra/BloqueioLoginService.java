package com.omnitribo.compartilhado.infra;

import com.omnitribo.compartilhado.api.AuditoriaPersistencia;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Controla rate limiting e bloqueio progressivo de tentativas de login.
 *
 * <p>Dois mecanismos independentes: 1. Bucket4j token-bucket: 5 tokens/min por chave IP+email —
 * bloqueia burst de credential stuffing. 2. BloqueioInfo counter: 10 falhas em 15 min → bloqueia
 * por 15 min — para brute-force mais lento.
 *
 * <p>In-memory — single instance MVP. Em múltiplas instâncias, migrar para Bucket4j + Redis
 * (bucket4j-redis module). Ver CLAUDE.md seção Escopo.
 */
@Component
public class BloqueioLoginService {

  private final AuditoriaPersistencia auditoriaPersistencia;

  @Value("${app.rate-limit.login-por-minuto:5}")
  private int loginPorMinuto;

  @Value("${app.rate-limit.falhas-para-bloqueio:10}")
  private int falhasParaBloqueio;

  @Value("${app.rate-limit.janela-falhas-minutos:15}")
  private int janelaFalhasMinutos;

  @Value("${app.rate-limit.duracao-bloqueio-minutos:15}")
  private int duracaoBloqueioMinutos;

  // Mapas com chave = sha256(ip:email) para não expor PII em estruturas de memória.
  private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, BloqueioInfo> bloqueios = new ConcurrentHashMap<>();

  public BloqueioLoginService(AuditoriaPersistencia auditoriaPersistencia) {
    this.auditoriaPersistencia = auditoriaPersistencia;
  }

  /**
   * Verifica se a tentativa de login está permitida.
   *
   * @return {@code null} se permitida; {@link BloqueioAtivo} com tempo restante se bloqueada
   */
  public BloqueioAtivo verificar(String ip, String email) {
    String chave = chave(ip, email);

    // 1. Verifica bloqueio progressivo (10 falhas em 15 min)
    BloqueioInfo info = bloqueios.get(chave);
    if (info != null && info.bloqueadoAte() != null) {
      Instant agora = Instant.now();
      if (agora.isBefore(info.bloqueadoAte())) {
        long segundosRestantes = Duration.between(agora, info.bloqueadoAte()).getSeconds();
        return new BloqueioAtivo(segundosRestantes);
      } else {
        // Bloqueio expirado: limpa o estado
        bloqueios.remove(chave);
      }
    }

    // 2. Verifica bucket Bucket4j (5/min)
    Bucket bucket = buckets.computeIfAbsent(chave, k -> criarBucket());
    if (!bucket.tryConsume(1)) {
      return new BloqueioAtivo(60L); // bucket tem janela de 1 minuto
    }

    return null; // Permitido
  }

  /** Registra falha de login. Se atingir o limiar, aplica bloqueio progressivo. */
  public void registrarFalha(String ip, String email) {
    String chave = chave(ip, email);
    Instant agora = Instant.now();

    bloqueios.merge(
        chave,
        new BloqueioInfo(1, agora, null),
        (existente, novo) -> {
          // Reseta contador se a última falha foi fora da janela de observação
          boolean dentroJanela =
              existente.ultimaFalha().isAfter(agora.minus(Duration.ofMinutes(janelaFalhasMinutos)));
          int novasFalhas = dentroJanela ? existente.falhas() + 1 : 1;
          Instant bloqueioAte =
              novasFalhas >= falhasParaBloqueio
                  ? agora.plus(Duration.ofMinutes(duracaoBloqueioMinutos))
                  : null;
          return new BloqueioInfo(novasFalhas, agora, bloqueioAte);
        });

    BloqueioInfo atualizado = bloqueios.get(chave);
    if (atualizado != null && atualizado.bloqueadoAte() != null) {
      // Registra evento de bloqueio na trilha de auditoria
      auditoriaPersistencia.gravar(
          null, // atorId null: ação anônima pré-autenticação
          "LOGIN_BLOQUEADO",
          "usuario",
          null,
          ip,
          null,
          MDC.get("correlationId"));
    }
  }

  /** Limpa o contador de falhas após login bem-sucedido. */
  public void registrarSucesso(String ip, String email) {
    bloqueios.remove(chave(ip, email));
  }

  private Bucket criarBucket() {
    // Token bucket: reabastece loginPorMinuto tokens a cada 60 segundos (janela deslizante).
    return Bucket.builder()
        .addLimit(
            Bandwidth.builder()
                .capacity(loginPorMinuto)
                .refillGreedy(loginPorMinuto, Duration.ofMinutes(1))
                .build())
        .build();
  }

  /** Chave é SHA-256(ip:email) — evita armazenar PII em plaintext na memória do processo. */
  private String chave(String ip, String email) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest((ip + ":" + email.toLowerCase()).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 não disponível", e);
    }
  }

  public record BloqueioAtivo(long segundosRestantes) {}

  record BloqueioInfo(int falhas, Instant ultimaFalha, Instant bloqueadoAte) {}
}
