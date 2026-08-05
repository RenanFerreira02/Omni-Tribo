package com.omnitribo.missoes.infra;

import com.omnitribo.missoes.dominio.MissaoEvento;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MissaoEventoRepository extends JpaRepository<MissaoEvento, UUID> {

  List<MissaoEvento> findByMissaoIdOrderByCriadoEmAsc(UUID missaoId);
}
