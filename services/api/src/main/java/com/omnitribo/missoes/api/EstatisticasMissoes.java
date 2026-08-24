package com.omnitribo.missoes.api;

/**
 * Porta de leitura agregada sobre {@code missao}, para o painel de impacto (ADR 0029).
 *
 * <p>Nenhum parâmetro de identidade: quem é o usuário-sistema é conhecimento de {@code missoes} (a
 * constante {@code UsuarioSistema.ID}, que este módulo já importa para criar a missão de retirada).
 * Recebê-lo de fora deixaria o chamador escolher de quem são as missões contadas, e o painel
 * passaria a poder mentir sem que ninguém mudasse uma consulta.
 */
public interface EstatisticasMissoes {

  ResumoMissoesDoSistema resumoDoSistema();

  /**
   * Tokens parados em pote de missão, em QUALQUER estado.
   *
   * <p>Metade da conservação do ADR 0027: {@code SUM(carteira.saldo_tokens) +
   * SUM(missao.pote_tokens)}. Token em pote saiu da carteira de quem financiou e ainda não chegou
   * na de quem executa — ele existe, e omiti-lo do painel faria a circulação parecer menor do que
   * é.
   */
  long tokensEmPotes();
}
