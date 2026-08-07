package com.omnitribo.missoes.dominio;

import com.omnitribo.carteira.api.ComandoCreditoConclusao;
import com.omnitribo.carteira.api.CreditoRecompensa;
import com.omnitribo.carteira.api.ResultadoCredito;
import com.omnitribo.compartilhado.api.PaginaResponse;
import com.omnitribo.compartilhado.api.PublicadorEventos;
import com.omnitribo.compartilhado.dominio.Auditavel;
import com.omnitribo.compartilhado.dominio.ChaveIdempotencia;
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

  /**
   * Missão TRIBO/COLETA só é publicável com o pote já cobrindo a recompensa em tokens.
   *
   * <p>Fecha um beco sem saída que a conservação da moeda cria. Como a conclusão paga DO POTE e não
   * cunha token, uma missão publicada com pote menor que a recompensa chegaria a
   * AGUARDANDO_CONFIRMACAO e o {@code /confirmar} falharia com 422 para sempre — e as únicas saídas
   * desse estado são CONFIRMAR e CONTESTAR, então só um admin destravaria, via disputa.
   *
   * <p>Recusar aqui é falhar cedo: no publicar, o criador ainda pode financiar ou baixar a
   * recompensa. Depois de alguém ter executado a missão, não pode mais.
   */
  private static void validarPoteSuficienteParaPublicar(Missao missao) {
    if (!pagaTokensDoPote(missao)) {
      return;
    }
    long recompensa = missao.getTokensRecompensa();
    if (recompensa > 0 && missao.getPoteTokens() < recompensa) {
      throw new RegraNegocioVioladaException(
          "Missão "
              + missao.getCategoria()
              + " precisa do pote financiado antes de publicar: recompensa de "
              + recompensa
              + " tokens contra pote de "
              + missao.getPoteTokens()
              + ".");
    }
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

  // ─── Conclusão com crédito ─────────────────────────────────────────────────────────────────

  /** Criador confirma a entrega: AGUARDANDO_CONFIRMACAO → CONCLUIDA, creditando o executor. */
  @Auditavel(acao = "MISSAO_CONFIRMADA", entidade = "missao")
  @Transactional
  public MissaoResponse confirmar(UUID missaoId, AtorMissao ator) {
    return concluirComCredito(missaoId, EventoMissao.CONFIRMAR, ator, null);
  }

  /** Admin resolve a disputa. CONCLUIR credita como a confirmação; CANCELAR não credita nada. */
  @Auditavel(acao = "DISPUTA_RESOLVIDA", entidade = "missao")
  @Transactional
  public MissaoResponse resolverDisputa(
      UUID missaoId, AtorMissao ator, ResolverDisputaRequest req) {

    if (req.resultado() == ResolverDisputaRequest.Resultado.CONCLUIR) {
      return concluirComCredito(
          missaoId,
          EventoMissao.RESOLVER_CONCLUIR,
          ator,
          payloadJustificativa(req.justificativa()));
    }
    // RESOLVER_CANCELAR não credita ninguém — cai no caminho comum, que estorna o pote se houver.
    return aplicar(
        missaoId, EventoMissao.RESOLVER_CANCELAR, ator, payloadJustificativa(req.justificativa()));
  }

  /**
   * Conclusão de missão com crédito, em UMA transação.
   *
   * <p>Ordem obrigatória, e cada passo depende do anterior:
   *
   * <ol>
   *   <li><b>Travar a missão</b> ({@code FOR UPDATE}, primeira leitura). É a linha serializadora:
   *       duas conclusões concorrentes da mesma missão passam por aqui uma de cada vez.
   *   <li><b>Autorizar</b> (403) — antes de qualquer coisa que revele estado.
   *   <li><b>Sondar o replay</b>. Sob o lock, então é autoritativo. Se o crédito já existe, devolve
   *       o estado atual: um retry de rede é a MESMA operação, não conflito. Sondar antes de checar
   *       a transição é o que evita devolver 409 para quem só perdeu a resposta.
   *   <li><b>Validar a transição</b> (409).
   *   <li><b>Debitar o pote</b>, se a missão paga em tokens de custódia.
   *   <li><b>Creditar a carteira</b> (ordem global: missao → carteira).
   *   <li><b>Conceder XP</b> (missao → carteira → usuario).
   *   <li><b>Transicionar</b> e gravar a trilha.
   *   <li><b>Publicar na outbox</b> — última, e é o passo que o teste de rollback derruba de
   *       propósito para provar que nada acima sobrevive.
   * </ol>
   */
  private MissaoResponse concluirComCredito(
      UUID missaoId, EventoMissao evento, AtorMissao ator, Map<String, Object> payloadExtra) {

    Instant agora = Instant.now();

    Missao missao =
        missaoRepository
            .buscarParaAtualizar(missaoId)
            .orElseThrow(() -> new RecursoNaoEncontradoException(NAO_ENCONTRADA));

    MissaoStateMachine.validarAutorizacao(missao, evento, ator);

    UUID executorId = missao.getExecutorId();

    // Sondagem de replay ANTES de validar a transição: um retry de POST /confirmar numa missão já
    // CONCLUIDA é a mesma operação, não conflito, e checar a transição primeiro devolveria 409 a
    // quem apenas perdeu a resposta na rede.
    //
    // Só faz sentido sondar quando existe executor: a chave é derivada dele, e sem executor não há
    // como haver crédito anterior. Sem essa condição, uma missão ABERTA (executor nulo) explodiria
    // em NPE ao derivar a chave.
    if (executorId != null) {
      String chaveExistente = ChaveIdempotencia.conclusaoMissao(missaoId, executorId);
      if (creditoRecompensa.consultar(chaveExistente).isPresent()) {
        return MissaoResponse.de(missao);
      }
    }

    // 409 ANTES de qualquer 422. A ordem do contrato é 403 → 409 → regra de negócio, e é a mesma
    // desde quando este endpoint era um stub 501 — o app já integra nessa sequência.
    MissaoStateMachine.validar(missao, evento, ator);

    if (executorId == null) {
      // Inalcançável pelas transições declaradas: só se chega a AGUARDANDO_CONFIRMACAO ou
      // EM_DISPUTA via ACEITAR, que grava o executor. Guarda explícita para que um estado
      // impossível vire erro legível em vez de NPE, e depois do 409 para não roubar o código dele.
      throw new RegraNegocioVioladaException("Missão sem executor não pode ser concluída.");
    }

    String chave = ChaveIdempotencia.conclusaoMissao(missaoId, executorId);

    long tokens = missao.getTokensRecompensa();
    boolean pagaDoPote = pagaTokensDoPote(missao);
    if (pagaDoPote && tokens > 0) {
      // Missão TRIBO/COLETA paga do pote financiado, não de token cunhado — é o que conserva a
      // oferta da moeda. A guarda de publicação já exige pote >= recompensa, então isto só falha
      // se alguém contornar o publicar; ainda assim recusamos antes de escrever qualquer coisa.
      if (missao.getPoteTokens() < tokens) {
        throw new RegraNegocioVioladaException(
            "Pote da missão tem "
                + missao.getPoteTokens()
                + " tokens e a recompensa é de "
                + tokens
                + ".");
      }
      missao.debitarPote(tokens);
    }

    ResultadoCredito credito =
        creditoRecompensa.creditarConclusao(
            new ComandoCreditoConclusao(
                missaoId, executorId, missao.getValorBrl(), tokens, chave, agora));

    ResultadoProgressao progressao =
        progressaoUsuario.concederXp(executorId, missao.getXpRecompensa());

    Map<String, Object> payload = new LinkedHashMap<>();
    if (payloadExtra != null) {
      payload.putAll(payloadExtra);
    }
    payload.put("lancamentoId", credito.lancamentoId().toString());
    payload.put("valorBrl", missao.getValorBrl());
    payload.put("tokens", tokens);
    payload.put("xp", missao.getXpRecompensa());

    MissaoEvento trilha =
        MissaoStateMachine.transicionar(missao, evento, ator, serializar(payload), agora);

    missaoRepository.save(missao);
    missaoEventoRepository.save(trilha);

    // Mesma transação do crédito: se a conclusão der rollback, o anúncio não sobrevive; se ela
    // commitar, o anúncio está durável e o drenador o entrega com retry. Ver PublicadorEventos.
    publicadorEventos.publicar(
        "MissaoConcluida",
        missaoId,
        Map.of(
            "missaoId", missaoId.toString(),
            "executorId", executorId.toString(),
            "valorBrl", missao.getValorBrl(),
            "tokens", tokens,
            "xpConcedido", missao.getXpRecompensa(),
            "nivelAtual", progressao.nivelAtual(),
            "subiuDeNivel", progressao.subiuDeNivel()));

    return MissaoResponse.de(missao);
  }

  /**
   * TRIBO e COLETA pagam tokens do pote financiado; ENTREGA e AJUDA pagam BRL e não têm pote.
   *
   * <p>Mesma partição de categorias do ADR 0004 e do {@code ck_missao_economia} da V3, que já
   * proíbe {@code valor_brl > 0} nessas duas.
   */
  private static boolean pagaTokensDoPote(Missao missao) {
    return missao.getCategoria() == CategoriaMissao.TRIBO
        || missao.getCategoria() == CategoriaMissao.COLETA;
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

    // Guarda de publicação: precisa rodar sob o lock, com a missão já carregada, e ANTES da
    // transição — depois de transicionar, recusar exigiria desfazer.
    if (evento == EventoMissao.PUBLICAR) {
      MissaoStateMachine.validarAutorizacao(missao, evento, ator);
      validarPoteSuficienteParaPublicar(missao);
    }

    Instant agora = Instant.now();
    MissaoEvento trilha =
        MissaoStateMachine.transicionar(missao, evento, ator, serializar(payload), agora);

    // Estorno do pote em estado terminal sem pagamento. Sem isto, os tokens financiados ficariam
    // presos na missão para sempre e a conservação da moeda — que é a garantia central desta fase —
    // seria falsa: a soma (carteiras + potes) continuaria fechando, mas parte dela estaria em
    // custódia inalcançável, o que economicamente é o mesmo que queimar dinheiro dos outros.
    StatusMissao destino = missao.getStatus();
    if ((destino == StatusMissao.CANCELADA || destino == StatusMissao.EXPIRADA)
        && missao.getPoteTokens() > 0) {
      estornoFinanciamentoService.estornarPote(missao, agora);
    }

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
    return payloadTexto("motivo", motivo);
  }

  /**
   * Chave própria: a justificativa do admin numa disputa não é o mesmo dado que o motivo de quem
   * desistiu ou cancelou, e a trilha é lida por quem investiga incidente.
   */
  private static Map<String, Object> payloadJustificativa(String justificativa) {
    return payloadTexto("justificativa", justificativa);
  }

  private static Map<String, Object> payloadTexto(String chave, String valor) {
    if (valor == null || valor.isBlank()) {
      return null;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put(chave, valor);
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
