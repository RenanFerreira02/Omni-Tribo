package com.omnitribo.identidade.infra;

import com.omnitribo.identidade.dominio.Auditoria;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditoriaRepository extends JpaRepository<Auditoria, UUID> {}
