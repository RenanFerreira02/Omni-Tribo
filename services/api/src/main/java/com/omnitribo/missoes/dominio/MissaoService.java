package com.omnitribo.missoes.dominio;

import com.omnitribo.compartilhado.api.PaginaResponse;
import com.omnitribo.compartilhado.dominio.Auditavel;
import com.omnitribo.compartilhado.dominio.Coordenadas;
import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
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

  public MissaoService(
      MissaoRepository missaoRepository, MissaoEventoRepository missaoEventoRepository) {
    this.missaoRepository = missaoRepository;
    this.missaoEventoRepository = missaoEventoRepository;
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

  // ─── Stubs de fases futuras ────────────────────────────────────────────────────────────────
  // O contrato de erro já é o definitivo: 403 para ator errado, 409 para estado errado e só
  // então 501. Assim o app mobile pode integrar a ordem de checagens agora, e F6/F7 só trocam
  // o corpo do método.
  //
  // Sem @Auditavel de propósito: os três sempre lançam UnsupportedOperationException, e o aspecto é
  // @AfterReturning — anotá-los agora criaria advice que nunca dispara. Entra junto com o corpo
  // real
  // em F6 (checkin) e F7 (confirmar, resolver).

  @Transactional(readOnly = true)
  public MissaoResponse registrarCheckin(
      UUID missaoId, AtorMissao ator, RegistrarCheckinRequest req) {
    Missao missao = carregarVisivel(missaoId, ator);
    MissaoStateMachine.validar(missao, EventoMissao.CHECKIN, ator);
    throw new UnsupportedOperationException(
        "TODO(F6): check-in exige cálculo de distância PostGIS contra raio_checkin_m no servidor.");
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
