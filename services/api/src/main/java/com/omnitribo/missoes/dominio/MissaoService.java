package com.omnitribo.missoes.dominio;

import com.omnitribo.carteira.api.ComandoCreditoConclusao;
import com.omnitribo.carteira.api.CreditoRecompensa;
import com.omnitribo.carteira.api.FinanciamentoMissao;
import com.omnitribo.carteira.api.ResultadoCredito;
import com.omnitribo.compartilhado.api.ConsultasGeoespaciais;
import com.omnitribo.compartilhado.api.ConsultasGeoespaciais.AlvoProximo;
import com.omnitribo.compartilhado.api.PaginaResponse;
import com.omnitribo.compartilhado.api.PublicadorEventos;
import com.omnitribo.compartilhado.dominio.Auditavel;
import com.omnitribo.compartilhado.dominio.ChaveIdempotencia;
import com.omnitribo.compartilhado.dominio.Coordenadas;
import com.omnitribo.compartilhado.dominio.Geohash;
import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import com.omnitribo.compartilhado.dominio.RegraNegocioVioladaException;
import com.omnitribo.compartilhado.dominio.TransicaoInvalidaException;
import com.omnitribo.geolocalizacao.api.ComandoCheckin;
import com.omnitribo.geolocalizacao.api.RegistroCheckin;
import com.omnitribo.geolocalizacao.api.ResultadoCheckin;
import com.omnitribo.identidade.api.ProgressaoUsuario;
import com.omnitribo.identidade.api.ResultadoProgressao;
import com.omnitribo.identidade.api.UsuarioSistema;
import com.omnitribo.logistica.api.BaixaCustodia;
import com.omnitribo.missoes.api.AtualizarMissaoRequest;
import com.omnitribo.missoes.api.ConfirmacaoRetirada;
import com.omnitribo.missoes.api.ConversaoEntregaFalida;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Orquestra o ciclo de vida de missões. Toda mudança de status passa pela máquina de estados. */
@Service
public class MissaoService implements ConversaoEntregaFalida, ConfirmacaoRetirada {

  private static final Logger log = LoggerFactory.getLogger(MissaoService.class);

  private static final String NAO_ENCONTRADA = "Missão não encontrada.";

  /**
   * Mapper próprio, não injetado: o Boot 4.1 só autoconfigura o Jackson 3, e o payload da trilha
   * append-only é registro histórico — não pode mudar de formato quando a serialização da API
   * mudar.
   */
  private static final JsonMapper MAPPER_TRILHA = JsonMapper.builder().build();

  private final MissaoRepository missaoRepository;
  private final MissaoEventoRepository missaoEventoRepository;
  private final ConsultasGeoespaciais consultasGeoespaciais;
  private final CacheMissoesProximas cacheMissoesProximas;

  // Portas de outros módulos, injetadas pela INTERFACE: é o tipo declarado que o ArchUnit
  // inspeciona.
  private final RegistroCheckin registroCheckin;
  private final CreditoRecompensa creditoRecompensa;
  private final ProgressaoUsuario progressaoUsuario;
  private final PublicadorEventos publicadorEventos;
  private final BaixaCustodia baixaCustodia;

  // Só o financiamento do PATROCINADOR passa por aqui; o comunitário vive em FinanciamentoService.
  private final FinanciamentoMissao financiamentoMissao;

  // Do próprio módulo, por isso pode ser classe concreta.
  private final EstornoFinanciamentoService estornoFinanciamentoService;

  // A FÓRMULA é código; os NÚMEROS são configuração.
  private final ParametrosRecompensa parametrosRecompensa;

  // Separada da recompensa: mudar um prazo daqui não exige subir a versão da fórmula.
  private final ParametrosEntregaFalida parametrosEntregaFalida;

  public MissaoService(
      MissaoRepository missaoRepository,
      MissaoEventoRepository missaoEventoRepository,
      ConsultasGeoespaciais consultasGeoespaciais,
      CacheMissoesProximas cacheMissoesProximas,
      RegistroCheckin registroCheckin,
      CreditoRecompensa creditoRecompensa,
      ProgressaoUsuario progressaoUsuario,
      PublicadorEventos publicadorEventos,
      BaixaCustodia baixaCustodia,
      FinanciamentoMissao financiamentoMissao,
      EstornoFinanciamentoService estornoFinanciamentoService,
      ParametrosRecompensa parametrosRecompensa,
      ParametrosEntregaFalida parametrosEntregaFalida) {
    this.missaoRepository = missaoRepository;
    this.missaoEventoRepository = missaoEventoRepository;
    this.consultasGeoespaciais = consultasGeoespaciais;
    this.cacheMissoesProximas = cacheMissoesProximas;
    this.registroCheckin = registroCheckin;
    this.creditoRecompensa = creditoRecompensa;
    this.progressaoUsuario = progressaoUsuario;
    this.publicadorEventos = publicadorEventos;
    this.baixaCustodia = baixaCustodia;
    this.financiamentoMissao = financiamentoMissao;
    this.estornoFinanciamentoService = estornoFinanciamentoService;
    this.parametrosRecompensa = parametrosRecompensa;
    this.parametrosEntregaFalida = parametrosEntregaFalida;
  }

  // Auditoria no serviço, onde a escrita e o proxy transacional já existem. Leitura não é auditada.
  @Auditavel(acao = "MISSAO_CRIADA", entidade = "missao")
  @Transactional
  public MissaoResponse criar(CriarMissaoRequest req, AtorMissao ator) {
    Instant agora = Instant.now();

    // A recompensa é DERIVADA no servidor e congelada com a versão da fórmula (ADR 0009).
    CalculadoraDeRecompensa.Recompensa recompensa = calcularRecompensa(req);

    // Toda missão nasce RASCUNHO, e o criador é sempre o dono do JWT.
    Missao missao =
        new Missao(
            UUID.randomUUID(),
            ator.usuarioId(),
            req.categoria(),
            req.titulo(),
            req.descricao(),
            StatusMissao.RASCUNHO,
            recompensa,
            // BRL é sempre zero (ADR 0009); ck_missao_economia recusaria outro valor.
            BigDecimal.ZERO,
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

    // Hoje é no-op (RASCUNHO não aparece no radar); fica como gancho se o status inicial mudar.
    cacheMissoesProximas.invalidarAposCommit();

    return MissaoResponse.de(salva);
  }

  /**
   * Recusa trocar entre "só XP" e "com token" fora de RASCUNHO. É 409, não 422: a mesma edição
   * caberia em outro estado. Publicar congela a fonte, porque dali em diante é promessa (ADR 0036).
   */
  private static void validarTrocaDeRecompensa(Missao missao, Boolean recompensaEmToken) {
    if (recompensaEmToken == null) {
      return;
    }
    if (missao.getStatus() != StatusMissao.RASCUNHO) {
      throw new TransicaoInvalidaException(
          "A forma de recompensa só pode mudar enquanto a missão está em rascunho.");
    }
    // Mesma regra (4) de CriacaoMissaoVerificador (ADR 0038): só COLETA exige token.
    if (!recompensaEmToken && missao.getCategoria() == CategoriaMissao.COLETA) {
      throw new RegraNegocioVioladaException("Missões COLETA não podem recompensar apenas em XP.");
    }
  }

  /**
   * Se este PATCH toca algo de que a recompensa depende: os insumos da calculadora que o PATCH
   * alcança, mais {@code recompensaEmToken}, que decide a fonte. <b>Campo novo no DTO de edição que
   * entre na fórmula precisa entrar aqui</b>, senão a missão volta a ter um valor que os dados dela
   * não explicam.
   */
  private static boolean mexeuEmInsumoDaRecompensa(AtualizarMissaoRequest req) {
    return req.pesoKg() != null
        || req.volumeL() != null
        || req.complexidade() != null
        || req.recompensaEmToken() != null
        // Origem e destino entram pela distância; os dois vêm aos pares (EdicaoMissaoVerificador).
        || req.origemLat() != null
        || req.destinoLat() != null;
  }

  private void recongelarRecompensaEditada(
      Missao missao, Boolean recompensaEmToken, ComplexidadeMissao complexidade) {
    boolean comToken =
        recompensaEmToken != null
            ? recompensaEmToken
            : missao.getFontePote() != FontePote.SEM_TOKEN;

    // Regra (3) da criação, como 422: depende de peso e volume DA MISSÃO, que o verificador não vê.
    boolean derivaDoObjeto = missao.getPesoKg() != null && missao.getVolumeL() != null;
    if (complexidade != null && derivaDoObjeto) {
      throw new RegraNegocioVioladaException(
          "Complexidade é derivada de peso e volume nesta missão — não a informe.");
    }

    CalculadoraDeRecompensa.Recompensa nova = calcularRecompensaDe(missao, comToken, complexidade);

    long pote = missao.getPoteTokens();
    boolean publicada = missao.getStatus() != StatusMissao.RASCUNHO;

    if (nova.tokens() < pote) {
      throw new PoteInsuficienteException(
          "Esta edição baixaria a recompensa para "
              + nova.tokens()
              + " tokens, abaixo dos "
              + pote
              + " já financiados — a diferença ficaria presa nesta missão. Cancele-a para estornar"
              + " quem financiou.",
          nova.tokens(),
          pote);
    }

    if (publicada && pote > 0 && nova.tokens() > pote) {
      throw new PoteInsuficienteException(
          "Esta edição subiria a recompensa para "
              + nova.tokens()
              + " tokens, acima dos "
              + pote
              + " já financiados, e a missão não poderia mais ser concluída. O pote de uma missão"
              + " publicada está fechado: cancele-a para estornar quem financiou.",
          nova.tokens(),
          pote);
    }

    missao.recongelarRecompensa(nova);
  }

  /**
   * Recompensa que a missão teria com os dados de AGORA. Valor ofertado nulo (só missão de retirada
   * o tem, e o PATCH não a alcança); multiplicador de risco congelado preservado.
   */
  private CalculadoraDeRecompensa.Recompensa calcularRecompensaDe(
      Missao missao, boolean comToken, ComplexidadeMissao complexidadeNova) {
    Double distanciaM = null;
    if (missao.getDestino() != null) {
      distanciaM =
          consultasGeoespaciais.distanciaMetros(
              Coordenadas.latitude(missao.getOrigem()),
              Coordenadas.longitude(missao.getOrigem()),
              Coordenadas.latitude(missao.getDestino()),
              Coordenadas.longitude(missao.getDestino()));
    }

    CalculadoraDeRecompensa.Recompensa recompensa =
        CalculadoraDeRecompensa.calcular(
            new CalculadoraDeRecompensa.Insumos(
                missao.getCategoria(),
                complexidadeNova != null ? complexidadeNova : missao.getComplexidade(),
                missao.getPesoKg(),
                missao.getVolumeL(),
                distanciaM,
                null,
                missao.getMultiplicadorRisco()),
            parametrosRecompensa);

    return comToken ? recompensa : recompensa.semToken();
  }

  /** Monta os insumos e chama a calculadora. Distância só é medida quando há destino. */
  public CalculadoraDeRecompensa.Recompensa calcularRecompensa(CriarMissaoRequest req) {
    Double distanciaM = null;
    if (req.destinoLat() != null && req.destinoLon() != null) {
      distanciaM =
          consultasGeoespaciais.distanciaMetros(
              req.origemLat(), req.origemLon(), req.destinoLat(), req.destinoLon());
    }

    CalculadoraDeRecompensa.Recompensa recompensa =
        CalculadoraDeRecompensa.calcular(
            new CalculadoraDeRecompensa.Insumos(
                req.categoria(),
                req.complexidade(),
                req.pesoKg(),
                req.volumeL(),
                distanciaM,
                // Só a conversão de entrega falida tem valor ofertado — quem cria não paga.
                null),
            parametrosRecompensa);

    // Zerar o token é decisão de produto, não de fórmula, e acontece no ponto que a prévia e a
    // criação compartilham: a prévia nunca promete token que a criação não paga (ADR 0035).
    return req.recompensaEmTokenEfetiva() ? recompensa : recompensa.semToken();
  }

  /**
   * Converte uma entrega falida em missão de retirada já ABERTA ({@link ConversaoEntregaFalida}).
   *
   * <p>Roda na transação do webhook, que segura o {@code FOR UPDATE} do ponto de custódia: missão e
   * ocupação commitam juntas. A publicação passa pela máquina de estados mesmo sem criador humano,
   * para gravar a trilha.
   *
   * <p>O pote é pago pela TRANSPORTADORA antes de a missão existir (ADR 0024). Sem saldo, devolve
   * {@link Optional#empty()}: nada é escrito e o chamador grava SEM_PATROCINIO. {@code
   * validarPoteSuficienteParaPublicar} não roda neste caminho — quem garante o pote é a ORDEM do
   * corpo.
   */
  @Override
  @Transactional
  public Optional<MissaoDeRetirada> abrirMissaoDeRetirada(Encomenda encomenda) {
    Double distanciaM = null;
    if (encomenda.destinoLat() != null && encomenda.destinoLon() != null) {
      distanciaM =
          consultasGeoespaciais.distanciaMetros(
              encomenda.origemLat(),
              encomenda.origemLon(),
              encomenda.destinoLat(),
              encomenda.destinoLon());
    }

    // Complexidade derivada de peso e volume, como em toda ENTREGA.
    CalculadoraDeRecompensa.Recompensa recompensa =
        CalculadoraDeRecompensa.calcular(
            new CalculadoraDeRecompensa.Insumos(
                CategoriaMissao.ENTREGA,
                null,
                encomenda.pesoKg(),
                encomenda.volumeL(),
                distanciaM,
                encomenda.valorOfertadoBrl(),
                // Único caminho com multiplicador de risco diferente de 1 (ADR 0022).
                encomenda.multiplicadorRisco()),
            parametrosRecompensa);

    Instant fimDaJanela = encomenda.agora().plus(parametrosEntregaFalida.prazoRetirada());

    // O id nasce antes da entidade porque o financiamento grava `lancamento.missao_id` e precisa
    // acontecer ANTES: se o patrocinador não puder pagar, nada pode ter sido escrito.
    UUID missaoId = UUID.randomUUID();

    // Financiamento primeiro. Vazio = sem patrocínio: nenhuma missão, nenhum lançamento, nenhuma
    // vaga ocupada. Recompensa zero não financia (lançamento zerado é recusado pelo ledger).
    if (recompensa.tokens() > 0
        && financiamentoMissao
            .debitarPatrocinador(
                encomenda.patrocinadorUsuarioId(),
                missaoId,
                recompensa.tokens(),
                ChaveIdempotencia.financiamentoPatrocinador(
                    encomenda.patrocinadorUsuarioId(), missaoId),
                encomenda.agora())
            .isEmpty()) {
      log.warn(
          "Entrega falida {} sem conversão: patrocinador {} não tem saldo para {} tokens.",
          encomenda.entregaFalidaId(),
          encomenda.patrocinadorUsuarioId(),
          recompensa.tokens());
      return Optional.empty();
    }

    Missao missao =
        new Missao(
            missaoId,
            // Criador = usuário-sistema: satisfaz AtorEsperado.CRIADOR sem evento novo.
            UsuarioSistema.ID,
            CategoriaMissao.ENTREGA,
            tituloDaRetirada(encomenda.descricaoDoItem()),
            descricaoDaRetirada(encomenda),
            StatusMissao.RASCUNHO,
            recompensa,
            // Zero: o valor ofertado já entrou na recompensa em TOKEN (ADR 0009).
            BigDecimal.ZERO,
            Coordenadas.ponto(encomenda.origemLat(), encomenda.origemLon()),
            Coordenadas.ponto(encomenda.destinoLat(), encomenda.destinoLon()),
            encomenda.pontoCustodiaId(),
            encomenda.cep(),
            encomenda.logradouro(),
            encomenda.bairro(),
            encomenda.cidade(),
            encomenda.uf(),
            parametrosEntregaFalida.raioCheckinM(),
            encomenda.pesoKg(),
            encomenda.volumeL(),
            encomenda.agora(),
            fimDaJanela,
            encomenda.agora());

    missao.exigirNivelMinimo(parametrosEntregaFalida.nivelMinimo());
    missao.registrarFaixaRisco(encomenda.faixaRisco());

    // PATROCINADOR e pote creditado ANTES do PUBLICAR: é a única coisa que impede a missão
    // patrocinada de nascer com o pote vazio.
    missao.financiadaPeloPatrocinador();
    if (recompensa.tokens() > 0) {
      missao.creditarPote(recompensa.tokens());
    }
    missaoRepository.save(missao);

    AtorMissao sistema = new AtorMissao(UsuarioSistema.ID, AtorMissao.PapelAtor.SISTEMA);
    MissaoEvento trilha =
        MissaoStateMachine.transicionar(
            missao,
            EventoMissao.PUBLICAR,
            sistema,
            serializar(
                Map.of("origem", "ENTREGA_FALIDA", "entregaFalidaId", encomenda.entregaFalidaId())),
            encomenda.agora());
    missaoRepository.save(missao);
    missaoEventoRepository.save(trilha);

    // Aqui a invalidação vale: a missão nasce ABERTA e entra no radar na hora.
    cacheMissoesProximas.invalidarAposCommit();

    return Optional.of(
        new MissaoDeRetirada(
            missao.getId(), recompensa.xp(), recompensa.tokens(), missao.getNivelMinimo()));
  }

  /**
   * Elegibilidade por reputação: encomenda de TERCEIRO não é aceitável por conta recém-criada. Sai
   * cedo quando {@code nivelMinimo == 1}. Ninguém é isento — nem SISTEMA nem ADMIN aceitam por
   * outro.
   */
  private void validarNivelParaAceitar(Missao missao, AtorMissao ator) {
    if (missao.getNivelMinimo() <= 1) {
      return;
    }
    int nivelAtual = progressaoUsuario.nivelDe(ator.usuarioId());
    if (nivelAtual < missao.getNivelMinimo()) {
      throw new NivelInsuficienteException(missao.getNivelMinimo(), nivelAtual);
    }
  }

  private static String tituloDaRetirada(String descricaoDoItem) {
    String titulo = "Retirar e entregar: " + descricaoDoItem;
    // Título tem 120 no schema e a descrição vem de terceiro: truncar é melhor que 500 no webhook.
    return titulo.length() <= 120 ? titulo : titulo.substring(0, 117) + "...";
  }

  /**
   * O check-in é na RETIRADA: o geofence usa a origem, que é a LOJA (dado nosso e conferido). Usar
   * o destino deixaria a transportadora escolher onde o antifraude acredita que alguém esteve.
   */
  private static String descricaoDaRetirada(Encomenda encomenda) {
    // Concatenação e não formatted(): %n viraria CRLF ou LF conforme o servidor, num texto que é
    // dado.
    return """
           Uma entrega falhou e a encomenda está guardada num ponto de custódia do bairro.

           Item: """
        + encomenda.descricaoDoItem()
        + """

           Faça o check-in geolocalizado AO RETIRAR, no ponto de custódia, e leve a encomenda ao \
           endereço de destino informado.

           Você recebe XP e tokens — esta missão não paga em reais.""";
  }

  @Transactional(readOnly = true)
  public PaginaResponse<MissaoResponse> listar(MissaoFiltroRequest filtro, AtorMissao ator) {
    // O escopo vem do JWT; o cliente nunca informa o id de quem filtrar.
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
            // Recorte por participação: sem ele, a listagem viraria um mapa de endereços do bairro.
            .map(m -> MissaoResponse.de(m, ator.usuarioId()));

    return PaginaResponse.de(page);
  }

  @Transactional(readOnly = true)
  public MissaoResponse buscarPorId(UUID missaoId, AtorMissao ator) {
    // Quem só olha vê bairro e coordenada aproximada; quem ACEITA passa a ver o endereço completo.
    return MissaoResponse.de(carregarVisivel(missaoId, ator), ator.usuarioId());
  }

  /**
   * Missões ABERTA no raio, da mais próxima para a mais distante. Duas consultas: o PostGIS devolve
   * (id, distância) — {@code ConsultasGeoespaciais} não pode conhecer Missao — e a segunda
   * reidrata. Só ABERTA sai daqui, e ABERTA é pública: o resultado independe de quem pergunta.
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

    // Itera em `alvos` para preservar a ordem por distância; findAllById não promete ordem.
    return alvos.stream()
        .map(
            alvo ->
                new MissaoProximaResponse(
                    // SEMPRE recortada: o cache é por região, sem o usuário, então recortar por
                    // solicitante serviria a resposta de um participante a um estranho.
                    MissaoResponse.deAproximada(porId.get(alvo.id())),
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
    validarTrocaDeRecompensa(missao, req.recompensaEmToken());

    // Nulo preserva o atual; categoria, status e executor não estão no DTO. A recompensa é
    // recalculada
    // abaixo (ADR 0036).
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

    // Recalcula só quando um INSUMO veio no corpo: corrigir o título de uma missão financiada não
    // pode
    // falhar com 422 porque a calibração do YAML mudou desde a criação.
    if (mexeuEmInsumoDaRecompensa(req)) {
      recongelarRecompensaEditada(missao, req.recompensaEmToken(), req.complexidade());
    }

    Missao salva = missaoRepository.save(missao);

    // Mover origem ou raio muda o radar sem transição de estado: invalida o cache.
    cacheMissoesProximas.invalidarAposCommit();

    return MissaoResponse.de(salva);
  }

  @Auditavel(acao = "MISSAO_PUBLICADA", entidade = "missao")
  @Transactional
  public MissaoResponse publicar(UUID missaoId, AtorMissao ator) {
    return aplicar(missaoId, EventoMissao.PUBLICAR, ator, null);
  }

  /**
   * Missão que paga DO POTE só publica com o pote cobrindo a recompensa: senão chegaria a
   * AGUARDANDO_CONFIRMACAO e a conclusão falharia com 422 para sempre. Falhar cedo, enquanto o
   * criador ainda pode financiar ou baixar o valor.
   */
  private static void validarPoteSuficienteParaPublicar(Missao missao) {
    if (!pagaTokensDoPote(missao)) {
      return;
    }
    long recompensa = missao.getTokensRecompensa();
    // Recompensa 0 é a missão só-XP (ADR 0035): não há pote a exigir.
    if (recompensa > 0 && missao.getPoteTokens() < recompensa) {
      throw new PoteInsuficienteException(
          "Missão "
              + missao.getCategoria()
              + " precisa do pote financiado antes de publicar: recompensa de "
              + recompensa
              + " tokens contra pote de "
              + missao.getPoteTokens()
              + ".",
          recompensa,
          missao.getPoteTokens());
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

  // ─── Check-in ──────────────────────────────────────────────────────────────────────────────

  /**
   * Check-in geolocalizado: EM_ANDAMENTO → AGUARDANDO_CONFIRMACAO.
   *
   * <p>Uma transação e uma conexão. A recusa volta como VALOR, não exceção, para a linha antifraude
   * commitar mesmo quando o check-in é recusado; quem a transforma em 422 é o controller.
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

    // Rascunho alheio é 404, não 403 (403 confirmaria que ele existe). Depois do lock, que precisa
    // ser
    // a primeira leitura da transação.
    if (missao.getStatus() == StatusMissao.RASCUNHO && !ator.ehMesmo(missao.getCriadorId())) {
      throw new RecursoNaoEncontradoException(NAO_ENCONTRADA);
    }

    // 403 antes da sondagem: senão o estado da missão vazaria para quem não é o executor.
    MissaoStateMachine.validarAutorizacao(missao, EventoMissao.CHECKIN, ator);

    String chave = chaveIdempotencia(ator.usuarioId(), missaoId, chaveCliente);

    // Sondagem entre o 403 e o 409: um retry legítimo chega com a missão já transicionada e precisa
    // do mesmo 200, não de 409.
    Optional<ResultadoCheckin> jaRegistrado = registroCheckin.consultar(chave);
    if (jaRegistrado.isPresent()) {
      ResultadoCheckin anterior = jaRegistrado.get();

      if (!anterior.aceito()) {
        // Replay de rejeição: mesmo 422, nenhuma linha nova.
        return ResultadoRegistroCheckin.rejeitado(
            MissaoResponse.de(missao),
            anterior.codigoRejeicao(),
            anterior.motivoRejeicao(),
            anterior.distanciaM(),
            anterior.acuraciaM());
      }
      // Replay de aceite: devolve o estado atual, sem linha nem evento novo.
      return ResultadoRegistroCheckin.aceito(MissaoResponse.de(missao));
    }

    // 409 antes de qualquer escrita: ator ou estado errado não são tentativas de check-in.
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
      // NÃO lança: a linha antifraude acabou de ser gravada nesta transação e o rollback a
      // apagaria.
      return ResultadoRegistroCheckin.rejeitado(
          MissaoResponse.de(missao),
          resultado.codigoRejeicao(),
          resultado.motivoRejeicao(),
          resultado.distanciaM(),
          resultado.acuraciaM());
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
   * Chave de idempotência derivada (usuário + missão + chave do cliente): a UNIQUE é global, e com
   * a chave crua o cliente que manda "1" receberia o replay do check-in de outra pessoa.
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
   * Payload da trilha — é aqui que fica o motivo de uma SUSPEITA: motivo_rejeicao precisa continuar
   * nulo quando valido=true, ou deixa de significar "rejeitado".
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

  /**
   * A transportadora confirma que a encomenda chegou ({@link ConfirmacaoRetirada}, ADR 0026).
   *
   * <p>Reusa {@link #confirmar} inteiro: o criador da missão de retirada é o usuário-sistema, então
   * o ator SISTEMA satisfaz {@code AtorEsperado.CRIADOR} por construção. Só alcança missão de
   * retirada, porque {@code logistica} resolve o id a partir de {@code entrega_falida}. A
   * justificativa vai para a trilha.
   */
  @Override
  @Transactional
  public long confirmarRetirada(UUID missaoId) {
    MissaoResponse confirmada =
        concluirComCredito(
            missaoId,
            EventoMissao.CONFIRMAR,
            new AtorMissao(UsuarioSistema.ID, AtorMissao.PapelAtor.SISTEMA),
            payloadJustificativa(
                "Transportadora confirmou o recebimento pelo destinatário, por webhook."));
    return confirmada.tokensRecompensa();
  }

  /**
   * ADMIN destrava uma missão parada: CANCELADA, com estorno do pote. Nenhum caminho de valor novo
   * ({@code aplicar} já estorna); a justificativa é obrigatória na trilha.
   */
  @Auditavel(acao = "MISSAO_DESTRAVADA", entidade = "missao")
  @Transactional
  public MissaoResponse destravar(UUID missaoId, AtorMissao ator, String justificativa) {
    return aplicar(missaoId, EventoMissao.DESTRAVAR, ator, payloadJustificativa(justificativa));
  }

  /**
   * Conclui pagando o executor quando o CRIADOR não confirmou no prazo — só a varredura chama.
   * Reusa {@code concluirComCredito}: dois caminhos de crédito é como se perde a conservação sem
   * ver.
   */
  @Transactional
  public MissaoResponse concluirPorOmissaoDoCriador(UUID missaoId, Instant agora) {
    return concluirComCredito(
        missaoId,
        EventoMissao.EXPIRAR_CONFIRMACAO,
        AtorMissao.sistema(),
        payloadJustificativa(
            "Criador não confirmou dentro do prazo; check-in do executor é a evidência."));
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
   * Conclusão com crédito, em UMA transação, nesta ordem:
   *
   * <ol>
   *   <li>travar a missão ({@code FOR UPDATE}, primeira leitura);
   *   <li>autorizar (403);
   *   <li>sondar o replay, sob o lock — retry é a mesma operação, não conflito;
   *   <li>validar a transição (409);
   *   <li>debitar o pote, se a missão paga do pote;
   *   <li>creditar a carteira e conceder XP (locks: missao → carteira → usuario);
   *   <li>transicionar e gravar a trilha;
   *   <li>publicar na outbox — por último, e é o passo que o teste de rollback derruba.
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

    // Replay antes da transição: o retry de /confirmar numa missão já CONCLUIDA precisa do mesmo
    // 200.
    // Só com executor: a chave é derivada dele.
    if (executorId != null) {
      String chaveExistente = ChaveIdempotencia.conclusaoMissao(missaoId, executorId);
      if (creditoRecompensa.consultar(chaveExistente).isPresent()) {
        return MissaoResponse.de(missao);
      }
      // SEM_TOKEN não grava lançamento, então a sondagem acima é cega para ela: a idempotência é
      // por
      // ESTADO, sob o mesmo FOR UPDATE (precedente do ADR 0031).
      if (nadaACreditar(missao) && missao.getStatus() == StatusMissao.CONCLUIDA) {
        return MissaoResponse.de(missao);
      }
    }

    // 409 antes de 422: a ordem do contrato é 403 → 409 → regra de negócio.
    MissaoStateMachine.validar(missao, evento, ator);

    if (executorId == null) {
      // Inalcançável pelas transições declaradas; vira erro legível em vez de NPE.
      throw new RegraNegocioVioladaException("Missão sem executor não pode ser concluída.");
    }

    String chave = ChaveIdempotencia.conclusaoMissao(missaoId, executorId);

    long tokens = missao.getTokensRecompensa();
    boolean pagaDoPote = pagaTokensDoPote(missao);
    if (pagaDoPote && tokens > 0) {
      // Paga do pote financiado, não de token emitido. A publicação já exige pote ≥ recompensa.
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

    // SEM_TOKEN não passa pelo ledger: Movimento (e ck_lancamento_valor_nao_nulo) recusa lançamento
    // que não move nada. A conclusão dela concede XP e só (ADR 0035).
    ResultadoCredito credito = null;
    if (!nadaACreditar(missao)) {
      credito =
          creditoRecompensa.creditarConclusao(
              new ComandoCreditoConclusao(
                  missaoId, executorId, missao.getValorBrl(), tokens, chave, agora));
    }

    ResultadoProgressao progressao =
        progressaoUsuario.concederXp(executorId, missao.getXpRecompensa());

    Map<String, Object> payload = new LinkedHashMap<>();
    if (payloadExtra != null) {
      payload.putAll(payloadExtra);
    }
    if (credito != null) {
      payload.put("lancamentoId", credito.lancamentoId().toString());
    }
    payload.put("valorBrl", missao.getValorBrl());
    payload.put("tokens", tokens);
    payload.put("xp", missao.getXpRecompensa());

    MissaoEvento trilha =
        MissaoStateMachine.transicionar(missao, evento, ator, serializar(payload), agora);

    missaoRepository.save(missao);
    missaoEventoRepository.save(trilha);

    // Baixa da custódia SÍNCRONA, nesta transação: pela outbox ela poderia repetir, e um decremento
    // redespachado liberaria uma vaga que nunca existiu.
    baixaCustodia.darBaixa(missaoId, agora);

    // Mesma transação do crédito: rollback leva o anúncio junto; commit o deixa durável.
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
   * Se a conclusão paga DO POTE ou CUNHA. Lê {@code missao.fonte_pote}, congelado na criação.
   *
   * <p>Só a {@link FontePote#CUNHAGEM} LEGADA cunha — missão publicada antes da V31, mantendo a
   * recompensa prometida. Nenhuma missão nova nasce nela (ADR 0038); a emissão que sustenta o resto
   * tem um ponto único e auditado, {@code AporteToken}. Nenhuma categoria paga em BRL ({@code
   * ck_missao_economia}, V15).
   */
  private static boolean pagaTokensDoPote(Missao missao) {
    return missao.getFontePote() != FontePote.CUNHAGEM;
  }

  /**
   * Se a conclusão não tem o que escrever no ledger. Testa os VALORES, e não a fonte, porque a
   * barreira respeitada é a do {@code Movimento}, que olha BRL e token.
   */
  private static boolean nadaACreditar(Missao missao) {
    return missao.getTokensRecompensa() == 0 && missao.getValorBrl().signum() == 0;
  }

  // ─── Núcleo ────────────────────────────────────────────────────────────────────────────────

  private MissaoResponse aplicar(
      UUID missaoId, EventoMissao evento, AtorMissao ator, Map<String, Object> payload) {

    // FOR UPDATE como PRIMEIRA leitura: com a entidade já no persistence context, o Hibernate não
    // reemitiria o SELECT ... FOR UPDATE e o lock não existiria.
    Missao missao =
        missaoRepository
            .buscarParaAtualizar(missaoId)
            .orElseThrow(() -> new RecursoNaoEncontradoException(NAO_ENCONTRADA));

    // Guarda de publicação sob o lock e antes da transição.
    if (evento == EventoMissao.PUBLICAR) {
      MissaoStateMachine.validarAutorizacao(missao, evento, ator);
      validarPoteSuficienteParaPublicar(missao);
    }

    // Trava de reputação sob o lock e antes da transição; depois da autorização (403 → 422), para
    // quem não pode agir não descobrir o nível exigido.
    if (evento == EventoMissao.ACEITAR) {
      MissaoStateMachine.validarAutorizacao(missao, evento, ator);
      validarNivelParaAceitar(missao, ator);
    }

    Instant agora = Instant.now();
    MissaoEvento trilha =
        MissaoStateMachine.transicionar(missao, evento, ator, serializar(payload), agora);

    // Estado terminal sem pagamento estorna o pote: sem isto o token ficaria preso numa missão
    // morta e
    // a reconciliação continuaria dizendo integro=true.
    StatusMissao destino = missao.getStatus();
    if ((destino == StatusMissao.CANCELADA || destino == StatusMissao.EXPIRADA)
        && missao.getPoteTokens() > 0) {
      estornoFinanciamentoService.estornarPote(missao, agora);
    }

    missaoRepository.save(missao);
    // Mesma transação da missão: status e trilha não têm como divergir.
    missaoEventoRepository.save(trilha);

    // Ponto único de invalidação: estas transições entram ou saem de ABERTA, que é o que o radar
    // vê.
    cacheMissoesProximas.invalidarAposCommit();

    return MissaoResponse.de(missao);
  }

  /** Visibilidade: rascunho alheio é 404, não 403 — um 403 confirmaria que ele existe. */
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
   * Chave própria: a justificativa do admin numa disputa não é o mesmo dado que um motivo comum.
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
