package com.omnitribo.logistica.infra;

import com.omnitribo.logistica.dominio.PontoCustodia;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PontoCustodiaRepository extends JpaRepository<PontoCustodia, UUID> {}
