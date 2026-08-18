package com.omnitribo.identidade.dominio;

/**
 * Estado da conta.
 *
 * <p>Espelhado num {@code CHECK} da V2. Nem toda constante é alcançável hoje — ver cada uma.
 */
public enum StatusUsuario {

  /** Único estado que o registro atribui, e o único que o login aceita. */
  ATIVO,

  /**
   * Atribuído pela anonimização LGPD ({@code ExclusaoContaService}), junto de {@code
   * anonimizado_em}.
   */
  INATIVO,

  /**
   * RESERVADO. Nada atribui — não existe endpoint nem job de moderação.
   *
   * <p>Passou a ser útil de verdade a partir do momento em que o filtro de autenticação consulta o
   * estado da conta a cada requisição: antes, suspender alguém no banco não teria efeito nenhum até
   * o próximo login. Falta só quem escreva o valor.
   */
  SUSPENSO,

  /**
   * RESERVADO. Mesma situação de {@link #SUSPENSO}, com a diferença de que banimento deveria ser
   * irreversível e por isso precisa de trilha de auditoria própria antes de existir.
   */
  BANIDO
}
