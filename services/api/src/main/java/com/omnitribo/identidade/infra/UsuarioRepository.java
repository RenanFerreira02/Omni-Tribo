package com.omnitribo.identidade.infra;

import com.omnitribo.identidade.dominio.Usuario;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsuarioRepository extends JpaRepository<Usuario, UUID> {

  Optional<Usuario> findByEmail(String email);

  Optional<Usuario> findByHandle(String handle);

  boolean existsByEmail(String email);

  boolean existsByHandle(String handle);
}
