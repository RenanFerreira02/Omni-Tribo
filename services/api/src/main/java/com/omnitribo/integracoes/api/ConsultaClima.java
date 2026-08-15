package com.omnitribo.integracoes.api;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Porta pública do módulo integracoes para leitura de clima.
 *
 * <p>{@code logistica} depende DESTA interface, nunca da implementação: a regra do ArchUnit proíbe
 * qualquer classe fora de {@code com.omnitribo.integracoes..} de acessar {@code
 * integracoes.dominio..} ou {@code integracoes.infra..}. Como o Spring injeta pela interface, o
 * bytecode do chamador nunca nomeia uma classe dos pacotes internos.
 *
 * <p><b>Devolve {@code Optional}, e nunca lança por indisponibilidade.</b> É a diferença central
 * entre esta porta e {@code IntegracoesController}, que responde 503 e faz o app esconder o card de
 * clima (ADR 0011). Aqui o consumidor é o webhook de transportadora: um provedor externo fora do ar
 * não pode transformar em erro uma entrega falida que a transportadora está tentando registrar —
 * ela reenviaria em laço, e o ponto de custódia continuaria com a encomenda sem missão. Ausência de
 * clima degrada o modelo de risco (a característica é imputada), não a entrada do fato.
 *
 * <p>Três coisas produzem ausência, e nenhuma delas é excepcional: provedor fora do ar, tempo
 * esgotado, e recusa do bulkhead de chamadas simultâneas ({@code
 * app.integracoes.clima.chamadas-simultaneas}) sob rajada de webhooks.
 */
public interface ConsultaClima {

  /**
   * Condição no ponto, ou vazio se o provedor não respondeu.
   *
   * <p>Só tipos JDK na assinatura — exigência do ArchUnit para porta pública.
   *
   * @param chuvaMm precipitação na hora corrente, em milímetros
   * @param temperaturaC temperatura, em graus Celsius
   */
  record CondicaoClimatica(BigDecimal chuvaMm, BigDecimal temperaturaC) {}

  Optional<CondicaoClimatica> consultarParaRisco(BigDecimal lat, BigDecimal lon);
}
