package com.omnitribo.compartilhado.infra;

import com.omnitribo.compartilhado.dominio.DrenadorOutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Gatilho do drenador de outbox. A regra mora em {@code DrenadorOutboxService}, no domínio; aqui só
 * o agendamento — o que mantém a regra testável sem depender do relógio do agendador.
 *
 * <p>Molde idêntico ao de {@code ExpiracaoMissoesJob}, inclusive na guarda explícita dentro do
 * método: {@code app.agendamento.habilitado} é {@code false} em teste, e um drenador varrendo a
 * outbox em paralelo marcaria eventos como publicados entre o arrange e o assert de outros testes.
 */
@Component
public class DrenadorOutboxJob {

  private static final Logger log = LoggerFactory.getLogger(DrenadorOutboxJob.class);

  private static final int TAMANHO_LOTE = 100;
  private static final int TETO_POR_EXECUCAO = 2_000;

  private final DrenadorOutboxService drenadorOutboxService;
  private final boolean habilitado;

  public DrenadorOutboxJob(
      DrenadorOutboxService drenadorOutboxService,
      @Value("${app.agendamento.habilitado:true}") boolean habilitado) {
    this.drenadorOutboxService = drenadorOutboxService;
    this.habilitado = habilitado;
  }

  @Scheduled(
      fixedDelayString = "${app.outbox.intervalo:PT10S}",
      initialDelayString = "${app.outbox.atraso-inicial:PT30S}")
  public void drenar() {
    if (!habilitado) {
      return;
    }

    int total = 0;
    int processados;
    // Lotes em transações independentes, com teto por execução: sem ele, um evento que sempre
    // reaparece no lote (por bug de predicado) viraria consumo de CPU indefinido.
    do {
      processados = drenadorOutboxService.drenarLote(TAMANHO_LOTE);
      total += processados;
    } while (processados == TAMANHO_LOTE && total < TETO_POR_EXECUCAO);

    if (total > 0) {
      log.info("Eventos de outbox processados nesta execução: {}", total);
    }
  }
}
