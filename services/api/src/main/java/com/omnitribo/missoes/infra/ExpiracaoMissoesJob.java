package com.omnitribo.missoes.infra;

import com.omnitribo.missoes.dominio.EventoMissao;
import com.omnitribo.missoes.dominio.ExpiracaoMissoesService;
import com.omnitribo.missoes.dominio.ExpiracaoMissoesService.Candidata;
import com.omnitribo.missoes.dominio.MissaoService;
import com.omnitribo.missoes.dominio.RegraExpiracao;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Gatilho da expiração de missões: ABERTA com janela vencida vira EXPIRADA.
 *
 * <p>A regra mora em {@code ExpiracaoMissoesService}, no domínio. Aqui fica o agendamento <b>e o
 * laço</b> — e o laço estar aqui é obrigatório, não organizacional: cada missão é expirada na
 * própria transação, e um laço dentro do serviço chamaria {@code expirarUma} por {@code this}, sem
 * passar pelo proxy do Spring. O {@code @Transactional} seria ignorado e cada missão rodaria sem
 * transação nenhuma — o defeito mais silencioso possível, porque tudo continuaria "funcionando".
 */
@Component
public class ExpiracaoMissoesJob {

  private static final Logger log = LoggerFactory.getLogger(ExpiracaoMissoesJob.class);

  private static final int TAMANHO_LOTE = 200;
  private static final int TETO_POR_EXECUCAO = 5_000;

  /** Quantas foram expiradas e quantas falharam sem derrubar as demais. */
  public record Varredura(int expiradas, int falhas) {}

  private final ExpiracaoMissoesService expiracaoMissoesService;
  private final MissaoService missaoService;
  private final List<RegraExpiracao> regras;
  private final boolean habilitado;

  public ExpiracaoMissoesJob(
      ExpiracaoMissoesService expiracaoMissoesService,
      MissaoService missaoService,
      @Value("${app.missoes.expiracao.prazo-execucao:PT48H}") Duration prazoExecucao,
      @Value("${app.missoes.expiracao.prazo-confirmacao:PT72H}") Duration prazoConfirmacao,
      @Value("${app.agendamento.habilitado:true}") boolean habilitado) {
    this.expiracaoMissoesService = expiracaoMissoesService;
    this.missaoService = missaoService;
    this.regras = RegraExpiracao.padrao(prazoExecucao, prazoConfirmacao);
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
    Varredura resultado = varrer(TAMANHO_LOTE, TETO_POR_EXECUCAO);
    if (resultado.expiradas() > 0 || resultado.falhas() > 0) {
      log.info(
          "Expiração: {} missões expiradas, {} falhas", resultado.expiradas(), resultado.falhas());
    }
  }

  /**
   * Varre as candidatas, expirando cada uma na própria transação.
   *
   * <p>Público e sem a guarda de {@code habilitado} porque os testes chamam por aqui: o agendamento
   * fica desligado no perfil de teste de propósito, para nenhum job mudar estado entre arrange e
   * assert.
   */
  public Varredura varrer(int tamanhoLote, int tetoPorExecucao) {
    // UM instante para a execução inteira. Recalcular a cada lote faria o predicado se mover
    // debaixo do cursor, e uma missão que vence no meio da varredura entraria fora de ordem.
    Instant agora = Instant.now();

    int expiradas = 0;
    int falhas = 0;
    for (RegraExpiracao regra : regras) {
      Varredura parcial = varrerRegra(regra, agora, tamanhoLote, tetoPorExecucao);
      expiradas += parcial.expiradas();
      falhas += parcial.falhas();
    }
    return new Varredura(expiradas, falhas);
  }

  private Varredura varrerRegra(
      RegraExpiracao regra, Instant agora, int tamanhoLote, int tetoPorExecucao) {

    Candidata cursor = null;
    int aplicadas = 0;
    int falhas = 0;
    int vistas = 0;
    List<Candidata> lote;

    do {
      lote = expiracaoMissoesService.candidatas(regra, agora, cursor, tamanhoLote);
      if (lote.isEmpty()) {
        break;
      }
      cursor = lote.get(lote.size() - 1);
      vistas += lote.size();

      for (Candidata candidata : lote) {
        try {
          if (aplicar(regra, candidata.id(), agora)) {
            aplicadas++;
          }
        } catch (RuntimeException e) {
          // MESMO isolamento de DrenadorOutboxService, e pela mesma razão: uma missão cujo pote
          // diverge da soma dos financiamentos lança IllegalStateException no estorno. Sem este
          // catch ela derrubava o LOTE INTEIRO, e o job reencontrava a mesma missão a cada 5
          // minutos — nada mais no sistema expirava por causa de uma linha inconsistente.
          //
          // O cursor keyset é o que impede a envenenada de ser reprocessada dentro DESTA execução:
          // ela continua no mesmo status e vencida, mas já ficou para trás na ordem (marco, id).
          falhas++;
          log.error(
              "Falha ao aplicar {} na missão {}; a varredura continua."
                  + " Investigue a conservação do pote.",
              regra.evento(),
              candidata.id(),
              e);
        }
      }
    } while (vistas < tetoPorExecucao && lote.size() == tamanhoLote);

    return new Varredura(aplicadas, falhas);
  }

  /**
   * Escolhe o caminho conforme o DESFECHO da regra: terminar sem crédito, ou concluir pagando.
   *
   * <p>{@code EXPIRAR_CONFIRMACAO} vai por {@code MissaoService}, que é o único caminho de crédito
   * do sistema. Reimplementar o pagamento aqui criaria um segundo caminho de valor — é assim que a
   * conservação da moeda se perde sem ninguém perceber.
   */
  private boolean aplicar(RegraExpiracao regra, UUID missaoId, Instant agora) {
    if (regra.evento() == EventoMissao.EXPIRAR_CONFIRMACAO) {
      missaoService.concluirPorOmissaoDoCriador(missaoId, agora);
      return true;
    }
    return expiracaoMissoesService.expirarUma(regra, missaoId, agora);
  }
}
