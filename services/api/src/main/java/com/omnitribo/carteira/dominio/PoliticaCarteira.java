package com.omnitribo.carteira.dominio;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limites operacionais da carteira, vinculados a {@code app.carteira.*}.
 *
 * <p>Os tetos de transferência não existem para impedir uso legítimo — existem para limitar o DANO
 * de uma conta comprometida. Quem rouba um token de acesso pode transferir; o teto por transação
 * impede o esvaziamento num único POST, e o teto por janela impede o mesmo esvaziamento fatiado em
 * N requisições. Um sem o outro é contornável de forma trivial.
 *
 * <p>Record imutável, no molde de {@code JwtProperties}: propriedade ausente falha no startup, em
 * vez de virar zero silencioso — e um teto zero recusaria toda transferência, ou pior, um teto lido
 * como {@code null} viraria NPE só quando alguém transferisse.
 */
@ConfigurationProperties(prefix = "app.carteira")
public record PoliticaCarteira(
    long transferenciaTetoPorTransacao,
    long transferenciaTetoPorJanela,
    Duration transferenciaJanela,
    BigDecimal saqueMinimoBrl,

    /**
     * Liga ou desliga {@code POST /carteira/saques}. <b>Desligado por padrão.</b>
     *
     * <p>Saque é a única saída de BRL do sistema, e desde o ADR 0009 o BRL deixou de ser recompensa
     * de missão — não existe mais forma de ganhá-lo. Manter o endpoint aberto sacaria saldo herdado
     * de um modelo que não vale mais.
     *
     * <p>É uma FLAG, e não a remoção do código, de propósito: o fluxo de saque está implementado e
     * coberto por testes de concorrência, mínimo, idempotência e saldo insuficiente. Se a conversão
     * patrocinada de TOKEN para moeda corrente se concretizar, é exatamente esta a mecânica que ela
     * reaproveita. Apagar e reescrever depois seria jogar fora trabalho já verificado.
     *
     * <p>O perfil de teste liga a flag para que essa cobertura continue exercitando o caminho real;
     * existe um teste próprio para o comportamento com ela desligada.
     */
    boolean saqueHabilitado) {}
