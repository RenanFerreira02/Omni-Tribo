package com.omnitribo.geolocalizacao.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Tudo que o módulo geolocalizacao precisa saber para avaliar e registrar um check-in.
 *
 * <p>Só tipos da JDK atravessam a fronteira. Nenhum tipo de missoes.dominio entra aqui, e nenhum
 * tipo de geolocalizacao.dominio sai — é o que mantém os dois módulos independentes sob a regra do
 * ArchUnit.
 *
 * <p>A origem da missão e o raio vêm como VALORES, não como um id para geolocalizacao ir buscar.
 * Não é conveniência: o registro roda numa transação REQUIRES_NEW enquanto a transação chamadora
 * segura {@code SELECT ... FOR UPDATE} sobre a linha da missão. Se este módulo lesse a tabela
 * {@code missao}, seriam duas conexões disputando a mesma linha, e o deadlock seria garantido.
 */
public record ComandoCheckin(
    UUID missaoId,
    UUID usuarioId,
    BigDecimal lat,
    BigDecimal lon,
    BigDecimal acuraciaM,
    /**
     * Flag reportada pelo dispositivo (expo-location / Android). Entrada não confiável por natureza
     * — ver docs/seguranca/antifraude-geolocalizacao.md.
     */
    boolean mocked,
    BigDecimal origemLat,
    BigDecimal origemLon,
    int raioCheckinM,
    /**
     * sha256(usuarioId|missaoId|chaveDoCliente), já calculado pelo chamador. Nunca a chave crua.
     */
    String chaveIdempotencia,
    Instant agora) {}
