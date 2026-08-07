package com.omnitribo.missoes.api;

import com.omnitribo.compartilhado.api.RecursoAuditavel;
import com.omnitribo.compartilhado.dominio.Coordenadas;
import com.omnitribo.missoes.dominio.CategoriaMissao;
import com.omnitribo.missoes.dominio.Missao;
import com.omnitribo.missoes.dominio.StatusMissao;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Representação de leitura de uma missão. Único ponto de conversão entidade → DTO. */
@Schema(description = "Missão em qualquer estado do ciclo de vida")
public record MissaoResponse(
    UUID id,
    UUID criadorId,
    UUID executorId,
    CategoriaMissao categoria,
    StatusMissao status,
    String titulo,
    String descricao,
    int xpRecompensa,
    BigDecimal valorBrl,
    long tokensRecompensa,
    // Tokens já financiados e em custódia. Aditivo no envelope: nenhum cliente existente quebra,
    // e o app precisa dele para mostrar o quanto falta financiar antes de a missão ser publicável.
    long poteTokens,
    BigDecimal origemLat,
    BigDecimal origemLon,
    BigDecimal destinoLat,
    BigDecimal destinoLon,
    UUID pontoCustodiaId,
    String cep,
    String logradouro,
    String bairro,
    String cidade,
    String uf,
    int raioCheckinM,
    BigDecimal pesoKg,
    BigDecimal volumeL,
    Instant janelaInicio,
    Instant janelaFim,
    Instant criadaEm,
    Instant aceitaEm,
    Instant concluidaEm,
    int versao)
    implements RecursoAuditavel {

  /**
   * Id da missão para a trilha de auditoria. Não é componente novo do record nem getter no padrão
   * getX/isX, então não entra na serialização JSON — o envelope da API continua idêntico.
   */
  @Override
  public UUID idAuditoria() {
    return id;
  }

  public static MissaoResponse de(Missao m) {
    return new MissaoResponse(
        m.getId(),
        m.getCriadorId(),
        m.getExecutorId(),
        m.getCategoria(),
        m.getStatus(),
        m.getTitulo(),
        m.getDescricao(),
        m.getXpRecompensa(),
        m.getValorBrl(),
        m.getTokensRecompensa(),
        m.getPoteTokens(),
        Coordenadas.latitude(m.getOrigem()),
        Coordenadas.longitude(m.getOrigem()),
        Coordenadas.latitude(m.getDestino()),
        Coordenadas.longitude(m.getDestino()),
        m.getPontoCustodiaId(),
        m.getCep(),
        m.getLogradouro(),
        m.getBairro(),
        m.getCidade(),
        m.getUf(),
        m.getRaioCheckinM(),
        m.getPesoKg(),
        m.getVolumeL(),
        m.getJanelaInicio(),
        m.getJanelaFim(),
        m.getCriadaEm(),
        m.getAceitaEm(),
        m.getConcluidaEm(),
        m.getVersao());
  }
}
