package com.omnitribo.identidade.dominio;

import java.util.List;

/**
 * Conquistas do usuário, DERIVADAS. Função pura, sem Spring e sem banco.
 *
 * <p><b>Por que derivar em vez de guardar.</b> Uma tabela {@code conquista} exigiria migration,
 * regra de concessão, e um caminho de escrita que pode falhar deixando o usuário sem a medalha que
 * já merece. Derivar de {@code xp}, {@code nivel} e {@code streak} — colunas que {@code usuario} já
 * tem — torna a concessão impossível de perder: se o XP está lá, a conquista está. O preço é não
 * existir "quando foi conquistada", e essa data não aparece em nenhuma tela pedida.
 *
 * <p><b>Só entram sinais que identidade POSSUI.</b> "Missões concluídas" seria a métrica óbvia e
 * está fora de propósito: a contagem vive em {@code missoes}, e buscá-la faria identidade depender
 * de missoes — que já depende de identidade. O ciclo custaria mais do que a medalha vale. XP é
 * proxy fiel, porque só conclusão de missão concede XP.
 *
 * <p><b>Mudou limiar no YAML? Suba a {@code versao} junto</b>, pela mesma razão de {@code
 * CalculadoraDeRecompensa}: sem isso, um usuário perde uma conquista que já tinha e ninguém
 * consegue explicar por quê. O teste dourado falha de propósito para forçar a decisão.
 *
 * <p>Devolve o catálogo INTEIRO, com o que falta para cada uma, e não só as já obtidas. É o que
 * permite à tela mostrar o próximo objetivo — uma lista só de conquistas ganhas não diz ao usuário
 * o que fazer em seguida.
 */
public final class CalculadoraDeConquistas {

  /** Limiares, todos configuráveis. A FÓRMULA é código; os NÚMEROS são configuração. */
  public record Calibracao(
      long xpIniciante,
      long xpVizinhoPresente,
      long xpPilarDaTribo,
      int nivelVeterano,
      int streakConstante,
      int versao) {}

  /**
   * @param progresso quanto o usuário tem hoje da métrica desta conquista
   * @param meta quanto é preciso — progresso ≥ meta é exatamente {@code conquistada}
   */
  public record Conquista(
      String codigo,
      String titulo,
      String descricao,
      boolean conquistada,
      long progresso,
      long meta) {}

  private CalculadoraDeConquistas() {}

  public static List<Conquista> avaliar(long xp, int nivel, int streak, Calibracao c) {
    return List.of(
        de(
            "INICIANTE",
            "Primeiro passo",
            "Conclua a primeira missão do bairro.",
            xp,
            c.xpIniciante()),
        de(
            "VIZINHO_PRESENTE",
            "Vizinho presente",
            "Acumule experiência ajudando por perto.",
            xp,
            c.xpVizinhoPresente()),
        de(
            "PILAR_DA_TRIBO",
            "Pilar da tribo",
            "Torne-se uma das pessoas mais atuantes da sua tribo.",
            xp,
            c.xpPilarDaTribo()),
        de(
            "VETERANO",
            "Veterano",
            "Alcance o nível " + c.nivelVeterano() + ".",
            nivel,
            c.nivelVeterano()),
        de(
            "CONSTANTE",
            "Constante",
            "Mantenha " + c.streakConstante() + " dias seguidos de atividade.",
            streak,
            c.streakConstante()));
  }

  /**
   * O progresso é SATURADO na meta antes de sair.
   *
   * <p>Sem isso, uma barra de progresso ingênua com 4000 de 2000 renderizaria o dobro da largura do
   * componente, e a tela mostraria "4000/2000" — que se lê como erro de cálculo, não como conquista
   * superada há tempos.
   */
  private static Conquista de(
      String codigo, String titulo, String descricao, long valor, long meta) {
    return new Conquista(codigo, titulo, descricao, valor >= meta, Math.min(valor, meta), meta);
  }
}
