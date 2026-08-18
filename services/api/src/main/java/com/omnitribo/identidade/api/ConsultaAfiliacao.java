package com.omnitribo.identidade.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta pública de identidade para consulta de tribo.
 *
 * <p>Consumida por {@code carteira} (regra de transferência só na mesma tribo) e por {@code
 * missoes} (escopo do financiamento). Ambos estão proibidos pelo ArchUnit de tocar {@code
 * identidade.dominio}.
 */
public interface ConsultaAfiliacao {

  Optional<UUID> triboDe(UUID usuarioId);

  /**
   * {@code true} SÓ quando ambos os usuários existem E pertencem à MESMA tribo.
   *
   * <p>Método próprio, e não duas chamadas a {@link #triboDe} comparadas no chamador. {@code
   * usuario.tribo_id} é nullable, então comparar dois {@code Optional.empty()} daria "mesma tribo"
   * para duas pessoas SEM tribo nenhuma — e liberaria transferência de tokens entre elas, furando a
   * única regra que limita a circulação da moeda comunitária. A semântica do nulo fica de um lado
   * só da fronteira, onde dá para testá-la.
   */
  boolean mesmaTribo(UUID usuarioA, UUID usuarioB);
}
