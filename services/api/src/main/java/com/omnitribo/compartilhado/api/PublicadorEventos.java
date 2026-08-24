package com.omnitribo.compartilhado.api;

import java.util.Map;
import java.util.UUID;

/**
 * Registra um evento de domínio para entrega assíncrona — o lado de escrita do Transactional
 * Outbox.
 *
 * <h2>Por que a outbox existe</h2>
 *
 * Notificar de dentro da transação NÃO é atômico com ela, e não existe ordem que resolva isso:
 *
 * <ul>
 *   <li>Disparar o push ANTES do commit anuncia um fato que o rollback pode desfazer. O usuário
 *       recebe "sua missão foi concluída, R$ 18,00 creditados" e o saldo não mudou.
 *   <li>Disparar DEPOIS do commit perde o anúncio se a entrega falhar — e ela falha, porque é rede.
 *       O crédito aconteceu e ninguém foi avisado.
 * </ul>
 *
 * <p>São dois sistemas sem transação em comum, então nenhuma ordenação os torna atômicos. A outbox
 * move a decisão para dentro do único lugar onde a atomicidade de fato existe: a MESMA transação do
 * banco grava o FATO (o lançamento) e a INTENÇÃO de anunciá-lo (a linha na outbox). Se a transação
 * some, a intenção some junto; se ela commita, a intenção está durável e um processo separado tenta
 * entregá-la.
 *
 * <p>Isso dispensa broker de mensageria — o Kafka/RabbitMQ que o escopo do MVP cortou de propósito
 * (CLAUDE.md, seção Escopo). O consumidor precisa tolerar receber o mesmo evento duas vezes, porque
 * a entrega não é exactly-once. Para o despachante desta fase, que grava alerta, isso é aceitável.
 *
 * <h2>O LIMITE desta garantia — leia antes de confiar nela</h2>
 *
 * <p><b>A entrega é limitada, não é at-least-once.</b> {@code DrenadorOutboxService} tenta no
 * máximo {@code app.outbox.maximo-tentativas} vezes (5 hoje); depois disso o predicado do lote
 * ({@code OutboxRepository.buscarPendentesParaPublicar}) deixa de enxergar a linha e o evento
 * <b>nunca mais é tentado</b>. Ele fica na tabela, com {@code publicado_em} nulo e {@code
 * ultimo_erro} preenchido, e <b>nada o mostra</b>: não há consulta de esgotados, não há endpoint de
 * administração, não há métrica. O único vestígio é o {@code log.warn} da última falha.
 *
 * <p>Na prática: um {@code MissaoConcluida} que o despachante não consiga tratar cinco vezes some,
 * o executor recebeu o crédito e nunca é avisado, e não existe lugar onde isso apareça. Este
 * javadoc já prometeu "retry até conseguir" e entrega "at-least-once" — as duas afirmações eram
 * falsas, e foram corrigidas em 2026-08-20 (ver docs/auditoria/varredura-orfaos.md §1.1 e a
 * Pendência #4 do CLAUDE.md). A carta-morta visível continua NÃO existindo.
 *
 * <h2>Por que é uma interface</h2>
 *
 * {@code compartilhado} é isento da regra do ArchUnit, então a interface não é exigida pela
 * arquitetura. Ela existe por uma razão de testabilidade, e vale dizer isso em vez de fingir motivo
 * arquitetural: é a costura ÚNICA que {@code ConclusaoRollbackTest} substitui por
 * {@code @MockitoBean} para forçar uma exceção depois do INSERT no ledger e antes do commit. Sem
 * uma interface, provar a atomicidade exigiria um seam de produção — uma flag de falha no código
 * real, que é exatamente o que não se deve fazer.
 */
public interface PublicadorEventos {

  /**
   * Grava o evento na outbox NA TRANSAÇÃO DO CHAMADOR.
   *
   * <p>{@code MANDATORY}, e não {@code REQUIRED}: chamar isto fora de transação é erro de
   * programação. Com {@code REQUIRED} o método abriria transação própria e commitaria a intenção
   * independentemente do fato — reintroduzindo, por dentro, exatamente a não-atomicidade que a
   * outbox existe para eliminar. Falhar alto na chamada é o comportamento correto.
   *
   * @param payload serializado para {@code jsonb}; deve conter só tipos que o mapper resolve sem
   *     configuração (String, número, boolean, UUID, Instant, Map, List)
   */
  void publicar(String tipoEvento, UUID agregadoId, Map<String, Object> payload);
}
