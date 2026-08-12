package com.omnitribo.missoes.dominio;

import java.time.Duration;
import java.util.List;

/**
 * O que a varredura procura, como DADO em vez de código.
 *
 * <p>Cada regra diz: neste status, passado este prazo desde a última transição, dispare este
 * evento. Acrescentar um estado à varredura passa a ser uma linha aqui mais uma transição em {@code
 * StatusMissao} — não um job novo, não um método novo no serviço.
 *
 * <p>Isso importa porque a alternativa já mostrou o custo: com o desenho anterior, cada estado
 * varrido seria uma consulta a mais DENTRO da mesma transação de lote, multiplicando por três a
 * superfície do deadlock e a do item envenenado que a reestruturação da expiração acabou de fechar.
 *
 * <p>{@code ABERTA} usa {@code janelaFim} e as demais usam {@code estadoDesde} — a diferença é
 * real, não acidental: a janela de oferta é um prazo ABSOLUTO escolhido pelo criador ("preciso
 * disso até sexta"), enquanto abandono e omissão são prazos RELATIVOS ao momento em que a missão
 * parou.
 */
public record RegraExpiracao(
    StatusMissao origem, EventoMissao evento, Duration prazo, Marco marco) {

  /** De onde sai o instante que o prazo conta. */
  public enum Marco {
    /** {@code janela_fim}: prazo absoluto da oferta, definido na criação. */
    JANELA_FIM,
    /** {@code estado_desde}: quando a missão entrou no status atual. */
    ESTADO_DESDE
  }

  /**
   * As três varreduras de hoje.
   *
   * <p>Os prazos de execução e confirmação são generosos de propósito. Errar para o lado curto tira
   * uma missão de quem está executando de boa-fé — trânsito, imprevisto, um dia ruim; errar para o
   * lado longo só adia a devolução de um pote que, sem esta varredura, ficaria preso para sempre.
   */
  public static List<RegraExpiracao> padrao(Duration prazoExecucao, Duration prazoConfirmacao) {
    return List.of(
        // Prazo ZERO: a janela de oferta já É o prazo. Assim que janelaFim passa, expira.
        new RegraExpiracao(
            StatusMissao.ABERTA, EventoMissao.EXPIRAR, Duration.ZERO, Marco.JANELA_FIM),
        new RegraExpiracao(
            StatusMissao.EM_ANDAMENTO,
            EventoMissao.EXPIRAR_EXECUCAO,
            prazoExecucao,
            Marco.ESTADO_DESDE),
        new RegraExpiracao(
            StatusMissao.AGUARDANDO_CONFIRMACAO,
            EventoMissao.EXPIRAR_CONFIRMACAO,
            prazoConfirmacao,
            Marco.ESTADO_DESDE));
  }
}
