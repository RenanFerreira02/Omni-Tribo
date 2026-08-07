package com.omnitribo.identidade.dominio;

/**
 * Converte XP acumulado em nível.
 *
 * <p>Curva quadrática: o nível N começa em {@code (N-1)² × 100} de XP. Nível 2 em 100, nível 3 em
 * 400, nível 4 em 900, nível 5 em 1600. A escolha é deliberada — uma curva LINEAR daria nível
 * infinito a quem só acumula tempo, e uma EXPONENCIAL tornaria o segundo nível inalcançável para
 * quem acabou de entrar. A quadrática dá progresso rápido no começo, quando o usuário está
 * decidindo se o app vale a pena, e desacelera depois.
 *
 * <p>Puro e sem Spring, no mesmo molde de {@code MissaoStateMachine}: é regra de domínio, testável
 * sem subir contexto nenhum.
 *
 * <p>Monotônica por construção — {@code sqrt} é crescente e XP nunca diminui (ADR 0004: XP não tem
 * ledger nem estorno). Logo o nível também nunca diminui, e não existe caminho por onde um usuário
 * perca nível.
 */
public final class RegraNivel {

  /** XP que separa dois níveis consecutivos no início da curva. */
  private static final double XP_POR_DEGRAU = 100.0;

  private RegraNivel() {}

  /**
   * Nível correspondente ao XP acumulado. Nível mínimo é 1, inclusive para XP zero — usuário novo
   * nasce no nível 1, nunca no 0.
   */
  public static int nivelPara(long xp) {
    if (xp < 0) {
      throw new IllegalArgumentException("XP não pode ser negativo.");
    }
    return 1 + (int) Math.floor(Math.sqrt(xp / XP_POR_DEGRAU));
  }

  /**
   * XP necessário para alcançar um nível. Inverso de {@link #nivelPara}, usado na UI de progresso.
   */
  public static long xpParaNivel(int nivel) {
    if (nivel < 1) {
      throw new IllegalArgumentException("Nível mínimo é 1.");
    }
    long degraus = nivel - 1L;
    return (long) (degraus * degraus * XP_POR_DEGRAU);
  }
}
