package com.omnitribo.identidade.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @NotBlank @Email String email,
    // Sem @Size aqui: mensagem de erro diferente para senha curta vs. senha incorreta
    // ajudaria um atacante a distinguir casos. A validação de tamanho só existe no registro.
    @NotBlank String senha) {}
