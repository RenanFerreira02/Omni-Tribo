package com.omnitribo.carteira.api;

/**
 * Porta de leitura agregada sobre o ledger e as carteiras, para o painel de impacto (ADR 0029).
 *
 * <p>Não confundir com {@code ReconciliacaoService}: aquele compara ledger contra projeção e
 * responde se o sistema está ÍNTEGRO; este soma para responder QUANTO circula. Um pode passar
 * enquanto o outro mostra número ruim — são perguntas diferentes sobre a mesma tabela.
 */
public interface EstatisticasToken {

  /** Aportado, resgatado e o total parado em carteira — numa única statement. */
  ResumoToken resumo();
}
