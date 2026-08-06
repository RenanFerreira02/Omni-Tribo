package com.omnitribo.identidade.api;

public record LoginResponse(
    String accessToken,
    String refreshToken,
    String tipoToken, // sempre "Bearer"
    long expiresIn // segundos até expiração do access token
    ) {}
