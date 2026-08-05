package com.omnitribo.compartilhado.infra;

import com.omnitribo.compartilhado.dominio.Alerta;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertaRepository extends JpaRepository<Alerta, UUID> {

  List<Alerta> findByUsuarioIdAndLidoFalseOrderByCriadoEmDesc(UUID usuarioId);

  List<Alerta> findByUsuarioIdOrderByCriadoEmDesc(UUID usuarioId);
}
