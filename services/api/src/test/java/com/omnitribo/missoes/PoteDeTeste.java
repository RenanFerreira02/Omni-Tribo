package com.omnitribo.missoes;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Pré-condição de teste: o pote da missão já cobre a recompensa.
 *
 * <p>Desde o ADR 0038 nenhuma missão criada por pessoa publica com token sem pote financiado. Os
 * testes de ciclo de vida, check-in, aceite e auditoria precisam de uma missão PUBLICADA que pague
 * token, e o financiamento em si não é o que medem — ele tem teste próprio, com dono de tribo e
 * ledger, em {@code FinanciamentoControllerTest} e {@code ConservacaoTokensTest}. Aqui o pote entra
 * como dado de cenário, pelo mesmo motivo pelo qual esses testes já pulam estados por SQL.
 *
 * <p>Por isso NÃO serve para teste que meça conservação — o token deste pote não saiu de carteira
 * nenhuma — nem para teste que CANCELE ou EXPIRE a missão: o estorno procura o lançamento de
 * financiamento, e sem ele falha. Esses financiam pela API (ver {@code
 * MissaoControllerTest.resolverCancelandoNaoCreditaNinguem}).
 */
public final class PoteDeTeste {

  private PoteDeTeste() {}

  public static void cobrir(JdbcTemplate jdbc, UUID missaoId) {
    jdbc.update(
        "UPDATE missao SET pote_tokens = tokens_recompensa"
            + " WHERE id = ? AND fonte_pote = 'COMUNIDADE'",
        missaoId);
  }
}
