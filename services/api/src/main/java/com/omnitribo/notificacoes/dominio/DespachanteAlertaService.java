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
 * Entrega de eventos da outbox como alerta in-app. Implementação da porta {@link DespachoAlerta}.
 *
 * <p>Destino provisório e assumido como tal: nesta fase "despachar" significa gravar uma linha em
 * {@code alerta}, a caixa de entrada do app. O push real trocaria só o corpo deste despachante — o
 * contrato do drenador e o backoff não mudam, porque é exatamente essa separação que o padrão
 * outbox compra. O que NÃO se deve repetir daqui é a palavra "at-least-once": a entrega para na
 * quinta tentativa. Desde o ADR 0031 o evento parado fica visível em {@code GET
 * /api/v1/admin/outbox/esgotados} e pode ser devolvido à fila por um ADMIN — o que muda "perda
 * silenciosa" para "perda detectável", e não para "entrega garantida", porque nada avisa que há
 * evento esgotado. Ver o javadoc de {@link com.omnitribo.compartilhado.api.PublicadorEventos},
 * seção "O LIMITE desta garantia".
 *
 * <p>O mapper é construído aqui, sem injeção: Jackson é o 3 (tools.jackson) em todo o repositório e
 * não existe bean de ObjectMapper para injetar. Mesmo padrão de {@code
 * MissaoService.MAPPER_TRILHA}.
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
      // Pela INTERFACE: as três são portas de outros módulos, e é o tipo declarado aqui que o
      // ArchUnit inspeciona.
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
   * Traduz um evento em alerta. Tipo desconhecido lança, e isso é intencional: o evento volta para
   * a outbox com backoff em vez de ser descartado, e {@code ultimo_erro} registra o que faltou.
   * Descartar em silêncio perderia um fato que o resto do sistema já considera consumado.
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
   * Fan-out geográfico: avisa quem está perto que há uma encomenda esperando alguém buscar.
   *
   * <p><b>Quem é "perto" sem que o usuário tenha coordenada.</b> A tabela {@code usuario} não tem
   * coluna geográfica. O raio é medido do ponto de custódia até o CENTRO DERIVADO de cada tribo, e
   * notifica os membros dela — granularidade de bairro, não de pessoa. É a decisão do ADR 0020, não
   * uma limitação a corrigir: notificar por tribo não exige armazenar onde ninguém está.
   *
   * <p><b>Três filtros, e cada um recusa por um motivo diferente.</b> Consentimento é permissão;
   * nível mínimo é a Regra de Elegibilidade por Reputação do challenge — não adianta anunciar uma
   * missão que a pessoa levaria 422 ao tentar aceitar; teto por hora é respeito ao canal.
   */
  private void anunciarMissaoDeRetirada(Map<String, Object> payload) {
    UUID missaoId = UUID.fromString((String) payload.get("missaoId"));
    BigDecimal lat = new BigDecimal(String.valueOf(payload.get("lat")));
    BigDecimal lon = new BigDecimal(String.valueOf(payload.get("lon")));
    String apelidoPonto = String.valueOf(payload.get("apelidoPonto"));
    long tokens = ((Number) payload.getOrDefault("tokensRecompensa", 0)).longValue();

    // A faixa vem no payload do evento, e não de uma consulta a `logistica`: a outbox existe
    // justamente para o despachante não depender do módulo que publicou o fato. Ausente é NORMAL —
    // eventos gravados antes desta fase continuam drenáveis, e um evento antigo não pode fazer o
    // job explodir.
    String faixaRisco = String.valueOf(payload.getOrDefault("faixaRisco", "BAIXO"));
    short prioridade = prioridadeDe(faixaRisco);

    List<UUID> tribos =
        consultasGeoespaciais
            .tribosNoRaio(lat, lon, parametros.raioAlertaMetros(), parametros.tribosPorEvento())
            .stream()
            .map(ConsultasGeoespaciais.AlvoProximo::id)
            .toList();

    if (tribos.isEmpty()) {
      // Nenhuma tribo com centro derivado no raio. Acontece de verdade — tribo sem missão nem ponto
      // não tem centro —, e não é erro: lançar devolveria o evento à outbox para tentar de novo, e
      // as cinco tentativas fracassariam igual.
      log.debug("Missão {} sem tribo no raio de {} m", missaoId, parametros.raioAlertaMetros());
      return;
    }

    // Os DOIS consentimentos: NOTIFICACAO porque é uma notificação, LOCALIZACAO porque a decisão de
    // enviar usou a posição da tribo da pessoa. Exigir só o primeiro trataria a inferência
    // geográfica como se não fosse uso de dado de localização.
    List<UUID> comConsentimento =
        consultaConsentimento.usuariosComConsentimento(
            tribos, List.of(ConsultaConsentimento.NOTIFICACAO, ConsultaConsentimento.LOCALIZACAO));

    // Segundo filtro: reputação. Anunciar a missão a quem não alcança o nível mínimo seria prometer
    // o que o servidor recusa com 422 no toque seguinte — e a Regra de Elegibilidade por Reputação
    // do challenge não é só sobre aceitar, é sobre VISIBILIDADE: "missões que envolvam custódia de
    // pacotes físicos não são visíveis para toda a base".
    int nivelMinimo = ((Number) payload.getOrDefault("nivelMinimo", 1)).intValue();
    List<UUID> destinatarios =
        progressaoUsuario.filtrarPorNivelMinimo(comConsentimento, nivelMinimo);

    Instant agora = Instant.now();
    Instant umaHoraAtras = agora.minus(Duration.ofHours(1));
    int enviados = 0;

    for (UUID destinatario : destinatarios) {
      // Deduplicação antes do teto, e não depois: um redespacho da outbox não pode consumir a cota
      // de quem já foi avisado, senão uma falha transitória de infraestrutura silencia
      // notificações legítimas pela hora seguinte.
      if (alertaRepository.existsByUsuarioIdAndTipoAndMissaoId(
          destinatario, TIPO_ENTREGA_FALIDA, missaoId)) {
        continue;
      }
      // Teto por hora COM carve-out para risco ALTO, e a assimetria é o ponto.
      //
      // Sem ele, cinco entregas triviais chegando primeiro silenciariam a difícil pela hora
      // seguinte — exatamente a que mais precisa de alguém e a que paga melhor. O carve-out é
      // limitado por um teto próprio, não é isenção: uma rajada de entregas de alto risco no mesmo
      // ponto continua sem virar assédio de notificação.
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
   * Faixa de risco → prioridade do alerta.
   *
   * <p>Faixa desconhecida vira NORMAL em vez de lançar: o drenador da outbox tem cinco tentativas e
   * backoff, então uma exceção aqui reprocessaria o mesmo evento cinco vezes antes de desistir — e
   * o defeito seria um rótulo de prioridade, não algo que justifique reter uma notificação.
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
   * Complemento do corpo para risco alto.
   *
   * <p>Sem endereço, sem CEP e sem contagem por logradouro — de propósito. O alerta vai para quem
   * ainda NÃO aceitou a missão, e {@code MissaoResponse} recorta endereço a bairro para quem não
   * participa. Uma notificação dizendo "a rua X falhou três vezes" devolveria por outra porta
   * exatamente o que aquele recorte protege.
   */
  private static String complementoDeRisco(short prioridade) {
    return prioridade == Alerta.PRIORIDADE_ALTA
        ? " Entregas nesse endereço costumam falhar — combine o horário antes de ir."
        : "";
  }

  /**
   * Aviso operacional de ponto lotado — <b>uma linha por ponto por janela</b>.
   *
   * <p>Alerta GLOBAL: {@code usuario_id} nulo, que a V7 permite de propósito. Não é notificação de
   * usuário, é sinal de operação — um ponto que recusa encomendas com frequência é exatamente o
   * dado que justifica negociar mais capacidade ou abrir outro ponto no bairro.
   *
   * <p><b>Era uma linha por EVENTO, e isso apagava o dado em vez de registrá-lo.</b> O teste de
   * carga de 2026-08-25 gravou 631 linhas idênticas sobre o mesmo ponto em menos de 3 minutos
   * ({@code docs/evidencias/f21-carga.md} §6): uma transportadora reenviando contra um ponto cheio
   * é amplificação de escrita sem teto, disparada por evento externo que o sistema não controla.
   *
   * <p><b>Por que a frequência não se perde ao deduplicar.</b> Ela nunca esteve só aqui: {@code
   * entrega_falida} grava TODA recusa, com ponto, instante e motivo. O número é derivável de lá e é
   * o que {@code GET /api/v1/admin/pontos-custodia/recusas} devolve — sem tabela de agregação, pelo
   * mesmo argumento do ADR 0029. Ver ADR 0033.
   *
   * <p><b>O corpo deixou de nomear a transportadora, e a omissão é obrigatória.</b> A linha agora
   * representa a JANELA, não um evento: dentro dela cabem recusas de transportadoras diferentes.
   * Manter o nome de uma delas seria uma afirmação falsa sobre as outras — a linha diria "recusou
   * uma encomenda de X" enquanto cobre também as de Y.
   */
  private void gravarPontoLotado(UUID entregaFalidaId, Map<String, Object> payload) {
    Instant agora = Instant.now();
    // O pontoCustodiaId já vinha no payload da outbox desde a V21 e nunca era lido — era o que
    // faltava para deduplicar por ponto sem ter de parsear a frase do corpo.
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
      // Não é falha: é a dedup funcionando. Fica em DEBUG para que o WARN continue significando
      // "este ponto começou a recusar agora", que é o que merece atenção.
      log.debug(
          "Ponto {} já tem alerta de lotação nesta janela; entrega {}", ponto, entregaFalidaId);
    } else {
      log.warn("Ponto lotado registrado para entrega falida {}", entregaFalidaId);
    }
  }

  /**
   * Aviso operacional de entrega recusada por falta de patrocínio — <b>uma linha por transportadora
   * por janela</b>.
   *
   * <p>Alerta GLOBAL, como o de ponto lotado, e pela mesma razão: é sinal de OPERAÇÃO, não
   * notificação de usuário. Uma transportadora cujo patrocinador ficou sem saldo para de gerar
   * missões silenciosamente — a encomenda continua na loja, o vizinho nunca é chamado, e a única
   * pista seria uma linha de {@code entrega_falida} que ninguém abre. É o ADMIN que precisa saber,
   * porque a correção é dele: um aporte.
   *
   * <p><b>Deduplicado junto com o de ponto lotado, e não depois.</b> Este caminho tinha exatamente
   * a mesma forma — {@code save} incondicional por evento, sem teto — e só não apareceu na medição
   * de §6 porque a rajada foi contra um ponto cheio, e não contra uma transportadora sem
   * patrocinador. Corrigir um e deixar o outro reabriria a mesma pendência com outro nome.
   *
   * <p><b>A referência é o SLUG da transportadora, não um id de patrocinador.</b> {@code
   * MotivoRecusa.SEM_PATROCINIO} colapsa de propósito três causas — patrocinador inexistente,
   * desativado e sem fundos — e na primeira delas não existe patrocinador para referenciar. O slug
   * é o único identificador presente nos três casos.
   *
   * <p>O corpo NÃO diz saldo nem valor, pelo mesmo motivo que o motivo colapsa as três causas: o
   * alerta é lido por gente que não precisa do estado financeiro de um parceiro para agir.
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
   * Trunca o instante para o início da janela de deduplicação corrente.
   *
   * <p>Janelas FIXAS ancoradas na época, e não uma janela deslizante contada a partir do último
   * alerta. A diferença aparece sob rajada contínua: com janela deslizante, um fluxo constante de
   * recusas empurraria o marco para sempre e a linha nunca seria renovada — o sinal congelaria no
   * primeiro alerta e "este ponto ainda está cheio" deixaria de ser dito. Com janela fixa, cada
   * hora produz no máximo uma linha e no mínimo nenhuma, que é o que a lista precisa mostrar.
   *
   * <p>{@code floorDiv}, e não divisão inteira comum: para instantes anteriores à época o
   * truncamento por divisão simples arredondaria na direção errada. Não acontece em produção, mas o
   * custo de estar certo aqui é zero e o de descobrir depois não é.
   */
  private Instant janelaDe(Instant agora) {
    long janelaSegundos = parametros.janelaAlertaOperacional().toSeconds();
    return Instant.ofEpochSecond(
        Math.floorDiv(agora.getEpochSecond(), janelaSegundos) * janelaSegundos);
  }
}
