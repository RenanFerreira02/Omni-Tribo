package com.omnitribo.missoes.dominio;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Estados do agregado Missao e as transições permitidas entre eles.
 *
 * <p>A tabela de transições vive aqui, e não numa classe separada, para que "quais estados existem"
 * e "como se sai de cada um" sejam lidos no mesmo arquivo — a máquina de estados não tem como
 * divergir da enumeração.
 *
 * <p>Regra econômica central do produto: só CONCLUIDA autoriza crédito em carteira. O protótipo
 * descartado creditava no aceite, permitindo aceitar e nunca executar com o dinheiro já pago.
 *
 * <p>CONCLUIDA, CANCELADA e EXPIRADA são terminais: ausentes do mapa de propósito.
 */
public enum StatusMissao {
  RASCUNHO,
  ABERTA,
  ACEITA,
  EM_ANDAMENTO,
  AGUARDANDO_CONFIRMACAO,
  EM_DISPUTA,
  CONCLUIDA,
  CANCELADA,
  EXPIRADA;

  private static final Map<StatusMissao, Map<EventoMissao, StatusMissao>> TRANSICOES =
      new EnumMap<>(StatusMissao.class);

  // Por que bloco static e não construtor: o construtor de um enum não pode referenciar outras
  // constantes do próprio enum — elas ainda não foram inicializadas nesse momento (illegal forward
  // reference). O bloco static roda depois de todas as constantes existirem.
  static {
    de(RASCUNHO, EventoMissao.PUBLICAR, ABERTA);

    de(ABERTA, EventoMissao.ACEITAR, ACEITA);
    de(ABERTA, EventoMissao.CANCELAR, CANCELADA);
    de(ABERTA, EventoMissao.EXPIRAR, EXPIRADA);

    de(ACEITA, EventoMissao.INICIAR, EM_ANDAMENTO);
    de(ACEITA, EventoMissao.DESISTIR, ABERTA);
    de(ACEITA, EventoMissao.CANCELAR, CANCELADA);

    de(EM_ANDAMENTO, EventoMissao.CHECKIN, AGUARDANDO_CONFIRMACAO);

    de(AGUARDANDO_CONFIRMACAO, EventoMissao.CONFIRMAR, CONCLUIDA);
    de(AGUARDANDO_CONFIRMACAO, EventoMissao.CONTESTAR, EM_DISPUTA);

    de(EM_DISPUTA, EventoMissao.RESOLVER_CONCLUIR, CONCLUIDA);
    de(EM_DISPUTA, EventoMissao.RESOLVER_CANCELAR, CANCELADA);
  }

  private static void de(StatusMissao origem, EventoMissao evento, StatusMissao destino) {
    TRANSICOES.computeIfAbsent(origem, s -> new EnumMap<>(EventoMissao.class)).put(evento, destino);
  }

  /** Destino da transição; vazio se o evento não é permitido a partir deste status. */
  public Optional<StatusMissao> destinoDe(EventoMissao evento) {
    return Optional.ofNullable(TRANSICOES.getOrDefault(this, Map.of()).get(evento));
  }

  public boolean permite(EventoMissao evento) {
    return destinoDe(evento).isPresent();
  }

  /** Estado terminal: nenhuma transição parte dele. */
  public boolean ehTerminal() {
    return !TRANSICOES.containsKey(this);
  }

  /** Cópia imutável — o mapa interno de transições nunca escapa. */
  public Set<EventoMissao> eventosPermitidos() {
    return Set.copyOf(TRANSICOES.getOrDefault(this, Map.of()).keySet());
  }
}
