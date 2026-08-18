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
 *
 * <p><b>Todo estado não-terminal precisa de pelo menos uma saída que NÃO dependa de um humano
 * específico aparecer.</b> É a regra que faltava: {@code EM_ANDAMENTO} e {@code
 * AGUARDANDO_CONFIRMACAO} só saíam por ação do executor ou do criador, e quando essa pessoa sumia o
 * pote ficava imobilizado para sempre — sem que nenhum ator, inclusive ADMIN, pudesse fazer algo.
 * Hoje os dois têm varredura por prazo (SISTEMA) e porta manual (ADMIN). Ao acrescentar um estado
 * novo, confira as duas.
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
    // Cancelar rascunho existe por causa do pote: missão comunitária é financiada ANTES de publicar
    // (a publicação exige pote cobrindo a recompensa), então sem esta saída um rascunho financiado
    // e abandonado prenderia os tokens dos financiadores para sempre — de RASCUNHO só se saía por
    // PUBLICAR. Com ela, o estorno de MissaoService.aplicar devolve o pote.
    de(RASCUNHO, EventoMissao.CANCELAR, CANCELADA);

    de(ABERTA, EventoMissao.ACEITAR, ACEITA);
    de(ABERTA, EventoMissao.CANCELAR, CANCELADA);
    de(ABERTA, EventoMissao.EXPIRAR, EXPIRADA);

    de(ACEITA, EventoMissao.INICIAR, EM_ANDAMENTO);
    de(ACEITA, EventoMissao.DESISTIR, ABERTA);
    de(ACEITA, EventoMissao.CANCELAR, CANCELADA);

    de(EM_ANDAMENTO, EventoMissao.CHECKIN, AGUARDANDO_CONFIRMACAO);
    // EM_ANDAMENTO tinha UMA saída — CHECKIN — e era um beco sem saída para todo mundo: nem o
    // executor que desistiu, nem o criador, nem um ADMIN tinham transição disponível. Um executor
    // que abandonava prendia o pote de uma missão TRIBO em custódia PARA SEMPRE, e a reconciliação
    // não acusava nada, porque ledger e projeção continuavam batendo (é a conservação que quebra,
    // não a reconciliação — são invariantes diferentes).
    de(EM_ANDAMENTO, EventoMissao.EXPIRAR_EXECUCAO, EXPIRADA);
    de(EM_ANDAMENTO, EventoMissao.DESTRAVAR, CANCELADA);

    de(AGUARDANDO_CONFIRMACAO, EventoMissao.CONFIRMAR, CONCLUIDA);
    de(AGUARDANDO_CONFIRMACAO, EventoMissao.CONTESTAR, EM_DISPUTA);
    // Mesmo beco, do outro lado: as duas saídas eram do CRIADOR, então o criador que sumia deixava
    // a missão parada indefinidamente — e nem o ADMIN podia entrar, porque EM_DISPUTA só se alcança
    // por CONTESTAR, que também é do criador.
    //
    // Vai para CONCLUIDA, PAGANDO o executor: houve check-in, e o check-in é a evidência. Ver o
    // javadoc de EventoMissao.EXPIRAR_CONFIRMACAO para a decisão e a alternativa descartada.
    de(AGUARDANDO_CONFIRMACAO, EventoMissao.EXPIRAR_CONFIRMACAO, CONCLUIDA);
    de(AGUARDANDO_CONFIRMACAO, EventoMissao.DESTRAVAR, CANCELADA);

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
