package com.omnitribo.compartilhado.infra;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Habilita o agendador do Spring. Separado dos jobs de propósito: o gatilho é infraestrutura
 * compartilhada, a regra de cada job é do módulo dono do domínio.
 */
@Configuration
@EnableScheduling
public class AgendamentoConfig {}
