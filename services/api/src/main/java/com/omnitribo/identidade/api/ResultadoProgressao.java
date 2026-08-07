package com.omnitribo.identidade.api;

/**
 * Saída de {@link ProgressaoUsuario#concederXp}.
 *
 * <p>Devolve o nível ANTES e DEPOIS em vez de só um booleano de "subiu": quem chama precisa do
 * número para o evento de notificação, e recalcular a partir do XP no chamador duplicaria a regra
 * de nivelamento fora do módulo que é dono dela.
 */
public record ResultadoProgressao(long xpTotal, int nivelAnterior, int nivelAtual) {

  public boolean subiuDeNivel() {
    return nivelAtual > nivelAnterior;
  }
}
