package com.omnitribo.missoes.api;

import com.omnitribo.compartilhado.api.RecursoAuditavel;
import com.omnitribo.compartilhado.dominio.Coordenadas;
import com.omnitribo.missoes.dominio.CategoriaMissao;
import com.omnitribo.missoes.dominio.ComplexidadeMissao;
import com.omnitribo.missoes.dominio.FontePote;
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
    // Tokens já financiados: o app mostra quanto falta para publicar.
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
    /**
     * Complexidade efetiva e versão da fórmula, congeladas: o app explica por que a recompensa é
     * essa.
     */
    ComplexidadeMissao complexidade,
    Integer versaoFormula,

    /**
     * Nível mínimo para aceitar (1 = sem restrição). Vai na resposta para o app desabilitar o botão
     * explicando o porquê; o 422 do servidor continua sendo a regra.
     */
    int nivelMinimo,

    /**
     * Risco avaliado na criação, congelado. Nulo fora de entrega falida — o app trata ausência como
     * "sem avaliação", nunca como risco baixo. O multiplicador é o que explica a recompensa.
     */
    BigDecimal multiplicadorRisco,
    String faixaRisco,

    /**
     * Texto pronto do aviso de risco, ou nulo. Montado no SERVIDOR: mudar a orientação é deploy,
     * não publicação na loja. <b>Nunca cita logradouro nem CEP</b>, ou devolveria o que o recorte
     * por participação protege.
     */
    String avisoRisco,

    /**
     * De onde sai o token (FontePote). Sem ela, pote zero não distingue "falta financiar", "o
     * patrocinador paga" e "não paga token" (ADR 0035) — três telas diferentes.
     */
    FontePote fontePote,
    int versao)
    implements RecursoAuditavel {

  /** Id para a auditoria. Não é componente nem getter, então não entra no JSON. */
  @Override
  public UUID idAuditoria() {
    return id;
  }

  /** Casas da coordenada para quem NÃO participa: 3 casas ≈ 110 m — perto, sem achar a porta. */
  private static final int CASAS_APROXIMADAS = 3;

  /** Precisão plena para quem participa: o executor precisa CHEGAR ao lugar. */
  private static final int CASAS_EXATAS = 6;

  /**
   * Visão completa: só quando o solicitante é participante por construção (criador ou executor).
   */
  public static MissaoResponse de(Missao m) {
    return montar(m, true);
  }

  /**
   * Visão recortada por PARTICIPAÇÃO: criador e executor veem tudo; os demais veem coordenada em 3
   * casas e endereço reduzido a bairro/cidade/UF. Arredondar sozinho não bastaria — o recorte útil
   * é do CAMPO: logradouro e CEP juntos identificam a residência.
   */
  public static MissaoResponse de(Missao m, UUID solicitanteId) {
    boolean participa =
        solicitanteId != null
            && (solicitanteId.equals(m.getCriadorId()) || solicitanteId.equals(m.getExecutorId()));
    return montar(m, participa);
  }

  /**
   * Visão recortada para TODO MUNDO: é a do radar, cujo cache é por região sem o usuário — uma
   * resposta de participante seria servida a um estranho.
   */
  public static MissaoResponse deAproximada(Missao m) {
    return montar(m, false);
  }

  private static MissaoResponse montar(Missao m, boolean participa) {
    int casas = participa ? CASAS_EXATAS : CASAS_APROXIMADAS;
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
        Coordenadas.arredondar(Coordenadas.latitude(m.getOrigem()), casas),
        Coordenadas.arredondar(Coordenadas.longitude(m.getOrigem()), casas),
        Coordenadas.arredondar(Coordenadas.latitude(m.getDestino()), casas),
        Coordenadas.arredondar(Coordenadas.longitude(m.getDestino()), casas),
        m.getPontoCustodiaId(),
        participa ? m.getCep() : null,
        participa ? m.getLogradouro() : null,
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
        m.getComplexidade(),
        m.getVersaoFormula(),
        m.getNivelMinimo(),
        m.getMultiplicadorRisco(),
        m.getFaixaRisco(),
        avisoDe(m.getFaixaRisco()),
        m.getFontePote(),
        m.getVersao());
  }

  /**
   * Orientação acionável para a faixa de risco. Só ALTO e MEDIO avisam (aviso que aparece sempre
   * deixa de ser lido), e o texto diz o que FAZER, não só o que temer.
   */
  private static String avisoDe(String faixaRisco) {
    if (faixaRisco == null) {
      return null;
    }
    return switch (faixaRisco) {
      case "ALTO" ->
          "Entregas neste endereço costumam falhar. Combine o horário com o destinatário antes de"
              + " ir — é o que mais aumenta a chance de dar certo desta vez.";
      case "MEDIO" ->
          "Esta entrega tem histórico irregular. Vale confirmar se há alguém para receber antes de"
              + " sair.";
      default -> null;
    };
  }
}
