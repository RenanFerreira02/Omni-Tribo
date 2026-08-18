package com.omnitribo.identidade.infra;

import com.omnitribo.identidade.dominio.Tribo;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TriboRepository extends JpaRepository<Tribo, UUID> {}
