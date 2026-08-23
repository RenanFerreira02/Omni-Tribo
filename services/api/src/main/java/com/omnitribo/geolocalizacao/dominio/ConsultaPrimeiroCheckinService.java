package com.omnitribo.geolocalizacao.dominio;

import com.omnitribo.geolocalizacao.api.ConsultaPrimeiroCheckin;
import com.omnitribo.geolocalizacao.infra.CheckinRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Implementação de {@link ConsultaPrimeiroCheckin} (ADR 0029). */
@Service
public class ConsultaPrimeiroCheckinService implements ConsultaPrimeiroCheckin {

  private final CheckinRepository checkinRepository;

  public ConsultaPrimeiroCheckinService(CheckinRepository checkinRepository) {
    this.checkinRepository = checkinRepository;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public Map<UUID, Instant> primeiroCheckinValido(Collection<UUID> missaoIds) {
    // Guarda obrigatória, não defensiva: `IN ()` é erro de sintaxe no PostgreSQL, então com lista
    // vazia a consulta não falharia em "zero resultados" e sim em exceção de SQL. Acontece de
    // verdade — banco recém-criado, ou nenhuma entrega convertida ainda.
    if (missaoIds.isEmpty()) {
      return Map.of();
    }

    Map<UUID, Instant> primeiro = new LinkedHashMap<>();
    for (CheckinRepository.PrimeiroCheckinProjecao p :
        checkinRepository.buscarPrimeiroValido(missaoIds)) {
      primeiro.put(p.getMissaoId(), p.getPrimeiroEm());
    }
    return primeiro;
  }
}
