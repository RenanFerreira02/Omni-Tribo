package com.omnitribo.logistica.infra;

import com.omnitribo.logistica.dominio.EntregaFalida;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
