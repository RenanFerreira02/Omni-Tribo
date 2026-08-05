package com.omnitribo.carteira.infra;

import com.omnitribo.carteira.dominio.Carteira;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CarteiraRepository extends JpaRepository<Carteira, UUID> {

  Optional<Carteira> findByUsuarioId(UUID usuarioId);
}
