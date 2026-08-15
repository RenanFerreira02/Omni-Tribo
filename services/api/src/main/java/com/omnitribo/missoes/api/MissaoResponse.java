package com.omnitribo.missoes.api;

import com.omnitribo.compartilhado.api.RecursoAuditavel;
import com.omnitribo.compartilhado.dominio.Coordenadas;
import com.omnitribo.missoes.dominio.CategoriaMissao;
import com.omnitribo.missoes.dominio.ComplexidadeMissao;
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
    /**
     * Complexidade EFETIVA e versão da fórmula, CONGELADAS na criação.
     *
     * <p>Existiam na entidade desde a V16 e não saíam por via nenhuma da API — os dois getters eram
     * código morto. O campo cuja razão de existir é responder "este crédito estava certo quando foi
     * feito?" não era legível por quem precisa da resposta.
     *
     * <p>{@code POST /previa-recompensa} já devolvia os dois; depois de criada, a missão parava de
     * devolvê-los, e o app não tinha como mostrar por que aquela recompensa é aquela.
     */
    ComplexidadeMissao complexidade,
    Integer versaoFormula,

    /**
     * Nível mínimo para aceitar. 1 = sem restrição, que é o caso de toda missão criada por usuário.
     *
     * <p>Vai na resposta para o app poder DESABILITAR o botão de aceitar com a explicação certa, em
     * vez de deixar a pessoa tentar e levar 422. Um botão que só falha depois do toque é pior do
     * que um botão desabilitado que diz o porquê — e o 422 continua existindo, porque a checagem do
     * cliente é conveniência e a do servidor é a regra.
     */
    int nivelMinimo,
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

  /**
   * Casas decimais da coordenada mostrada a quem NÃO participa da missão.
   *
   * <p>3 casas ≈ 110 m: o bastante para "isso é perto de mim", insuficiente para achar a porta. O
   * app já limita o radar a 20 km, então a precisão perdida não muda nenhuma decisão de quem
   * procura missão — e muda tudo para quem estaria catalogando endereços.
   */
  private static final int CASAS_APROXIMADAS = 3;

  /** Precisão plena para quem participa: o executor precisa CHEGAR ao lugar. */
  private static final int CASAS_EXATAS = 6;

  /**
   * Visão completa. Use só quando o solicitante é participante por construção — ações do ciclo de
   * vida (publicar, aceitar, check-in, confirmar) são sempre do criador ou do executor.
   */
  public static MissaoResponse de(Missao m) {
    return montar(m, true);
  }

  /**
   * Visão recortada por PARTICIPAÇÃO: criador e executor veem tudo; os demais veem menos.
   *
   * <p><b>O problema.</b> {@code GET /missoes} devolvia coordenada com ~15 dígitos significativos
   * mais logradouro e CEP de TODA missão não-rascunho, paginada, para qualquer autenticado. Um
   * usuário comum colhia endereço exato do bairro inteiro — inclusive de missões já CONCLUIDAS —
   * enquanto {@code TriboController} recusa listar membros porque "essa lista seria uma lista de
   * alvos" e não existe {@code GET /usuarios/{id}} justamente por privacidade. A listagem entregava
   * o mesmo mapa social por outra porta, com o endereço junto.
   *
   * <p><b>O que muda para quem não participa:</b> coordenada em 3 casas (~110 m) e endereço
   * reduzido a bairro/cidade/UF. Some o logradouro e o CEP — que juntos identificam a residência.
   *
   * <p>Arredondar sozinho NÃO resolveria: 6 casas são ~11 cm, ainda é a porta. O recorte útil é o
   * do CAMPO, não o da precisão.
   */
  public static MissaoResponse de(Missao m, UUID solicitanteId) {
    boolean participa =
        solicitanteId != null
            && (solicitanteId.equals(m.getCriadorId()) || solicitanteId.equals(m.getExecutorId()));
    return montar(m, participa);
  }

  /**
   * Visão recortada para TODO MUNDO, independente de quem pergunta.
   *
   * <p>É o que o radar de proximidade usa, e a independência é obrigatória lá: o resultado é
   * cacheado por célula geográfica, sem o usuário na chave, então uma resposta montada para um
   * participante seria servida a um estranho. Pôr o solicitante na chave transformaria o cache numa
   * entrada por usuário — ou seja, em nada.
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
        m.getVersao());
  }
}
