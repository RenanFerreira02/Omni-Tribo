package com.omnitribo.identidade.api;

import java.util.UUID;

/**
 * Porta pública de identidade para concessão de XP e recálculo de nível.
 *
 * <p>XP mora em {@code identidade}, e não em {@code carteira}, porque não é dinheiro: o ADR 0004 é
 * explícito que XP é reputação, não transferível, monotônica e SEM ledger — um contador em {@code
 * usuario.xp}. Pôr isso na carteira faria o módulo financeiro carregar uma moeda que não tem
 * lançamento, não tem estorno e não tem saldo a conciliar.
 */
public interface ProgressaoUsuario {

  /**
   * Soma XP e recalcula o nível, na transação do chamador.
   *
   * <p>Monotônica: quantidade negativa é recusada. XP não tem estorno — cancelar uma missão não
   * retira reputação de quem já a executou.
   *
   * <p>Trava a linha de {@code usuario} com {@code PESSIMISTIC_WRITE}. Não é excesso: {@code
   * Usuario} tem {@code @Version}, e duas conclusões simultâneas do mesmo executor colidiriam no
   * flush com {@code ObjectOptimisticLockingFailureException} — que o handler global traduz em 409.
   * O usuário receberia um conflito por um crédito que JÁ foi gravado na mesma transação, e o
   * rollback desfaria os dois. Travar a linha transforma isso numa espera de microssegundos.
   *
   * <p>Ordem global de lock: {@code missao} → {@code carteira} → {@code usuario}. Esta é a última.
   */
  ResultadoProgressao concederXp(UUID usuarioId, long quantidade);
}
