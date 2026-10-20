package com.omnitribo.missoes.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta pela qual {@code logistica} transforma uma entrega falida em missão de retirada (o ArchUnit
 * proíbe importar {@code missoes.dominio}). Só tipos da JDK na assinatura.
 *
 * <p>Roda na transação do chamador, que segura o {@code FOR UPDATE} do ponto: missão e ocupação
 * commitam juntas. {@code REQUIRES_NEW} proibido, como em todo o caminho de valor.
 */
public interface ConversaoEntregaFalida {

  /**
   * A encomenda no ponto, com o que a fórmula precisa. {@code valorOfertadoBrl} é só insumo do
   * TOKEN (ADR 0009). Risco chega calculado e como tipos JDK, porque a regra é direcional.
   *
   * @param multiplicadorRisco congelado na missão; nulo vale 1,00.
   * @param faixaRisco o {@code name()} do enum (BAIXO, MEDIO, ALTO), nunca texto livre.
   */
  record Encomenda(
      UUID entregaFalidaId,
      UUID pontoCustodiaId,
      String descricaoDoItem,
      BigDecimal origemLat,
      BigDecimal origemLon,
      BigDecimal destinoLat,
      BigDecimal destinoLon,
      String cep,
      String logradouro,
      String bairro,
      String cidade,
      String uf,
      BigDecimal pesoKg,
      BigDecimal volumeL,
      BigDecimal valorOfertadoBrl,
      BigDecimal multiplicadorRisco,
      String faixaRisco,
      UUID patrocinadorUsuarioId,
      Instant agora) {}

  /** O que a logística precisa saber de volta para fechar o registro e notificar. */
  record MissaoDeRetirada(
      UUID missaoId, int xpRecompensa, long tokensRecompensa, int nivelMinimo) {}

  /**
   * Cria a missão de retirada já ABERTA e com o pote pago pelo patrocinador.
   *
   * <p>Devolve VAZIO quando o patrocinador não cobre o pote: nada é criado, e o chamador grava
   * SEM_PATROCINIO com 200 — lançar apagaria o registro que a transportadora precisa ler. O
   * financiamento vem ANTES da missão: publicada com pote vazio, ela nunca poderia ser concluída.
   *
   * @param encomenda com {@code patrocinadorUsuarioId} não nulo — resolvido antes pelo chamador.
   */
  Optional<MissaoDeRetirada> abrirMissaoDeRetirada(Encomenda encomenda);
}
