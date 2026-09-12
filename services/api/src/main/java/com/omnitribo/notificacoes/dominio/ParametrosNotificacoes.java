package com.omnitribo.notificacoes.dominio;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Calibração do fan-out de alertas geográficos. */
@ConfigurationProperties(prefix = "app.notificacoes")
public record ParametrosNotificacoes(
    /**
     * Raio do alerta, em metros, medido do local da missão até o CENTRO DA TRIBO do destinatário —
     * não até a pessoa, que não tem coordenada no sistema. Ver ADR 0020.
     */
    int raioAlertaMetros,

    /**
     * Teto de alertas por usuário por hora.
     *
     * <p>Existe porque a densidade de entregas falidas é irregular: um ponto de custódia
     * movimentado no fim da rota do dia gera uma rajada, e sem teto a mesma pessoa recebe dezenas
     * de notificações em minutos. A reação a isso é desinstalar o app, não aceitar missão — ou
     * seja, o excesso de notificação destrói exatamente o canal que a notificação existe para usar.
     */
    int alertasPorHora,

    /**
     * Teto de tribos consideradas por evento.
     *
     * <p>Guarda de segurança, não de produto. O fan-out roda dentro da transação do lote da outbox,
     * que processa até 100 eventos por vez; sem teto, um ponto numa região densa expandiria um
     * evento em milhares de inserts e seguraria a transação do lote inteiro.
     */
    int tribosPorEvento,

    /**
     * Teto por hora para alertas de risco ALTO.
     *
     * <p>Maior que {@code alertasPorHora}, e é essa folga que resolve o problema: sem ela, cinco
     * entregas triviais chegando primeiro silenciariam a difícil pela hora seguinte — justamente a
     * que mais precisa de alguém e a que paga melhor. É folga, não isenção: uma rajada de entregas
     * de alto risco no mesmo ponto continua limitada.
     */
    int alertasAltaPrioridadePorHora,

    /**
     * Janela de deduplicação do alerta operacional GLOBAL — o de ponto lotado e o de entrega sem
     * patrocínio. Uma linha por (tipo, referência, janela).
     *
     * <p>É outro problema que {@code alertasPorHora} NÃO resolve, e não por defeito dele: o teto
     * por hora conta {@code WHERE usuario_id = ?}, e estes alertas têm {@code usuario_id} nulo de
     * propósito, porque são sinal de operação e não notificação de ninguém. Em SQL {@code NULL = ?}
     * é UNKNOWN, então a contagem daria zero para sempre. O teste de carga de 2026-08-25 mediu a
     * consequência: 631 linhas idênticas em menos de 3 minutos, disparadas por uma transportadora
     * reenviando contra um ponto cheio. Ver ADR 0033.
     *
     * <p>Escolher a janela é escolher quão grosseiro é o sinal, e não quanto dado se perde: a
     * contagem de recusas continua inteira em {@code entrega_falida} e é lida por {@code GET
     * /admin/pontos-custodia/recusas}.
     */
    Duration janelaAlertaOperacional) {

  public ParametrosNotificacoes {
    if (raioAlertaMetros <= 0) {
      throw new IllegalArgumentException("app.notificacoes.raio-alerta-metros deve ser positivo");
    }
    if (alertasPorHora <= 0) {
      throw new IllegalArgumentException("app.notificacoes.alertas-por-hora deve ser positivo");
    }
    if (tribosPorEvento <= 0) {
      throw new IllegalArgumentException("app.notificacoes.tribos-por-evento deve ser positivo");
    }
    if (alertasAltaPrioridadePorHora < alertasPorHora) {
      // Menor que o teto normal inverteria o sentido do carve-out: o alerta mais urgente seria o
      // primeiro a ser descartado, e nada no comportamento denunciaria a inversão.
      throw new IllegalArgumentException(
          "app.notificacoes.alertas-alta-prioridade-por-hora não pode ser menor que"
              + " alertas-por-hora");
    }
    if (janelaAlertaOperacional == null || janelaAlertaOperacional.toSeconds() < 1) {
      // O piso é UM SEGUNDO, não "positiva", e a diferença é uma armadilha real: PT0.5S é positiva
      // e sobreviveria a uma checagem de isZero()/isNegative(), mas toSeconds() a trunca para 0 e o
      // floorDiv de DespachanteAlertaService.janelaDe estouraria com ArithmeticException. Isso não
      // apareceria como erro de configuração: a exceção subiria pelo despacho, o drenador a
      // contaria como falha de entrega e o evento terminaria na carta-morta da outbox.
      throw new IllegalArgumentException(
          "app.notificacoes.janela-alerta-operacional deve ser de pelo menos 1 segundo");
    }
  }
}
