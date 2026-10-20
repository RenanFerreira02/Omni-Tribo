package com.omnitribo.compartilhado.dominio;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Deriva a chave gravada em {@code lancamento.chave_idempotencia} (ADR 0008).
 *
 * <p>Nunca a chave crua do cliente: a UNIQUE é GLOBAL, e o {@code Idempotency-Key: 1} de um cliente
 * receberia o REPLAY da transação de um estranho. O hash de ator + operação + chave torna a colisão
 * impossível. VALOR e DESTINATÁRIO ficam fora de propósito: retry com a mesma chave é replay da
 * operação original, nunca uma operação nova com valor maior.
 */
public final class ChaveIdempotencia {

  private static final String SEPARADOR = "|";

  private ChaveIdempotencia() {}

  /** Conclusão: sem chave de cliente — a mesma missão concluída duas vezes é o mesmo evento. */
  public static String conclusaoMissao(UUID missaoId, UUID executorId) {
    return derivar("conclusao", missaoId.toString(), executorId.toString());
  }

  /**
   * Perna de débito da transferência: duas linhas no ledger, mesmo material com prefixos distintos.
   */
  public static String transferenciaEnviada(UUID remetenteId, String chaveDoCliente) {
    return derivar("transferencia-enviada", remetenteId.toString(), chaveDoCliente);
  }

  /** Perna de crédito da transferência. Par de {@link #transferenciaEnviada}. */
  public static String transferenciaRecebida(UUID remetenteId, String chaveDoCliente) {
    return derivar("transferencia-recebida", remetenteId.toString(), chaveDoCliente);
  }

  /**
   * Resgate, que QUEIMA token: um retry que queimasse duas vezes não apareceria na reconciliação. O
   * benefício não entra no material — resgatar duas vezes de propósito é o cliente mandar duas
   * chaves.
   */
  public static String resgate(UUID usuarioId, String chaveDoCliente) {
    return derivar("resgate", usuarioId.toString(), chaveDoCliente);
  }

  public static String saque(UUID usuarioId, String chaveDoCliente) {
    return derivar("saque", usuarioId.toString(), chaveDoCliente);
  }

  /** Financiamento: a missão entra no material porque é identidade do recurso, não parâmetro. */
  public static String financiamento(UUID financiadorId, UUID missaoId, String chaveDoCliente) {
    return derivar("financiamento", financiadorId.toString(), missaoId.toString(), chaveDoCliente);
  }

  /**
   * Financiamento pelo PATROCINADOR, na conversão: o webhook é idempotente por (transportadora,
   * rastreio), então a chave natural é missão + patrocinador — existe para a UNIQUE e o rastreio.
   */
  public static String financiamentoPatrocinador(UUID patrocinadorId, UUID missaoId) {
    return derivar("financiamento-patrocinador", patrocinadorId.toString(), missaoId.toString());
  }

  /**
   * Aporte, a única EMISSÃO de token: COM chave de cliente, porque um retry que emitisse duas vezes
   * não apareceria na reconciliação.
   */
  public static String aportePatrocinador(UUID patrocinadorUsuarioId, String chaveDoCliente) {
    return derivar("aporte-patrocinador", patrocinadorUsuarioId.toString(), chaveDoCliente);
  }

  /** Estorno do pote: missão + carteira, idempotente mesmo se o job reprocessar a missão. */
  public static String estornoFinanciamento(UUID missaoId, UUID carteiraFinanciadorId) {
    return derivar("estorno-financiamento", missaoId.toString(), carteiraFinanciadorId.toString());
  }

  private static String derivar(String operacao, String... partes) {
    String material = operacao + SEPARADOR + String.join(SEPARADOR, partes);
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
      // 64 chars em hex — cabe folgado no VARCHAR(100) da coluna.
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 é obrigatório em toda JVM (JLS/JCA). Se faltar, a instalação está quebrada.
      throw new IllegalStateException("SHA-256 indisponível na JVM", e);
    }
  }
}
