package com.omnitribo.missoes.dominio;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Estados da missão e as transições permitidas (ADR 0006) — tabela e enumeração no mesmo arquivo,
 * para não divergirem.
 *
 * <p>Só CONCLUIDA autoriza crédito. CONCLUIDA, CANCELADA e EXPIRADA são terminais.
 *
 * <p><b>Todo estado não-terminal precisa de uma saída que NÃO dependa de um humano específico
 * aparecer</b> (ADR 0015 e 0034): cinco têm porta de ADMIN ({@code DESTRAVAR}) e ABERTA sai pela
 * varredura de {@code janela_fim}. Estado novo precisa de ao menos uma das duas.
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

  // Bloco static: o construtor de um enum não pode referenciar as outras constantes.
  static {
    de(RASCUNHO, EventoMissao.PUBLICAR, ABERTA);
    // Rascunho financiado e abandonado prenderia o pote: cancelar estorna.
    de(RASCUNHO, EventoMissao.CANCELAR, CANCELADA);
    // Porta de ADMIN quando o criador some. Sem varredura: rascunho não tem prazo natural (ADR
    // 0034).
    de(RASCUNHO, EventoMissao.DESTRAVAR, CANCELADA);

    de(ABERTA, EventoMissao.ACEITAR, ACEITA);
    de(ABERTA, EventoMissao.CANCELAR, CANCELADA);
    de(ABERTA, EventoMissao.EXPIRAR, EXPIRADA);

    de(ACEITA, EventoMissao.INICIAR, EM_ANDAMENTO);
    de(ACEITA, EventoMissao.DESISTIR, ABERTA);
    de(ACEITA, EventoMissao.CANCELAR, CANCELADA);
    // Executor e criador sumiram: só o ADMIN solta o pote. Sem varredura — puniria quem aceitou e
    // ainda não iniciou (ADR 0034).
    de(ACEITA, EventoMissao.DESTRAVAR, CANCELADA);

    de(EM_ANDAMENTO, EventoMissao.CHECKIN, AGUARDANDO_CONFIRMACAO);
    // Sem estas saídas, o executor que abandona prenderia o pote para sempre (ADR 0015).
    de(EM_ANDAMENTO, EventoMissao.EXPIRAR_EXECUCAO, EXPIRADA);
    de(EM_ANDAMENTO, EventoMissao.DESTRAVAR, CANCELADA);

    de(AGUARDANDO_CONFIRMACAO, EventoMissao.CONFIRMAR, CONCLUIDA);
    de(AGUARDANDO_CONFIRMACAO, EventoMissao.CONTESTAR, EM_DISPUTA);
    // Criador sumiu depois do check-in: conclui PAGANDO o executor — o check-in é a evidência.
    de(AGUARDANDO_CONFIRMACAO, EventoMissao.EXPIRAR_CONFIRMACAO, CONCLUIDA);
    de(AGUARDANDO_CONFIRMACAO, EventoMissao.DESTRAVAR, CANCELADA);

    de(EM_DISPUTA, EventoMissao.RESOLVER_CONCLUIR, CONCLUIDA);
    de(EM_DISPUTA, EventoMissao.RESOLVER_CANCELAR, CANCELADA);
    // DESTRAVAR × RESOLVER_CANCELAR: mesmo efeito, semântica diferente — desistir de julgar versus
    // julgar contra o executor. Só a trilha e a justificativa as separam (ADR 0034).
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
