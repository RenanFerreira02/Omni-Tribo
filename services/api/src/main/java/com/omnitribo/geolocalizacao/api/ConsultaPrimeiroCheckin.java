package com.omnitribo.geolocalizacao.api;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Quando cada missão recebeu o PRIMEIRO check-in válido do executor (ADR 0029).
 *
 * <p><b>Primeiro e válido, as duas coisas.</b> O primeiro porque a métrica mede quanto tempo o
 * bairro levou para responder — um segundo check-in, no destino, mediria a duração da execução, que
 * é outra pergunta. Válido porque {@code checkin} é APPEND-ONLY e guarda também as tentativas
 * REPROVADAS (fora do raio, GPS falsificado, cinemática impossível): contá-las faria uma tentativa
 * fraudulenta às 3h da manhã melhorar o indicador de impacto do produto.
 */
public interface ConsultaPrimeiroCheckin {

  /**
   * @param missaoIds pode vir vazia — nesse caso devolve mapa vazio SEM consultar o banco, porque
   *     {@code IN ()} não é SQL válido
   * @return só as missões que TÊM check-in válido; ausência de chave significa "ninguém apareceu",
   *     e é o chamador quem decide o que fazer com isso (aqui: fica fora da amostra da mediana)
   */
  Map<UUID, Instant> primeiroCheckinValido(Collection<UUID> missaoIds);
}
