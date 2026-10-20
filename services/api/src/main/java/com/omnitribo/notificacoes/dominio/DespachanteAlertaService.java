package com.omnitribo.notificacoes.dominio;

import com.omnitribo.compartilhado.api.ConsultasGeoespaciais;
import com.omnitribo.identidade.api.ConsultaConsentimento;
import com.omnitribo.identidade.api.ProgressaoUsuario;
import com.omnitribo.notificacoes.api.DespachoAlerta;
import com.omnitribo.notificacoes.infra.AlertaRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Entrega eventos da outbox como alerta in-app ({@link DespachoAlerta}). Despachar = gravar em
 * {@code alerta}, a caixa de entrada do app; push remoto trocaria só este corpo.
 *
 * <p>Não é at-least-once: a outbox para na 5ª tentativa, e o evento esgotado fica listável e
 * reenfileirável por ADMIN (ADR 0031). Mapper próprio porque não há bean de ObjectMapper (Jackson
 * 3).
 */
@Service
public class DespachanteAlertaService implements DespachoAlerta {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final Logger log = LoggerFactory.getLogger(DespachanteAlertaService.class);

  /** Discriminador do alerta de missão nova vinda de entrega falida. */
  static final String TIPO_ENTREGA_FALIDA = "ENTREGA_FALIDA_DISPONIVEL";

  /** Discriminador do aviso operacional de ponto lotado. Alerta GLOBAL: usuário nulo. */
  static final String TIPO_PONTO_LOTADO = "PONTO_CUSTODIA_LOTADO";

  static final String TIPO_SEM_PATROCINIO = "ENTREGA_SEM_PATROCINIO";

  private final AlertaRepository alertaRepository;
  private final ConsultasGeoespaciais consultasGeoespaciais;
  private final ConsultaConsentimento consultaConsentimento;
  private final ProgressaoUsuario progressaoUsuario;
  private final ParametrosNotificacoes parametros;

  public DespachanteAlertaService(
      AlertaRepository alertaRepository,
      // Portas de outros módulos pela INTERFACE (ArchUnit).
      ConsultasGeoespaciais consultasGeoespaciais,
      ConsultaConsentimento consultaConsentimento,
      ProgressaoUsuario progressaoUsuario,
      ParametrosNotificacoes parametros) {
    this.alertaRepository = alertaRepository;
    this.consultasGeoespaciais = consultasGeoespaciais;
    this.consultaConsentimento = consultaConsentimento;
    this.progressaoUsuario = progressaoUsuario;
    this.parametros = parametros;
  }

  /**
   * Traduz um evento em alerta. Tipo desconhecido LANÇA: o evento volta à outbox com {@code
   * ultimo_erro}, em vez de sumir.
   */
  @Override
  public void despachar(String tipoEvento, UUID agregadoId, String payloadJson) {
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = MAPPER.readValue(payloadJson, Map.class);

    switch (tipoEvento) {
      case "MissaoConcluida" -> gravarConclusao(agregadoId, payload);
      case "EntregaFalidaConvertida" -> anunciarMissaoDeRetirada(payload);
      case "EntregaFalidaRecusada" -> gravarPontoLotado(agregadoId, payload);
      case "EntregaFalidaSemPatrocinio" -> gravarSemPatrocinio(agregadoId, payload);
      default ->
          throw new IllegalStateException("Nenhum despachante para o evento " + tipoEvento + ".");
    }
  }

  private void gravarConclusao(UUID missaoId, Map<String, Object> payload) {
    UUID executorId = UUID.fromString((String) payload.get("executorId"));
    boolean subiuDeNivel = Boolean.TRUE.equals(payload.get("subiuDeNivel"));

    String corpo =
        subiuDeNivel
            ? "Missão concluída e recompensa creditada. Você subiu para o nível "
                + payload.get("nivelAtual")
                + "."
            : "Missão concluída. A recompensa já está na sua carteira.";

    alertaRepository.save(
        new Alerta(
            UUID.randomUUID(),
            executorId,
            "MISSAO_CONCLUIDA",
            "Recompensa creditada",
            corpo,
            missaoId,
            Instant.now()));
  }

  /**
   * Fan-out geográfico por TRIBO (ADR 0020): o raio é medido do ponto até as âncoras de cada tribo,
   * sem guardar onde ninguém está. Três filtros: consentimento, nível mínimo (não anunciar o que
   * daria 422) e teto por hora.
   */
  private void anunciarMissaoDeRetirada(Map<String, Object> payload) {
    UUID missaoId = UUID.fromString((String) payload.get("missaoId"));
    BigDecimal lat = new BigDecimal(String.valueOf(payload.get("lat")));
    BigDecimal lon = new BigDecimal(String.valueOf(payload.get("lon")));
    String apelidoPonto = String.valueOf(payload.get("apelidoPonto"));
    long tokens = ((Number) payload.getOrDefault("tokensRecompensa", 0)).longValue();

    // A faixa vem no payload, não de uma consulta a logistica. Ausente é normal em evento antigo.
    String faixaRisco = String.valueOf(payload.getOrDefault("faixaRisco", "BAIXO"));
    short prioridade = prioridadeDe(faixaRisco);

    List<UUID> tribos =
        consultasGeoespaciais
            .tribosNoRaio(lat, lon, parametros.raioAlertaMetros(), parametros.tribosPorEvento())
            .stream()
            .map(ConsultasGeoespaciais.AlvoProximo::id)
            .toList();

    if (tribos.isEmpty()) {
      // Nenhuma tribo no raio: não é erro — relançar falharia igual cinco vezes.
      log.debug("Missão {} sem tribo no raio de {} m", missaoId, parametros.raioAlertaMetros());
      return;
    }

    // Os DOIS consentimentos: a decisão de enviar usou a localização da tribo da pessoa.
    List<UUID> comConsentimento =
        consultaConsentimento.usuariosComConsentimento(
            tribos, List.of(ConsultaConsentimento.NOTIFICACAO, ConsultaConsentimento.LOCALIZACAO));

    // Reputação: não anunciar a quem receberia 422 ao aceitar — a regra é também de visibilidade.
    int nivelMinimo = ((Number) payload.getOrDefault("nivelMinimo", 1)).intValue();
    List<UUID> destinatarios =
        progressaoUsuario.filtrarPorNivelMinimo(comConsentimento, nivelMinimo);

    Instant agora = Instant.now();
    Instant umaHoraAtras = agora.minus(Duration.ofHours(1));
    int enviados = 0;

    for (UUID destinatario : destinatarios) {
      // Deduplicação antes do teto: um redespacho não pode consumir a cota de quem já foi avisado.
      if (alertaRepository.existsByUsuarioIdAndTipoAndMissaoId(
          destinatario, TIPO_ENTREGA_FALIDA, missaoId)) {
        continue;
      }
      // Teto por hora com exceção limitada para risco ALTO: entregas triviais não podem silenciar a
      // difícil, e a exceção tem teto próprio para não virar assédio.
      int tetoAplicavel =
          prioridade == Alerta.PRIORIDADE_ALTA
              ? parametros.alertasAltaPrioridadePorHora()
              : parametros.alertasPorHora();
      if (alertaRepository.countByUsuarioIdAndCriadoEmAfter(destinatario, umaHoraAtras)
          >= tetoAplicavel) {
        continue;
      }

      alertaRepository.save(
          new Alerta(
              UUID.randomUUID(),
              destinatario,
              TIPO_ENTREGA_FALIDA,
              tituloDe(prioridade),
              "Uma entrega falhou e o pacote está em "
                  + apelidoPonto
                  + ". Leve ao destinatário e receba "
                  + tokens
                  + " tokens mais XP."
                  + complementoDeRisco(prioridade),
              missaoId,
              agora,
              prioridade));
      enviados++;
    }

    log.info(
        "Missão {}: {} alertas enviados de {} candidatos em {} tribos",
        missaoId,
        enviados,
        destinatarios.size(),
        tribos.size());
  }

  /**
   * Faixa → prioridade. Desconhecida vira NORMAL: rótulo errado não justifica reter a notificação.
   */
  private static short prioridadeDe(String faixaRisco) {
    return switch (faixaRisco) {
      case "ALTO" -> Alerta.PRIORIDADE_ALTA;
      case "MEDIO" -> Alerta.PRIORIDADE_MEDIA;
      default -> Alerta.PRIORIDADE_NORMAL;
    };
  }

  private static String tituloDe(short prioridade) {
    return prioridade == Alerta.PRIORIDADE_ALTA
        ? "Entrega difícil esperando na sua região"
        : "Encomenda esperando na sua região";
  }

  /**
   * Complemento para risco alto, sem endereço nem CEP: o alerta vai a quem ainda não participa, e o
   * recorte por participação esconde a rua.
   */
  private static String complementoDeRisco(short prioridade) {
    return prioridade == Alerta.PRIORIDADE_ALTA
        ? " Entregas nesse endereço costumam falhar — combine o horário antes de ir."
        : "";
  }

  /**
   * Aviso operacional de ponto lotado — <b>uma linha por ponto por janela</b> (ADR 0033).
   *
   * <p>Alerta global ({@code usuario_id} nulo), sinal de operação. Era uma linha por evento, e uma
   * transportadora reenviando contra um ponto cheio virava amplificação de escrita sem teto. A
   * frequência não se perde: está em {@code entrega_falida}, lida por {@code GET
   * /admin/pontos-custodia/recusas}. O corpo não nomeia a transportadora — a linha cobre a janela.
   */
  private void gravarPontoLotado(UUID entregaFalidaId, Map<String, Object> payload) {
    Instant agora = Instant.now();
    // Ponto lido do payload para deduplicar sem parsear o texto.
    String ponto = String.valueOf(payload.get("pontoCustodiaId"));

    int gravados =
        alertaRepository.inserirOperacionalSeAusente(
            UUID.randomUUID(),
            TIPO_PONTO_LOTADO,
            "Ponto de custódia lotado",
            "O ponto "
                + payload.get("apelidoPonto")
                + " ("
                + payload.get("codigoPonto")
                + ") está recusando encomendas por falta de vaga. Capacidade: "
                + payload.get("capacidade")
                + ". Esta linha cobre a janela inteira; a contagem de recusas está no painel de"
                + " recusas por ponto.",
            ponto,
            janelaDe(agora),
            agora);

    if (gravados == 0) {
      // Dedup funcionando, não falha: DEBUG, para o WARN significar "começou a recusar agora".
      log.debug(
          "Ponto {} já tem alerta de lotação nesta janela; entrega {}", ponto, entregaFalidaId);
    } else {
      log.warn("Ponto lotado registrado para entrega falida {}", entregaFalidaId);
    }
  }

  /**
   * Aviso operacional de entrega sem patrocínio — <b>uma linha por transportadora por janela</b>.
   * Sinal para o ADMIN, que corrige com um aporte. A referência é o SLUG: SEM_PATROCINIO junta
   * inexistente, inativo e sem saldo, e no primeiro não há id. Sem saldo nem valor no corpo.
   */
  private void gravarSemPatrocinio(UUID entregaFalidaId, Map<String, Object> payload) {
    Instant agora = Instant.now();
    String transportadora = String.valueOf(payload.get("transportadora"));

    int gravados =
        alertaRepository.inserirOperacionalSeAusente(
            UUID.randomUUID(),
            TIPO_SEM_PATROCINIO,
            "Entrega sem patrocínio",
            "Encomendas de "
                + transportadora
                + " não estão virando missão por falta de patrocínio ativo. Nenhum vizinho foi"
                + " acionado.",
            transportadora,
            janelaDe(agora),
            agora);

    if (gravados == 0) {
      log.debug(
          "Transportadora {} já tem alerta de falta de patrocínio nesta janela; entrega {}",
          transportadora,
          entregaFalidaId);
    } else {
      log.warn("Entrega falida {} recusada por falta de patrocínio", entregaFalidaId);
    }
  }

  /**
   * Início da janela de deduplicação. Janelas FIXAS, não deslizantes: sob rajada contínua uma
   * janela deslizante nunca renovaria a linha. {@code floorDiv} para estar certo também antes da
   * época.
   */
  private Instant janelaDe(Instant agora) {
    long janelaSegundos = parametros.janelaAlertaOperacional().toSeconds();
    return Instant.ofEpochSecond(
        Math.floorDiv(agora.getEpochSecond(), janelaSegundos) * janelaSegundos);
  }
}
