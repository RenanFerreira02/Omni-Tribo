package com.omnitribo.carteira.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Entrada de {@link CreditoRecompensa#creditarConclusao}.
 *
 * <p>Só tipos da JDK cruzam a fronteira do módulo — é o que permite a {@code missoes} depender
 * desta porta sem que o bytecode dela nomeie {@code carteira.dominio} ou {@code carteira.infra}, o
 * que a regra do ArchUnit proíbe.
 *
 * <p>Não existe flag de pote aqui, de propósito. {@code missoes} é dona de {@code
 * missao.pote_tokens} e debita o pote sob o lock da missão que já segura, informando a {@code
 * carteira} apenas QUANTO creditar. A carteira nunca toca a tabela {@code missao}. Isso mantém a
 * dependência em uma direção só e evita o ciclo {@code missoes.api} ⇄ {@code carteira.api}.
 *
 * @param missaoId gravado em {@code lancamento.missao_id} como UUID puro, sem FK
 * @param executorId dono da carteira a creditar
 * @param chaveIdempotencia já derivada por {@code ChaveIdempotencia.conclusaoMissao}
 */
public record ComandoCreditoConclusao(
    UUID missaoId,
    UUID executorId,
    BigDecimal valorBrl,
    long tokens,
    String chaveIdempotencia,
    Instant agora) {}
