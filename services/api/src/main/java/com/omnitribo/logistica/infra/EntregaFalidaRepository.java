package com.omnitribo.logistica.infra;

import com.omnitribo.logistica.dominio.EntregaFalida;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EntregaFalidaRepository extends JpaRepository<EntregaFalida, UUID> {

  List<EntregaFalida> findByPontoCustodiaId(UUID pontoCustodiaId);

  List<EntregaFalida> findByConvertidaEmIsNull();
}
