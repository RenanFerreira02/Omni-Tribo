package com.omnitribo.notificacoes.dominio;

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
    int tribosPorEvento) {

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
  }
}
