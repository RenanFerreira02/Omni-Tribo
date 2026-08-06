package com.omnitribo.missoes.dominio;

/**
 * Eventos que disparam transições na máquina de estados de Missao.
 *
 * <p>Cada evento carrega o ator autorizado a dispará-lo e o tipo gravado na trilha append-only.
 * Este enum nunca referencia {@link StatusMissao} — o destino de cada transição mora na tabela
 * dentro de StatusMissao — o que evita ciclo de inicialização estática entre os dois enums.
 */
public enum EventoMissao {
  PUBLICAR(AtorEsperado.CRIADOR, TipoMissaoEvento.PUBLICADA),
  ACEITAR(AtorEsperado.CANDIDATO, TipoMissaoEvento.ACEITA),
  INICIAR(AtorEsperado.EXECUTOR, TipoMissaoEvento.INICIADA),
  DESISTIR(AtorEsperado.EXECUTOR, TipoMissaoEvento.DESISTIDA),
  CANCELAR(AtorEsperado.CRIADOR, TipoMissaoEvento.CANCELADA),
  CHECKIN(AtorEsperado.EXECUTOR, TipoMissaoEvento.CHECK_IN_REGISTRADO),
  CONFIRMAR(AtorEsperado.CRIADOR, TipoMissaoEvento.CONFIRMADA),
  CONTESTAR(AtorEsperado.CRIADOR, TipoMissaoEvento.CONTESTADA),
  RESOLVER_CONCLUIR(AtorEsperado.ADMIN, TipoMissaoEvento.DISPUTA_RESOLVIDA),
  RESOLVER_CANCELAR(AtorEsperado.ADMIN, TipoMissaoEvento.DISPUTA_RESOLVIDA),
  EXPIRAR(AtorEsperado.SISTEMA, TipoMissaoEvento.EXPIRADA);

  private final AtorEsperado atorEsperado;
  private final TipoMissaoEvento tipoTrilha;

  EventoMissao(AtorEsperado atorEsperado, TipoMissaoEvento tipoTrilha) {
    this.atorEsperado = atorEsperado;
    this.tipoTrilha = tipoTrilha;
  }

  public AtorEsperado atorEsperado() {
    return atorEsperado;
  }

  public TipoMissaoEvento tipoTrilha() {
    return tipoTrilha;
  }
}
