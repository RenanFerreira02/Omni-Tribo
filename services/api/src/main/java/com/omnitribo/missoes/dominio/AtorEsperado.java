package com.omnitribo.missoes.dominio;

/** Quem tem autoridade para disparar cada {@link EventoMissao}. */
public enum AtorEsperado {
  /** Quem criou a missão. */
  CRIADOR,
  /** Quem já foi vinculado como executor. */
  EXECUTOR,
  /** Qualquer usuário autenticado que não seja o criador — impede autonegócio no aceite. */
  CANDIDATO,
  /** Somente papel ADMIN. */
  ADMIN,
  /** O próprio sistema (job agendado). Inalcançável por requisição HTTP. */
  SISTEMA
}
