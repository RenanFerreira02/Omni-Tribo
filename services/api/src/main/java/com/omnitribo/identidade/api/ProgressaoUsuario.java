package com.omnitribo.identidade.api;

import java.util.Collection;
import java.util.List;
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

  /**
   * Nível atual do usuário, DERIVADO do XP por {@code RegraNivel}.
   *
   * <p>Derivado, e não lido da coluna {@code usuario.nivel}: aquela coluna é cache recalculado a
   * cada concessão de XP, e uma missão só pode ser barrada por um número que seja função pura do XP
   * — senão um cache defasado negaria acesso a quem já tem o XP necessário, e o usuário não teria
   * nada a fazer a respeito. É a mesma correção que a exportação LGPD recebeu em 2026-08-11.
   *
   * @throws com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException se o usuário não
   *     existe.
   */
  int nivelDe(UUID usuarioId);

  /**
   * Dos usuários dados, quais atingem o nível mínimo. Uma consulta só.
   *
   * <p>Existe para o fan-out de notificação, onde chamar {@link #nivelDe} por candidato daria uma
   * ida ao banco por membro da tribo. Como o nível é função pura do XP, o filtro vira uma
   * comparação de {@code xp} contra o limiar do nível — nenhuma linha precisa ser trazida para a
   * memória.
   *
   * <p>Anunciar missão a quem não pode aceitá-la seria prometer o que o servidor recusa com 422 no
   * toque seguinte, então este filtro é parte da regra de notificação e não otimização.
   *
   * @return subconjunto de {@code usuarioIds}; lista vazia se a entrada for vazia.
   */
  List<UUID> filtrarPorNivelMinimo(Collection<UUID> usuarioIds, int nivelMinimo);
}
