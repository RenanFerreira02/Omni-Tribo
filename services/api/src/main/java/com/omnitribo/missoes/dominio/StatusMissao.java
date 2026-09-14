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
 * específico aparecer.</b> É a regra que faltava, e ela levou dois ADRs para valer de verdade: o
 * ADR 0015 a aplicou a {@code EM_ANDAMENTO} e {@code AGUARDANDO_CONFIRMACAO}, que eram os becos
 * conhecidos na época, e o <b>ADR 0034</b> a completou em {@code RASCUNHO}, {@code ACEITA} e {@code
 * EM_DISPUTA} — onde a única saída continuava sendo o criador ou o executor agir, e o pote ficava
 * preso sem que nem um ADMIN pudesse soltá-lo. Foi o diagnóstico do ADR 0032 que tornou essa lacuna
 * visível, e ela chegou a se materializar com 42 tokens presos no banco de demonstração.
 *
 * <p>Hoje os <b>seis</b> estados não-terminais satisfazem a regra, e <b>não pelo mesmo
 * mecanismo</b>: cinco têm porta de ADMIN ({@code DESTRAVAR}), e {@code ABERTA} sai pela varredura
 * de {@code janela_fim} — ela não tem porta de ADMIN de propósito, porque o prazo prometido ao
 * executor já é a saída. Três têm as duas coisas. Ao acrescentar um estado novo, garanta ao menos
 * UMA das duas — e note que varredura não é obrigatória: {@code RASCUNHO} e {@code ACEITA} não têm
 * prazo natural a medir, e inventar um apagaria trabalho legítimo em andamento.
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
    // Porta de ADMIN para o rascunho financiado cujo CRIADOR desapareceu. CANCELAR acima é dele;
    // se ele não volta, só o ADMIN solta o pote. Não há varredura aqui de propósito: rascunho é
    // trabalho em andamento e não tem prazo natural a medir — expirá-lo por tempo apagaria missão
    // que alguém está escrevendo. Ver ADR 0034.
    de(RASCUNHO, EventoMissao.DESTRAVAR, CANCELADA);

    de(ABERTA, EventoMissao.ACEITAR, ACEITA);
    de(ABERTA, EventoMissao.CANCELAR, CANCELADA);
    de(ABERTA, EventoMissao.EXPIRAR, EXPIRADA);

    de(ACEITA, EventoMissao.INICIAR, EM_ANDAMENTO);
    de(ACEITA, EventoMissao.DESISTIR, ABERTA);
    de(ACEITA, EventoMissao.CANCELAR, CANCELADA);
    // Mesmo beco: DESISTIR é do executor e CANCELAR é do criador. Se os DOIS somem, a missão aceita
    // fica parada com o pote preso e nem o ADMIN entrava. Sem varredura por prazo de propósito —
    // expirar ACEITA puniria o executor que aceitou e ainda não iniciou, que é exatamente a janela
    // que INICIAR existe para cobrir. Ver ADR 0034.
    de(ACEITA, EventoMissao.DESTRAVAR, CANCELADA);

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
    // A terceira saída de EM_DISPUTA. A diferença é SEMÂNTICA, não de efeito: esta e
    // RESOLVER_CANCELAR chegam a CANCELADA pelo mesmo estorno. RESOLVER_CANCELAR é julgamento de
    // mérito ("decidido contra o executor"); DESTRAVAR é desistência de julgar ("não há informação
    // para decidir; libere o pote"). Existe porque `resolver` OBRIGA a julgar, e há disputa sem
    // informação, com as duas partes ausentes. O que as separa é o tipo gravado na trilha mais o
    // texto da justificativa; o código não impede o ADMIN de escolher a errada, e o ADR 0034 aceita
    // isso como consequência negativa declarada.
    de(EM_DISPUTA, EventoMissao.DESTRAVAR, CANCELADA);
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
