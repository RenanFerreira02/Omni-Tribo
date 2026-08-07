package com.omnitribo.compartilhado.dominio;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Deriva a chave gravada em {@code lancamento.chave_idempotencia}.
 *
 * <p>O valor armazenado NUNCA é a chave que o cliente enviou. {@code uk_lancamento_idempotencia} é
 * uma UNIQUE de coluna única e de namespace GLOBAL — vale para todas as carteiras e todos os tipos
 * de operação ao mesmo tempo. Guardar a chave crua faria o {@code Idempotency-Key: 1} de um cliente
 * colidir com o {@code 1} de todos os outros, e o segundo chamador não receberia erro: receberia o
 * REPLAY DA TRANSAÇÃO DE UM ESTRANHO. Isso é vazamento entre usuários e perda silenciosa de uma
 * operação na mesma linha de código. Hashear ator + operação + chave do cliente torna a colisão
 * impossível por construção, sem precisar de UNIQUE composta.
 *
 * <p>Mesma decisão e mesmo raciocínio de {@code V12__checkin_idempotencia.sql} na fase de
 * geolocalização, que guarda {@code sha256(usuario_id|missao_id|chave_do_cliente)}.
 *
 * <p>O que entra no material, e o que fica de fora de propósito:
 *
 * <ul>
 *   <li>O ATOR entra — é o que separa o namespace de um usuário do de outro.
 *   <li>A OPERAÇÃO entra — sem ela, a mesma chave de cliente num saque e numa transferência
 *       colidiriam, e o saque viraria um no-op respondendo 200 sem ter movido nada.
 *   <li>VALOR e DESTINATÁRIO ficam FORA. É a semântica correta de idempotência: retry com a mesma
 *       chave é replay da operação original, não uma operação nova, independentemente do que o
 *       corpo diga. Também fecha o buraco de "reenviar a mesma chave com valor maior cria uma
 *       segunda transferência".
 * </ul>
 *
 * <p>As chaves cruas do seed ({@code seed-idem-bob-e1-brl}) convivem sem risco: um hex de 64
 * caracteres nunca é igual a uma delas, então nenhuma migration de dados é necessária.
 *
 * <p>Utilitário estático sem Spring, no mesmo molde de {@link Coordenadas} e {@code
 * MissaoStateMachine}: existe em um lugar só para que nenhum call site reinvente a derivação.
 */
public final class ChaveIdempotencia {

  private static final String SEPARADOR = "|";

  private ChaveIdempotencia() {}

  /**
   * Conclusão de missão. Sem chave de cliente, de propósito.
   *
   * <p>{@code POST /missoes/{id}/confirmar} não tem header {@code Idempotency-Key}, e a
   * idempotência precisa valer inclusive para um cliente que não envia nenhum. A chave natural é a
   * própria missão: "a mesma missão concluída duas vezes" é o mesmo evento, não dois. Como {@code
   * CONCLUIDA} é estado terminal, idempotente-por-missão é exatamente a semântica desejada.
   */
  public static String conclusaoMissao(UUID missaoId, UUID executorId) {
    return derivar("conclusao", missaoId.toString(), executorId.toString());
  }

  /**
   * Perna de débito da transferência P2P.
   *
   * <p>Uma transferência é UMA operação lógica que precisa de DUAS linhas no ledger, e a UNIQUE é
   * de coluna única — duas linhas não podem carregar a mesma chave. As duas pernas derivam do MESMO
   * material do cliente com prefixos de operação diferentes: ficam distintas para o banco e
   * rastreavelmente ligadas para quem investiga.
   */
  public static String transferenciaEnviada(UUID remetenteId, String chaveDoCliente) {
    return derivar("transferencia-enviada", remetenteId.toString(), chaveDoCliente);
  }

  /** Perna de crédito da transferência. Par de {@link #transferenciaEnviada}. */
  public static String transferenciaRecebida(UUID remetenteId, String chaveDoCliente) {
    return derivar("transferencia-recebida", remetenteId.toString(), chaveDoCliente);
  }

  public static String saque(UUID usuarioId, String chaveDoCliente) {
    return derivar("saque", usuarioId.toString(), chaveDoCliente);
  }

  /**
   * Financiamento de missão. {@code missaoId} entra no material porque é identidade do recurso, não
   * parâmetro da operação: financiar a missão A e a missão B são operações diferentes, ainda que o
   * cliente reutilize a mesma chave por engano.
   */
  public static String financiamento(UUID financiadorId, UUID missaoId, String chaveDoCliente) {
    return derivar("financiamento", financiadorId.toString(), missaoId.toString(), chaveDoCliente);
  }

  /**
   * Estorno do pote para um financiador, no cancelamento ou expiração da missão.
   *
   * <p>Sem chave de cliente: o estorno é disparado pelo sistema (inclusive pelo job de expiração),
   * e a chave natural é o par missão + carteira estornada. Isso torna o estorno idempotente mesmo
   * se o lote de expiração reprocessar a mesma missão.
   */
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
