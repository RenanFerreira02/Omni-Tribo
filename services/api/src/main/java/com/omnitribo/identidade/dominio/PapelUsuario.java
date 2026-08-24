package com.omnitribo.identidade.dominio;

public enum PapelUsuario {
  USUARIO,
  ADMIN,

  /**
   * Financiador do pote das missões de retirada. Titular de carteira, nunca operador.
   *
   * <p>É um PAPEL e não um tipo de titular separado porque a carteira identifica o dono por {@code
   * usuario_id} com FK e {@code uk_carteira_usuario} (V5) — todo o módulo {@code carteira},
   * incluindo a ordem de lock e a resolução {@code usuarioId → carteiraId}, continua valendo sem
   * uma linha de mudança. Ver ADR 0024 §4, que registra o titular polimórfico como alternativa
   * descartada.
   *
   * <p>A conta nasce {@code StatusUsuario.INATIVO} e NUNCA autentica: {@code AutenticacaoService}
   * recusa qualquer status diferente de ATIVO, então nenhuma senha casa e nenhum token é emitido.
   * Mesmo molde do usuário-sistema da V21 — não é backdoor, é alvo de chave estrangeira.
   *
   * <p><b>Não confere autorização nenhuma.</b> Nenhum {@code hasRole('PATROCINADOR')} existe no
   * projeto, e não deve passar a existir: quem opera em nome do patrocinador é um ADMIN, pelos
   * endpoints de {@code /api/v1/admin/patrocinadores}.
   */
  PATROCINADOR
}
