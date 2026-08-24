package com.omnitribo.logistica.dominio;

/**
 * Por que uma encomenda não virou missão. Coluna {@code entrega_falida.motivo_recusa} (V23).
 *
 * <p>Os dois casos compartilham {@code recusada_em} de propósito, e não ganharam colunas separadas:
 * para o resto do sistema eles são o MESMO fato — a encomenda não entrou na custódia, não ocupa
 * vaga e não tem missão. É isso que a invariante de ocupação de {@code MigracaoTest} e o {@code
 * ck_entrega_falida_recusada_sem_missao} da V21 verificam, e um terceiro estado paralelo obrigaria
 * a alterar os dois.
 *
 * <p>O que difere é só a EXPLICAÇÃO devolvida à transportadora, e ela é operacionalmente relevante:
 * um ponto lotado pode abrir vaga em horas, um patrocínio ausente não muda sozinho.
 */
public enum MotivoRecusa {

  /** Ponto de custódia sem vaga. Reenviar depois pode dar certo. */
  PONTO_LOTADO,

  /**
   * Sem patrocinador que financie o pote: inexistente, desativado, ou sem saldo.
   *
   * <p>As três causas colapsam num valor só de propósito. Distingui-las contaria à transportadora o
   * estado financeiro de um terceiro sem nenhum ganho operacional — ela precisa saber que reenviar
   * não adianta, não por quê. É a mesma doutrina do 401 único do {@code HmacWebhookFilter}.
   */
  SEM_PATROCINIO
}
