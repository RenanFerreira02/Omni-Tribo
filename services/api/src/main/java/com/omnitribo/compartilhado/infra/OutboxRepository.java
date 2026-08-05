package com.omnitribo.compartilhado.infra;

import com.omnitribo.compartilhado.dominio.Outbox;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<Outbox, UUID> {

  List<Outbox> findByPublicadoEmIsNullOrderByCriadoEmAsc();
}
