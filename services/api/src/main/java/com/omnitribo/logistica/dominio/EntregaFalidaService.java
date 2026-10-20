package com.omnitribo.logistica.dominio;

import com.omnitribo.compartilhado.api.PublicadorEventos;
import com.omnitribo.compartilhado.api.RecursoAuditavel;
import com.omnitribo.compartilhado.dominio.Auditavel;
import com.omnitribo.compartilhado.dominio.Coordenadas;
import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import com.omnitribo.identidade.api.ConsultaPatrocinador;
import com.omnitribo.logistica.infra.EntregaFalidaRepository;
import com.omnitribo.logistica.infra.PontoCustodiaRepository;
import com.omnitribo.missoes.api.ConversaoEntregaFalida;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Converte uma entrega que falhou em missão comunitária de retirada — a tese do produto em código.
 *
 * <p>Ordem canônica: <b>locks → sondar idempotência → validar → escrever</b>. O lock é o do PONTO
 * DE CUSTÓDIA: serializa a disputa por vaga e o replay do mesmo webhook.
 *
 * <p>Recusa volta como VALOR, não exceção: precisa ser GRAVADA, e lançar a apagaria no rollback.
 * {@code REQUIRES_NEW} travaria o pool (a transação externa já segura um FOR UPDATE).
 */
@Service
public class EntregaFalidaService {

  private static final Logger log = LoggerFactory.getLogger(EntregaFalidaService.class);

  /** Evento de notificação: há missão nova de retirada perto de você. */
  public static final String EVENTO_CONVERTIDA = "EntregaFalidaConvertida";

  /** Evento operacional: um ponto está lotado e recusou encomenda. */
  public static final String EVENTO_RECUSADA = "EntregaFalidaRecusada";

  /**
   * Evento operacional: não havia patrocínio para o pote. Tipo próprio, com case no despachante —
   * tipo sem case lançaria e iria para a carta-morta da outbox (ADR 0031).
   */
  public static final String EVENTO_SEM_PATROCINIO = "EntregaFalidaSemPatrocinio";

  private final EntregaFalidaRepository entregaFalidaRepository;
  private final PontoCustodiaRepository pontoCustodiaRepository;
  private final ConversaoEntregaFalida conversaoEntregaFalida;
  private final PublicadorEventos publicadorEventos;

  // Pela INTERFACE: logistica não alcança identidade.dominio (ArchUnit).
  private final ConsultaPatrocinador consultaPatrocinador;

  public EntregaFalidaService(
      EntregaFalidaRepository entregaFalidaRepository,
      PontoCustodiaRepository pontoCustodiaRepository,
      // Pela INTERFACE: nomear MissaoService faria logistica alcançar missoes.dominio.
      ConversaoEntregaFalida conversaoEntregaFalida,
      PublicadorEventos publicadorEventos,
      ConsultaPatrocinador consultaPatrocinador) {
    this.entregaFalidaRepository = entregaFalidaRepository;
    this.pontoCustodiaRepository = pontoCustodiaRepository;
    this.conversaoEntregaFalida = conversaoEntregaFalida;
    this.publicadorEventos = publicadorEventos;
    this.consultaPatrocinador = consultaPatrocinador;
  }

  /** Desfecho do registro, para o controller traduzir em resposta. */
  public enum Desfecho {
    /** Virou missão de retirada, ABERTA, e a ocupação do ponto subiu. */
    CONVERTIDA,
    /** Ponto sem vaga: o fato foi gravado, mas nenhuma missão nasceu. */
    RECUSADA,

    /**
     * Sem patrocinador que financie o pote: gravado, nenhuma missão. Separado de RECUSADA porque a
     * transportadora reage diferente; 200 mesmo assim, porque 4xx a faria reenviar em laço (ADR
     * 0021).
     */
    SEM_PATROCINIO
  }

  /**
   * @param replay true quando repetia uma requisição já processada; o corpo é o mesmo da primeira.
   */
  public record ResultadoRegistro(
      UUID entregaFalidaId, Desfecho desfecho, UUID missaoId, boolean replay)
      implements RecursoAuditavel {

    /** A entrega falida é o recurso auditado — sem isto a trilha não diria qual entrega. */
    @Override
    public UUID idAuditoria() {
      return entregaFalidaId;
    }
  }

  /**
   * O ator da auditoria é nulo (webhook sem JWT); quem agiu está no payload e na coluna {@code
   * transportadora}.
   *
   * @param risco já calculado FORA desta transação: consultar o clima aqui prenderia o lock do
   *     ponto durante uma chamada de rede.
   */
  @Auditavel(acao = "ENTREGA_FALIDA_REGISTRADA", entidade = "entrega_falida")
  @Transactional
  public ResultadoRegistro registrar(DadosEntregaFalida dados, ResultadoRisco risco) {
    Instant agora = Instant.now();

    // 1. LOCK, como primeira leitura — senão o Hibernate devolveria o cache sem o FOR UPDATE.
    PontoCustodia ponto =
        pontoCustodiaRepository
            .buscarParaAtualizar(dados.pontoCustodiaId())
            .filter(PontoCustodia::isAtivo)
            .orElseThrow(
                () ->
                    new RecursoNaoEncontradoException(
                        "Ponto de custódia não encontrado ou inativo."));

    // 2. SONDA. Sob o lock, então o replay concorrente espera aqui em vez de duplicar.
    Optional<EntregaFalida> jaRegistrada =
        entregaFalidaRepository.findByTransportadoraAndCodigoRastreio(
            dados.transportadora(), dados.codigoRastreio());
    if (jaRegistrada.isPresent()) {
      EntregaFalida existente = jaRegistrada.get();
      // Replay reproduz o primeiro desfecho, sem reescrever nem republicar. Os três desfechos
      // precisam
      // ser distinguíveis, ou a transportadora reenviaria esperando uma vaga que não é o problema.
      return new ResultadoRegistro(
          existente.getId(), desfechoDe(existente), existente.getMissaoId(), true);
    }

    EntregaFalida entrega = new EntregaFalida(UUID.randomUUID(), dados, agora);

    // 3. VALIDA. Vaga é regra de negócio, e a recusa é um desfecho normal — não um erro.
    if (!ponto.temVaga()) {
      entrega.recusar(agora, MotivoRecusa.PONTO_LOTADO);
      entregaFalidaRepository.save(entrega);
      publicadorEventos.publicar(
          EVENTO_RECUSADA,
          entrega.getId(),
          Map.of(
              "pontoCustodiaId", ponto.getId(),
              "codigoPonto", ponto.getCodigo(),
              "apelidoPonto", ponto.getApelido(),
              "transportadora", entrega.getTransportadora(),
              "capacidade", ponto.getCapacidade()));
      log.warn(
          "Entrega recusada: ponto {} lotado ({}/{})",
          ponto.getCodigo(),
          ponto.getOcupacao(),
          ponto.getCapacidade());
      return new ResultadoRegistro(entrega.getId(), Desfecho.RECUSADA, null, false);
    }

    // 3b. VALIDA o patrocínio antes de escrever: sem ele a missão nunca poderia ser paga. Esta
    // consulta só responde "existe contrato?"; o saldo é decidido sob o lock da carteira.
    Optional<UUID> patrocinador =
        consultaPatrocinador.usuarioIdDoPatrocinadorAtivo(dados.transportadora());
    if (patrocinador.isEmpty()) {
      return recusarPorFaltaDePatrocinio(entrega, agora, true);
    }

    // Use o RETORNO de save(): com @Id atribuído e sem @Version, save() faz merge() e devolve uma
    // instância NOVA. Mutar a original depois (vincularMissao) se perderia no commit — missao_id
    // ficava nulo e a baixa da custódia não achava nada.
    EntregaFalida gerenciada = entregaFalidaRepository.save(entrega);

    // Congela o risco na entrega antes de abrir a missão: é a linha que a validação futura do
    // modelo
    // vai medir.
    gerenciada.congelarRisco(
        risco.probabilidadeFalha(),
        risco.faixaRisco(),
        risco.multiplicadorRecompensa(),
        risco.versaoModelo());

    Optional<ConversaoEntregaFalida.MissaoDeRetirada> convertida =
        conversaoEntregaFalida.abrirMissaoDeRetirada(
            new ConversaoEntregaFalida.Encomenda(
                gerenciada.getId(),
                ponto.getId(),
                dados.descricaoDoItem(),
                Coordenadas.latitude(ponto.getPonto()),
                Coordenadas.longitude(ponto.getPonto()),
                Coordenadas.latitude(dados.destino()),
                Coordenadas.longitude(dados.destino()),
                dados.cep(),
                dados.logradouro(),
                dados.bairro(),
                dados.cidade(),
                dados.uf(),
                dados.pesoKg(),
                dados.volumeL(),
                dados.valorOfertadoBrl(),
                // Só tipos JDK atravessam a porta (ArchUnit).
                risco.multiplicadorRecompensa(),
                risco.faixaRisco().name(),
                patrocinador.get(),
                agora));

    // Patrocinador sem saldo — só o lock da carteira sabia. Nada foi criado; a recusa é gravada.
    if (convertida.isEmpty()) {
      return recusarPorFaltaDePatrocinio(gerenciada, agora, false);
    }

    ConversaoEntregaFalida.MissaoDeRetirada missao = convertida.get();

    // A ocupação sobe SÓ depois de a missão existir: recusa por falta de saldo não pode ocupar
    // vaga.
    ponto.registrarEntrada();

    gerenciada.vincularMissao(missao.missaoId());

    // Notificação pela OUTBOX, na mesma transação do fato.
    Map<String, Object> payload = new HashMap<>();
    payload.put("missaoId", missao.missaoId());
    payload.put("entregaFalidaId", gerenciada.getId());
    payload.put("pontoCustodiaId", ponto.getId());
    payload.put("apelidoPonto", ponto.getApelido());
    payload.put("lat", Coordenadas.latitude(ponto.getPonto()));
    payload.put("lon", Coordenadas.longitude(ponto.getPonto()));
    payload.put("nivelMinimo", missao.nivelMinimo());
    payload.put("tokensRecompensa", missao.tokensRecompensa());
    // A faixa vai no evento para o fan-out priorizar sem consultar logistica.
    payload.put("faixaRisco", risco.faixaRisco().name());
    publicadorEventos.publicar(EVENTO_CONVERTIDA, entrega.getId(), payload);

    return new ResultadoRegistro(gerenciada.getId(), Desfecho.CONVERTIDA, missao.missaoId(), false);
  }

  /**
   * Grava a recusa por falta de patrocínio e anuncia.
   *
   * @param antesDeGravar true quando a entrega ainda não foi persistida (sem contrato); false
   *     quando já é gerenciada (saldo insuficiente) e o dirty checking basta.
   */
  private ResultadoRegistro recusarPorFaltaDePatrocinio(
      EntregaFalida entrega, Instant agora, boolean antesDeGravar) {

    entrega.recusar(agora, MotivoRecusa.SEM_PATROCINIO);
    if (antesDeGravar) {
      entregaFalidaRepository.save(entrega);
    }

    publicadorEventos.publicar(
        EVENTO_SEM_PATROCINIO,
        entrega.getId(),
        Map.of(
            "entregaFalidaId", entrega.getId(),
            "transportadora", entrega.getTransportadora(),
            "codigoRastreio", entrega.getCodigoRastreio()));

    return new ResultadoRegistro(entrega.getId(), Desfecho.SEM_PATROCINIO, null, false);
  }

  /**
   * O desfecho já gravado, para o replay reproduzi-lo — lê {@code motivo_recusa}, não só a data.
   */
  private static Desfecho desfechoDe(EntregaFalida entrega) {
    if (!entrega.foiRecusada()) {
      return Desfecho.CONVERTIDA;
    }
    return entrega.getMotivoRecusa() == MotivoRecusa.SEM_PATROCINIO
        ? Desfecho.SEM_PATROCINIO
        : Desfecho.RECUSADA;
  }

  // A baixa da custódia vive em BaixaCustodiaService, para quebrar o ciclo de beans com
  // MissaoService.
}
