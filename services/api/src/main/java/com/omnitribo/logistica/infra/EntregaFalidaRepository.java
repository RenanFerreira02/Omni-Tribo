package com.omnitribo.logistica.infra;

import com.omnitribo.logistica.dominio.EntregaFalida;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EntregaFalidaRepository extends JpaRepository<EntregaFalida, UUID> {

  /**
   * Sondagem de idempotência do webhook.
   *
   * <p>O par é a chave natural da encomenda e tem UNIQUE desde a V21. Devolve {@code Optional}, e
   * não {@code List}, porque a unicidade é do banco: se aparecer segunda linha, é a constraint que
   * está faltando, e um {@code NonUniqueResultException} é exatamente o alarme que se quer nesse
   * caso — não um "pega o primeiro" silencioso.
   *
   * <p>Chamada SOB o lock do ponto de custódia. Sondar antes de travar reabre a corrida: dois
   * webhooks idênticos leriam "não existe" ao mesmo tempo e os dois inseririam, e aí quem decide é
   * a UNIQUE, com 500 para um deles. É a mesma ordem que o check-in usa — "adquira todos os locks →
   * sonde a chave → valide → escreva".
   */
  Optional<EntregaFalida> findByTransportadoraAndCodigoRastreio(
      String transportadora, String codigoRastreio);

  /** Baixa da custódia na conclusão da missão. */
  Optional<EntregaFalida> findByMissaoId(UUID missaoId);

  /**
   * Os quatro desfechos do webhook, numa única statement (painel de impacto, ADR 0029).
   *
   * <p>{@code FILTER (WHERE ...)} e não quatro consultas: sob READ COMMITTED cada statement teria
   * snapshot próprio, e um webhook chegando entre a segunda e a terceira produziria um resumo em
   * que os três desfechos não somam o total — uma incoerência aritmética visível na tela, causada
   * só pelo momento da leitura. É a mesma razão que faz a reconciliação caber numa statement só.
   *
   * <p>{@code convertidas} olha {@code missao_id IS NOT NULL} e não {@code convertida_em}: a
   * primeira é a que {@code ck_entrega_falida_recusada_sem_missao} (V21) amarra à ausência de
   * recusa, então é a coluna cuja coerência o banco garante.
   *
   * <p><b>{@code pendentes} é o quarto desfecho</b>: sem missão E sem recusa — encomenda na
   * custódia que nunca virou missão. O webhook não produz esse estado, mas o schema permite e o
   * seed V901 usa. Sem contá-lo, os outros três não somam o total e a taxa de conversão sai
   * calculada sobre um denominador com um grupo invisível dentro. Ver {@code
   * ResumoEntregasFalidas}.
   */
  @Query(
      value =
          """
          SELECT COUNT(*)                                                          AS recebidas,
                 COUNT(*) FILTER (WHERE missao_id IS NOT NULL)                     AS convertidas,
                 COUNT(*) FILTER (WHERE missao_id IS NULL
                                    AND motivo_recusa IS NULL)                      AS pendentes,
                 COUNT(*) FILTER (WHERE motivo_recusa = 'PONTO_LOTADO')            AS lotado,
                 COUNT(*) FILTER (WHERE motivo_recusa = 'SEM_PATROCINIO')          AS semPatrocinio
          FROM entrega_falida
          """,
      nativeQuery = true)
  DesfechosProjecao contarDesfechos();

  /**
   * {@code (missao_id, recebido_em)} das entregas convertidas — a metade que este módulo tem da
   * mediana "webhook → check-in". Ver {@code EstatisticasEntregasFalidas.recebimentoPorMissao()}.
   */
  @Query(
      value =
          """
          SELECT missao_id AS missaoId, recebido_em AS recebidoEm
          FROM entrega_falida
          WHERE missao_id IS NOT NULL
          """,
      nativeQuery = true)
  List<RecebimentoProjecao> buscarRecebimentoPorMissao();

  /** Projeção de interface: o Spring Data materializa do ResultSet, sem entidade no caminho. */
  interface DesfechosProjecao {
    long getRecebidas();

    long getConvertidas();

    long getPendentes();

    long getLotado();

    long getSemPatrocinio();
  }

  interface RecebimentoProjecao {
    UUID getMissaoId();

    java.time.Instant getRecebidoEm();
  }
}
