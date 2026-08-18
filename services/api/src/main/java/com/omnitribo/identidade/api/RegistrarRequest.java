package com.omnitribo.identidade.api;

import com.omnitribo.identidade.dominio.SenhaComumVerificador.ValidSenhaNaoComum;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record RegistrarRequest(
    @NotBlank(message = "Nome é obrigatório") @Size(max = 100) String nome,
    @NotBlank(message = "Email é obrigatório") @Email(message = "Email inválido") String email,
    @NotBlank(message = "Handle é obrigatório")
        @Size(min = 3, max = 50, message = "Handle deve ter entre 3 e 50 caracteres")
        String handle,
    @NotBlank(message = "Senha é obrigatória")
        @Size(min = 12, message = "Senha deve ter no mínimo 12 caracteres")
        @ValidSenhaNaoComum
        String senha,
    UUID triboId // opcional na criação — usuário pode se associar a uma tribo depois
    ) {}
