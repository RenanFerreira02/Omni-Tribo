package com.omnitribo.carteira.infra;

import com.omnitribo.carteira.dominio.Parceiro;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParceiroRepository extends JpaRepository<Parceiro, UUID> {

  List<Parceiro> findByIdInAndAtivoTrue(Collection<UUID> ids);

  List<Parceiro> findByTriboIdAndAtivoTrue(UUID triboId);
}
