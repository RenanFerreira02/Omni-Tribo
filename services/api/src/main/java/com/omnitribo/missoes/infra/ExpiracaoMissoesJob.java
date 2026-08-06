package com.omnitribo.missoes.infra;

import com.omnitribo.missoes.dominio.ExpiracaoMissoesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Gatilho da expiração de missões: ABERTA com janela vencida vira EXPIRADA.
 *
 * <p>A regra em si mora em ExpiracaoMissoesService, no domínio. Aqui fica só o agendamento — o que
 * mantém a regra testável sem depender do relógio do agendador.
 */
@Component
public class ExpiracaoMissoesJob {

  private static final Logger log = LoggerFactory.getLogger(ExpiracaoMissoesJob.class);

  private static final int TAMANHO_LOTE = 200;
  private static final int TETO_POR_EXECUCAO = 5_000;

  private final ExpiracaoMissoesService expiracaoMissoesService;
  private final boolean habilitado;

  public ExpiracaoMissoesJob(
      ExpiracaoMissoesService expiracaoMissoesService,
      @Value("${app.agendamento.habilitado:true}") boolean habilitado) {
    this.expiracaoMissoesService = expiracaoMissoesService;
    this.habilitado = habilitado;
  }

  /**
   * A guarda é explícita, dentro do método, em vez de @ConditionalOnProperty no bean: condições de
   * autoconfiguração em @Component comum têm ordem de avaliação não garantida, e aqui o que importa
   * é apenas não varrer o banco durante os testes — um job disparando em paralelo mudaria o status
   * de missões entre o arrange e o assert.
   */
  @Scheduled(
      fixedDelayString = "${app.missoes.expiracao.intervalo:PT5M}",
      initialDelayString = "${app.missoes.expiracao.atraso-inicial:PT1M}")
  public void expirarJanelasVencidas() {
    if (!habilitado) {
      return;
    }

    int total = 0;
    int processados;
    // Lotes em transações independentes. O teto evita laço infinito caso um lote reapareça por
    // qualquer motivo — sem ele, um bug de predicado viraria consumo de CPU indefinido.
    do {
      processados = expiracaoMissoesService.expirarLote(TAMANHO_LOTE);
      total += processados;
    } while (processados == TAMANHO_LOTE && total < TETO_POR_EXECUCAO);

    if (total > 0) {
      log.info("Missões expiradas nesta execução: {}", total);
    }
  }
}
