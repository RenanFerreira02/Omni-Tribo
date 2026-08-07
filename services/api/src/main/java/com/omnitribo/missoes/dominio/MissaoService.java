package com.omnitribo.missoes.dominio;

import com.omnitribo.carteira.api.ComandoCreditoConclusao;
import com.omnitribo.carteira.api.CreditoRecompensa;
import com.omnitribo.carteira.api.ResultadoCredito;
import com.omnitribo.compartilhado.api.PaginaResponse;
import com.omnitribo.compartilhado.api.PublicadorEventos;
import com.omnitribo.compartilhado.dominio.Auditavel;
import com.omnitribo.compartilhado.dominio.ChaveIdempotencia;
import com.omnitribo.compartilhado.dominio.Coordenadas;
import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import com.omnitribo.compartilhado.dominio.RegraNegocioVioladaException;
import com.omnitribo.identidade.api.ProgressaoUsuario;
import com.omnitribo.identidade.api.ResultadoProgressao;
import com.omnitribo.missoes.api.AtualizarMissaoRequest;
import com.omnitribo.missoes.api.CriarMissaoRequest;
import com.omnitribo.missoes.api.MissaoFiltroRequest;
import com.omnitribo.missoes.api.MissaoResponse;
import com.omnitribo.missoes.api.RegistrarCheckinRequest;
import com.omnitribo.missoes.api.ResolverDisputaRequest;
import com.omnitribo.missoes.infra.MissaoEventoRepository;
import com.omnitribo.missoes.infra.MissaoRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
  private final CreditoRecompensa creditoRecompensa;
  private final ProgressaoUsuario progressaoUsuario;
  private final PublicadorEventos publicadorEventos;
  private final EstornoFinanciamentoService estornoFinanciamentoService;

  public MissaoService(
      MissaoRepository missaoRepository,
      MissaoEventoRepository missaoEventoRepository,
      CreditoRecompensa creditoRecompensa,
      ProgressaoUsuario progressaoUsuario,
      PublicadorEventos publicadorEventos,
      EstornoFinanciamentoService estornoFinanciamentoService) {
    this.missaoRepository = missaoRepository;
    this.missaoEventoRepository = missaoEventoRepository;
    this.creditoRecompensa = creditoRecompensa;
    this.progressaoUsuario = progressaoUsuario;
    this.publicadorEventos = publicadorEventos;
    this.estornoFinanciamentoService = estornoFinanciamentoService;
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

    return MissaoResponse.de(missaoRepository.save(missao));
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

    return MissaoResponse.de(missaoRepository.save(missao));
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

  // ─── Stub de fase futura ───────────────────────────────────────────────────────────────────
  // Só o check-in continua pendente (F6). O contrato de erro já é o definitivo: 403 para ator
  // errado, 409 para estado errado e só então 501.
  //
  // Sem @Auditavel de propósito: sempre lança UnsupportedOperationException, e o aspecto é
  // @AfterReturning — anotá-lo criaria advice que nunca dispara. Entra junto com o corpo real.

  @Transactional(readOnly = true)
  public MissaoResponse registrarCheckin(
      UUID missaoId, AtorMissao ator, RegistrarCheckinRequest req) {
    Missao missao = carregarVisivel(missaoId, ator);
    MissaoStateMachine.validar(missao, EventoMissao.CHECKIN, ator);
    throw new UnsupportedOperationException(
        "TODO(F6): check-in exige cálculo de distância PostGIS contra raio_checkin_m no servidor.");
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
