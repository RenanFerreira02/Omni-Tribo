package com.omnitribo.identidade.infra;

import com.omnitribo.identidade.dominio.Dispositivo;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DispositivoRepository extends JpaRepository<Dispositivo, UUID> {

  List<Dispositivo> findByUsuarioId(UUID usuarioId);

  Optional<Dispositivo> findByPushToken(String pushToken);
}
