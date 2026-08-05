package com.omnitribo.logistica.infra;

import com.omnitribo.logistica.dominio.PontoCustodia;
import com.omnitribo.logistica.dominio.TipoPontoCustodia;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PontoCustodiaRepository extends JpaRepository<PontoCustodia, UUID> {

  Optional<PontoCustodia> findByCodigo(String codigo);

  List<PontoCustodia> findByAtivoTrue();

  List<PontoCustodia> findByTipoAndAtivoTrue(TipoPontoCustodia tipo);
}
