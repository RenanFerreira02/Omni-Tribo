package com.omnitribo.missoes.dominio;

/** Tipos gravados na trilha append-only missao_evento. Espelha o CHECK de V11. */
public enum TipoMissaoEvento {
  PUBLICADA,
  ACEITA,
  DESISTIDA,
  INICIADA,
  CHECK_IN_REGISTRADO,
  CHECK_IN_REJEITADO,
  CONFIRMADA,
  CONTESTADA,
  DISPUTA_RESOLVIDA,
  // CONCLUIDA fica reservada para F7 registrar a conclusão com crédito efetivado em carteira.
  CONCLUIDA,
  CANCELADA,
  EXPIRADA
}
