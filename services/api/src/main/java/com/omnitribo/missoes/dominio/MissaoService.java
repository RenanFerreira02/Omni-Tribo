package com.omnitribo.missoes.dominio;

import com.omnitribo.compartilhado.api.PaginaResponse;
import com.omnitribo.compartilhado.dominio.Auditavel;
import com.omnitribo.compartilhado.dominio.Coordenadas;
import com.omnitribo.compartilhado.dominio.Geohash;
import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import com.omnitribo.compartilhado.infra.ConsultasGeoespaciais;
import com.omnitribo.compartilhado.infra.ConsultasGeoespaciais.AlvoProximo;
import com.omnitribo.geolocalizacao.api.ComandoCheckin;
import com.omnitribo.geolocalizacao.api.RegistroCheckin;
import com.omnitribo.geolocalizacao.api.ResultadoCheckin;
import com.omnitribo.missoes.api.AtualizarMissaoRequest;
import com.omnitribo.missoes.api.CriarMissaoRequest;
import com.omnitribo.missoes.api.MissaoFiltroRequest;
import com.omnitribo.missoes.api.MissaoProximaFiltroRequest;
import com.omnitribo.missoes.api.MissaoProximaResponse;
import com.omnitribo.missoes.api.MissaoResponse;
import com.omnitribo.missoes.api.RegistrarCheckinRequest;
import com.omnitribo.missoes.api.ResolverDisputaRequest;
import com.omnitribo.missoes.api.ResultadoRegistroCheckin;
import com.omnitribo.missoes.infra.CacheMissoesProximas;
import com.omnitribo.missoes.infra.ChaveProximidade;
import com.omnitribo.missoes.infra.MissaoEventoRepository;
import com.omnitribo.missoes.infra.MissaoRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Point;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Orquestra o ciclo de vida de missões. Toda mudança de status passa pela máquina de estados. */
@Service
public class MissaoService {

  private static final String NAO_ENCONTRADA = "Missão não encontrada.";

  /**
   * Mapper próprio, não injetado.
   *
   * <p>Duas razões. Primeira, técnica: o Spring Boot 4.1 autoconfigura o mapper do Jackson 3
   * (tools.jackson), então não existe bean de com.fasterxml.jackson.databind.ObjectMapper para
   * injetar. Segunda, de projeto: o payload gravado na trilha append-only é registro histórico e
   * não pode mudar de formato quando alguém ajustar a serialização da API.
   */
  private static final JsonMapper MAPPER_TRILHA = JsonMapper.builder().build();

  private final MissaoRepository missaoRepository;
  private final MissaoEventoRepository missaoEventoRepository;
  private final ConsultasGeoespaciais consultasGeoespaciais;
  private final CacheMissoesProximas cacheMissoesProximas;

  // Injetado pela INTERFACE: a regra ArchUnit proíbe missoes.dominio de tocar
  // geolocalizacao.dominio, e é o tipo declarado aqui que o ArchUnit inspeciona.
  private final RegistroCheckin registroCheckin;

  public MissaoService(
      MissaoRepository missaoRepository,
      MissaoEventoRepository missaoEventoRepository,
      ConsultasGeoespaciais consultasGeoespaciais,
      CacheMissoesProximas cacheMissoesProximas,
      RegistroCheckin registroCheckin) {
    this.missaoRepository = missaoRepository;
    this.missaoEventoRepository = missaoEventoRepository;
    this.consultasGeoespaciais = consultasGeoespaciais;
    this.cacheMissoesProximas = cacheMissoesProximas;
    this.registroCheckin = registroCheckin;
  }

  // A trilha de auditoria fica no serviço, não no controller: é onde a escrita acontece e onde o
  // proxy do @Transactional já existe. Anotar o controller criaria um proxy CGLIB sobre o MVC sem
  // ganho nenhum. Só escrita é auditada; leitura não.
  @Auditavel(acao = "MISSAO_CRIADA", entidade = "missao")
  @Transactional
  public MissaoResponse criar(CriarMissaoRequest req, AtorMissao ator) {
    Instant agora = Instant.now();

    // Toda missão nasce RASCUNHO, e o criador é sempre o dono do JWT. Nenhum dos dois é
    // aceito do corpo da requisição.
    Missao missao =
        new Missao(
            UUID.randomUUID(),
            ator.usuarioId(),
            req.categoria(),
            req.titulo(),
            req.descricao(),
            StatusMissao.RASCUNHO,
            req.xpRecompensa(),
            req.valorBrl(),
            req.tokensRecompensa(),
            Coordenadas.ponto(req.origemLat(), req.origemLon()),
            Coordenadas.ponto(req.destinoLat(), req.destinoLon()),
            req.pontoCustodiaId(),
            req.cep(),
            req.logradouro(),
            req.bairro(),
            req.cidade(),
            req.uf(),
            req.raioCheckinM(),
            req.pesoKg(),
            req.volumeL(),
            req.janelaInicio(),
            req.janelaFim(),
            agora);

    Missao salva = missaoRepository.save(missao);

    // No-op semântico hoje: a missão nasce RASCUNHO e o radar só devolve ABERTA, então não há
    // entrada de cache que esta criação possa invalidar. Fica aqui de propósito — no dia em que
    // `criar` aceitar outro status inicial, o gancho já está no lugar certo.
    cacheMissoesProximas.invalidarAposCommit();

    return MissaoResponse.de(salva);
  }

  @Transactional(readOnly = true)
  public PaginaResponse<MissaoResponse> listar(MissaoFiltroRequest filtro, AtorMissao ator) {
    // O escopo é traduzido aqui a partir do ator do JWT — o cliente nunca informa um id de
    // usuário para filtrar, senão listaria as missões de qualquer pessoa.
    UUID criadorId =
        filtro.minhas() == MissaoFiltroRequest.Escopo.CRIADAS ? ator.usuarioId() : null;
    UUID executorId =
        filtro.minhas() == MissaoFiltroRequest.Escopo.EXECUTANDO ? ator.usuarioId() : null;

    PageRequest pagina =
        PageRequest.of(
            filtro.pagina(),
            filtro.tamanho(),
            Sort.by(filtro.direcao(), filtro.ordenarPor().propriedade()));

    Page<MissaoResponse> page =
        missaoRepository
            .buscarComFiltros(
                filtro.status(),
                filtro.categoria(),
                filtro.cidade(),
                filtro.bairro(),
                criadorId,
                executorId,
                ator.usuarioId(),
                pagina)
            .map(MissaoResponse::de);

    return PaginaResponse.de(page);
  }

  @Transactional(readOnly = true)
  public MissaoResponse buscarPorId(UUID missaoId, AtorMissao ator) {
    return MissaoResponse.de(carregarVisivel(missaoId, ator));
  }

  /**
   * Missões ABERTA dentro do raio, da mais próxima para a mais distante.
   *
   * <p>Duas consultas de propósito. A primeira, no PostGIS, devolve só (id, distância) — porque
   * ConsultasGeoespaciais mora em compartilhado e a regra ArchUnit a proíbe de conhecer Missao. A
   * segunda reidrata as entidades e reusa MissaoResponse.de, o que mantém uma única representação
   * de leitura da missão na API e evita trafegar enum como String.
   *
   * <p>Nenhum filtro de visibilidade é necessário: só ABERTA sai daqui, e ABERTA é pública. É
   * também o que mantém o resultado independente do solicitante — ver ChaveProximidade.
   */
  @Transactional(readOnly = true)
  public List<MissaoProximaResponse> buscarProximas(MissaoProximaFiltroRequest filtro) {
    ChaveProximidade chave =
        new ChaveProximidade(
            Geohash.celulaDeCache(filtro.lat(), filtro.lon()),
            filtro.raioMetros(),
            filtro.categoria(),
            filtro.limite());

    return cacheMissoesProximas.obter(chave, ignorada -> consultarProximas(filtro));
  }

  private List<MissaoProximaResponse> consultarProximas(MissaoProximaFiltroRequest filtro) {
    List<AlvoProximo> alvos =
        consultasGeoespaciais.missoesNoRaio(
            filtro.lat(),
            filtro.lon(),
            filtro.raioMetros(),
            StatusMissao.ABERTA.name(),
            filtro.categoria() == null ? null : filtro.categoria().name(),
            filtro.limite());

    if (alvos.isEmpty()) {
      return List.of();
    }

    Map<UUID, Missao> porId =
        missaoRepository.findAllById(alvos.stream().map(AlvoProximo::id).toList()).stream()
            .collect(Collectors.toMap(Missao::getId, m -> m));

    // Itera sobre `alvos`, NÃO sobre porId.values(): é esta iteração que preserva a ordenação por
    // distância vinda do PostGIS. findAllById não promete ordem nenhuma.
    return alvos.stream()
        .map(
            alvo ->
                new MissaoProximaResponse(
                    MissaoResponse.de(porId.get(alvo.id())),
                    BigDecimal.valueOf(alvo.distanciaM()).setScale(1, RoundingMode.HALF_UP)))
        .toList();
  }

  @Auditavel(acao = "MISSAO_ATUALIZADA", entidade = "missao")
  @Transactional
  public MissaoResponse atualizar(UUID missaoId, AtualizarMissaoRequest req, AtorMissao ator) {
    Missao missao =
        missaoRepository
            .buscarParaAtualizar(missaoId)
            .orElseThrow(() -> new RecursoNaoEncontradoException(NAO_ENCONTRADA));

    MissaoStateMachine.validarEdicao(missao, ator);

    // Campos nulos preservam o valor atual. Recompensa, categoria, status e executor não estão
    // no DTO: enviá-los no JSON não altera nada.
    Point origem =
        req.origemLat() != null
            ? Coordenadas.ponto(req.origemLat(), req.origemLon())
            : missao.getOrigem();
    Point destino =
        req.destinoLat() != null
            ? Coordenadas.ponto(req.destinoLat(), req.destinoLon())
            : missao.getDestino();

    missao.editarRascunho(
        ouAtual(req.titulo(), missao.getTitulo()),
        ouAtual(req.descricao(), missao.getDescricao()),
        ouAtual(req.cep(), missao.getCep()),
        ouAtual(req.logradouro(), missao.getLogradouro()),
        ouAtual(req.bairro(), missao.getBairro()),
        ouAtual(req.cidade(), missao.getCidade()),
        ouAtual(req.uf(), missao.getUf()),
        origem,
        destino,
        ouAtual(req.janelaInicio(), missao.getJanelaInicio()),
        ouAtual(req.janelaFim(), missao.getJanelaFim()),
        req.raioCheckinM() != null ? req.raioCheckinM() : missao.getRaioCheckinM(),
        ouAtual(req.pesoKg(), missao.getPesoKg()),
        ouAtual(req.volumeL(), missao.getVolumeL()));

    Missao salva = missaoRepository.save(missao);

    // Obrigatório, e não é óbvio: este PATCH move `origem` e `raioCheckinM` sem mudar status
    // nenhum. Uma missão ABERTA que muda de lugar entra e sai de raios de busca — o resultado do
    // radar muda sem que nenhuma transição tenha ocorrido.
    cacheMissoesProximas.invalidarAposCommit();

    return MissaoResponse.de(salva);
  }

  @Auditavel(acao = "MISSAO_PUBLICADA", entidade = "missao")
  @Transactional
  public MissaoResponse publicar(UUID missaoId, AtorMissao ator) {
    return aplicar(missaoId, EventoMissao.PUBLICAR, ator, null);
  }

  @Auditavel(acao = "MISSAO_ACEITA", entidade = "missao")
  @Transactional
  public MissaoResponse aceitar(UUID missaoId, AtorMissao ator) {
    return aplicar(missaoId, EventoMissao.ACEITAR, ator, null);
  }

  @Auditavel(acao = "MISSAO_INICIADA", entidade = "missao")
  @Transactional
  public MissaoResponse iniciar(UUID missaoId, AtorMissao ator) {
    return aplicar(missaoId, EventoMissao.INICIAR, ator, null);
  }

  @Auditavel(acao = "MISSAO_DESISTIDA", entidade = "missao")
  @Transactional
  public MissaoResponse desistir(UUID missaoId, AtorMissao ator, String motivo) {
    return aplicar(missaoId, EventoMissao.DESISTIR, ator, payloadMotivo(motivo));
  }

  @Auditavel(acao = "MISSAO_CANCELADA", entidade = "missao")
  @Transactional
  public MissaoResponse cancelar(UUID missaoId, AtorMissao ator, String motivo) {
    return aplicar(missaoId, EventoMissao.CANCELAR, ator, payloadMotivo(motivo));
  }

  @Auditavel(acao = "MISSAO_CONTESTADA", entidade = "missao")
  @Transactional
  public MissaoResponse contestar(UUID missaoId, AtorMissao ator, String motivo) {
    return aplicar(missaoId, EventoMissao.CONTESTAR, ator, payloadMotivo(motivo));
  }

  // ─── Check-in (F6) e stubs de F7 ───────────────────────────────────────────────────────────
  // confirmar e resolverDisputa seguem publicando contrato e respondendo 501: validam 403 e 409
  // antes, então o app mobile já integra a ordem de checagens definitiva.
  //
  // Os dois seguem sem @Auditavel de propósito: sempre lançam UnsupportedOperationException, e o
  // aspecto é @AfterReturning — anotá-los agora criaria advice que nunca dispara. A anotação entra
  // junto com o corpo real, em F7.

  /**
   * Check-in geolocalizado. EM_ANDAMENTO → AGUARDANDO_CONFIRMACAO.
   *
   * <p>UMA transação, UMA conexão, e a linha de auditoria commitada mesmo quando o check-in é
   * recusado. Isso funciona porque a rejeição volta como VALOR, não como exceção: lançar daqui de
   * dentro apagaria a linha no rollback. Quem transforma a recusa em 422 é o controller, depois do
   * commit. Ver {@link ResultadoRegistroCheckin}.
   *
   * <p>O caminho aceito é atômico: linha de check-in, transição e trilha entram no mesmo commit.
   */
  @Auditavel(acao = "MISSAO_CHECKIN", entidade = "missao")
  @Transactional
  public ResultadoRegistroCheckin registrarCheckin(
      UUID missaoId, AtorMissao ator, RegistrarCheckinRequest req, String chaveCliente) {

    // PRIMEIRA leitura da transação, obrigatoriamente — mesmo motivo documentado em aplicar().
    Missao missao =
        missaoRepository
            .buscarParaAtualizar(missaoId)
            .orElseThrow(() -> new RecursoNaoEncontradoException(NAO_ENCONTRADA));

    // Rascunho alheio responde 404, não 403 — mesma regra de carregarVisivel(). Sem isto, um 403
    // aqui confirmaria a existência de um rascunho que o solicitante não deveria sequer saber que
    // existe. Vai depois do buscarParaAtualizar para preservar o invariante do lock ser a primeira
    // leitura da transação.
    if (missao.getStatus() == StatusMissao.RASCUNHO && !ator.ehMesmo(missao.getCriadorId())) {
      throw new RecursoNaoEncontradoException(NAO_ENCONTRADA);
    }

    // 403 SEMPRE primeiro, antes até da sondagem de idempotência: sondar antes disso permitiria
    // devolver o estado da missão a quem não é (ou não é mais) o executor.
    MissaoStateMachine.validarAutorizacao(missao, EventoMissao.CHECKIN, ator);

    String chave = chaveIdempotencia(ator.usuarioId(), missaoId, chaveCliente);

    // Sondagem ENTRE o 403 e o 409, e a ordem é o ponto todo. Um replay legítimo chega com a missão
    // já em AGUARDANDO_CONFIRMACAO; se a máquina de estados falasse primeiro, o retry natural do
    // cliente móvel levaria 409 em vez do mesmo 200 de antes.
    Optional<ResultadoCheckin> jaRegistrado = registroCheckin.consultar(chave);
    if (jaRegistrado.isPresent()) {
      ResultadoCheckin anterior = jaRegistrado.get();

      if (!anterior.aceito()) {
        // Replay de uma rejeição: mesmo 422, mesmo motivo, nenhuma linha nova. Idempotência vale
        // para o fracasso também, senão um retry de rede inventaria um padrão de tentativas.
        return ResultadoRegistroCheckin.rejeitado(
            MissaoResponse.de(missao), anterior.motivoRejeicao());
      }
      // Replay de um aceito: a missão já transicionou no mesmo commit que gravou a linha, então
      // basta devolver o estado atual. Nenhuma linha nova, nenhum evento novo de trilha.
      return ResultadoRegistroCheckin.aceito(MissaoResponse.de(missao));
    }

    // 409 só agora, e antes de qualquer escrita. Ator errado ou estado errado não geram linha em
    // `checkin`: não são tentativas de check-in que falharam na validação geoespacial, são chamadas
    // de quem não é o executor ou de missão fora de EM_ANDAMENTO — e não haveria distancia_alvo_m
    // (NOT NULL) a gravar.
    MissaoStateMachine.validar(missao, EventoMissao.CHECKIN, ator);

    ResultadoCheckin resultado =
        registroCheckin.registrar(
            new ComandoCheckin(
                missaoId,
                ator.usuarioId(),
                req.lat(),
                req.lon(),
                req.acuraciaM(),
                req.mockedOuFalso(),
                Coordenadas.latitude(missao.getOrigem()),
                Coordenadas.longitude(missao.getOrigem()),
                missao.getRaioCheckinM(),
                chave,
                Instant.now()));

    if (!resultado.aceito()) {
      // NÃO lança: a linha em `checkin` acabou de ser gravada NESTA transação, e uma exceção aqui
      // a levaria embora no rollback. A transação commita normalmente — sem transição, porque o
      // check-in foi recusado — e o controller devolve 422 depois. É o que preserva a trilha
      // antifraude sem precisar de segunda conexão.
      return ResultadoRegistroCheckin.rejeitado(
          MissaoResponse.de(missao), resultado.motivoRejeicao());
    }

    MissaoEvento trilha =
        MissaoStateMachine.transicionar(
            missao,
            EventoMissao.CHECKIN,
            ator,
            serializar(payloadCheckin(resultado)),
            Instant.now());

    missaoRepository.save(missao);
    missaoEventoRepository.save(trilha);
    cacheMissoesProximas.invalidarAposCommit();

    return ResultadoRegistroCheckin.aceito(MissaoResponse.de(missao));
  }

  /**
   * Chave de idempotência derivada, não a chave crua do cliente.
   *
   * <p>A constraint uk_checkin_idempotencia é de coluna única e namespace global. Guardar a chave
   * como veio deixaria o cliente que envia "1" colidir com todo outro cliente que envia "1" — e a
   * segunda chamada receberia como resposta o replay do check-in de outra pessoa. Compondo com
   * usuário e missão antes do hash, a colisão entre usuários é impossível por construção.
   */
  private static String chaveIdempotencia(UUID usuarioId, UUID missaoId, String chaveCliente) {
    String composta = usuarioId + ":" + missaoId + ":" + chaveCliente;
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(composta.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 indisponível na JVM", e);
    }
  }

  /**
   * Payload da trilha. É AQUI que o motivo de uma suspeita fica registrado: motivo_rejeicao precisa
   * continuar nulo quando valido=true, senão "motivo_rejeicao IS NOT NULL" deixa de significar
   * "rejeitado" em toda consulta de fraude escrita depois.
   */
  private static Map<String, Object> payloadCheckin(ResultadoCheckin resultado) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("checkinId", resultado.checkinId().toString());
    payload.put("distanciaM", resultado.distanciaM());
    if (resultado.suspeito()) {
      payload.put("suspeito", true);
      payload.put("velocidadeImplicitaKmh", resultado.velocidadeImplicitaKmh());
    }
    return payload;
  }

  @Transactional(readOnly = true)
  public MissaoResponse confirmar(UUID missaoId, AtorMissao ator) {
    Missao missao = carregarVisivel(missaoId, ator);
    MissaoStateMachine.validar(missao, EventoMissao.CONFIRMAR, ator);
    throw new UnsupportedOperationException(
        "TODO(F7): confirmar credita BRL, tokens e XP na carteira do executor antes de concluir.");
  }

  @Transactional(readOnly = true)
  public MissaoResponse resolverDisputa(
      UUID missaoId, AtorMissao ator, ResolverDisputaRequest req) {
    Missao missao = carregarVisivel(missaoId, ator);
    EventoMissao evento =
        req.resultado() == ResolverDisputaRequest.Resultado.CONCLUIR
            ? EventoMissao.RESOLVER_CONCLUIR
            : EventoMissao.RESOLVER_CANCELAR;
    MissaoStateMachine.validar(missao, evento, ator);
    throw new UnsupportedOperationException(
        "TODO(F7): resolução de disputa credita ou estorna na carteira conforme o resultado.");
  }

  // ─── Núcleo ────────────────────────────────────────────────────────────────────────────────

  private MissaoResponse aplicar(
      UUID missaoId, EventoMissao evento, AtorMissao ator, Map<String, Object> payload) {

    // FOR UPDATE, e obrigatoriamente a PRIMEIRA leitura da transação: se a entidade já estivesse
    // no persistence context, o Hibernate devolveria a instância em cache sem reemitir o
    // SELECT ... FOR UPDATE, e o lock nunca seria adquirido.
    Missao missao =
        missaoRepository
            .buscarParaAtualizar(missaoId)
            .orElseThrow(() -> new RecursoNaoEncontradoException(NAO_ENCONTRADA));

    MissaoEvento trilha =
        MissaoStateMachine.transicionar(missao, evento, ator, serializar(payload), Instant.now());

    missaoRepository.save(missao);
    // Mesma transação da missão: status e trilha não têm como divergir.
    missaoEventoRepository.save(trilha);

    // Ponto único de invalidação para publicar/aceitar/iniciar/desistir/cancelar/contestar — todas
    // entram ou saem de ABERTA, que é o que o radar devolve.
    cacheMissoesProximas.invalidarAposCommit();

    return MissaoResponse.de(missao);
  }

  /**
   * Carrega respeitando a visibilidade: rascunho alheio responde 404, não 403. Um 403 confirmaria a
   * existência de um recurso privado a quem não deveria sequer saber que ele existe.
   */
  private Missao carregarVisivel(UUID missaoId, AtorMissao ator) {
    Missao missao =
        missaoRepository
            .findById(missaoId)
            .orElseThrow(() -> new RecursoNaoEncontradoException(NAO_ENCONTRADA));

    if (missao.getStatus() == StatusMissao.RASCUNHO && !ator.ehMesmo(missao.getCriadorId())) {
      throw new RecursoNaoEncontradoException(NAO_ENCONTRADA);
    }
    return missao;
  }

  private static Map<String, Object> payloadMotivo(String motivo) {
    if (motivo == null || motivo.isBlank()) {
      return null;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("motivo", motivo);
    return payload;
  }

  private static String serializar(Map<String, Object> payload) {
    if (payload == null || payload.isEmpty()) {
      return null;
    }
    return MAPPER_TRILHA.writeValueAsString(payload);
  }

  private static <T> T ouAtual(T novo, T atual) {
    return novo != null ? novo : atual;
  }
}
