package com.omnitribo.geolocalizacao.infra;

import com.omnitribo.geolocalizacao.dominio.Checkin;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckinRepository extends JpaRepository<Checkin, UUID> {

  List<Checkin> findByMissaoId(UUID missaoId);

  List<Checkin> findByUsuarioId(UUID usuarioId);
}
