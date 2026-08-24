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
 * Converte uma entrega que falhou em missão comunitária de retirada.
 *
 * <p>É a tese do produto virando código: o pacote que a transportadora não conseguiu entregar fica
 * na loja do bairro, e um vizinho ganha XP e token por levá-lo ao destinatário. Para a
 * transportadora, evita a segunda viagem; para o bairro, é renda comunitária.
 *
 * <h2>Ordem das operações</h2>
 *
 * <p>Rigorosamente a ordem canônica do projeto: <b>adquira todos os locks → sonde a chave de
 * idempotência → valide as regras → escreva</b>. É o lock que fecha a corrida entre sondar e
 * inserir, não a sondagem.
 *
 * <p>O lock aqui é o do PONTO DE CUSTÓDIA, e ele resolve dois problemas de uma vez: serializa a
 * disputa por vaga (dois webhooks não podem ler a mesma ocupação e ambos incrementar) e serializa o
 * replay do mesmo webhook (dois INSERTs idênticos não empatam na UNIQUE, com 500 para o perdedor).
 *
 * <h2>Recusa volta como VALOR</h2>
 *
 * <p>Ponto sem vaga NÃO lança. A recusa precisa ser GRAVADA — a transportadora tem direito de saber
 * onde o pacote dela parou —, e lançar de dentro da transação apagaria a linha no rollback. Usar
 * {@code REQUIRES_NEW} para preservá-la travaria a aplicação inteira, porque a transação externa já
 * segura um {@code FOR UPDATE} e a interna pediria uma segunda conexão. Mesmo padrão do check-in
 * rejeitado: o serviço devolve um resultado, a transação commita, e quem decide o status HTTP é o
 * controller.
 */
@Service
public class EntregaFalidaService {

  private static final Logger log = LoggerFactory.getLogger(EntregaFalidaService.class);

  /** Evento de notificação: há missão nova de retirada perto de você. */
  public static final String EVENTO_CONVERTIDA = "EntregaFalidaConvertida";

  /** Evento operacional: um ponto está lotado e recusou encomenda. */
  public static final String EVENTO_RECUSADA = "EntregaFalidaRecusada";

  /**
   * Evento operacional: não havia patrocínio para financiar o pote.
   *
   * <p>Tipo PRÓPRIO, e o {@code DespachanteAlertaService} ganhou o case correspondente no mesmo
   * commit. Publicar um tipo que o despachante não conhece cairia no {@code default} que lança, e a
   * outbox tentaria cinco vezes antes de abandonar o evento em silêncio — sem carta-morta, sem
   * métrica, sem endpoint. Ver Pendência #4 do CLAUDE.md.
   */
  public static final String EVENTO_SEM_PATROCINIO = "EntregaFalidaSemPatrocinio";

  private final EntregaFalidaRepository entregaFalidaRepository;
  private final PontoCustodiaRepository pontoCustodiaRepository;
  private final ConversaoEntregaFalida conversaoEntregaFalida;
  private final PublicadorEventos publicadorEventos;

  // Injetado pela INTERFACE: logistica não pode alcançar identidade.dominio, onde vive
  // PatrocinadorService. É o tipo declarado no campo que o ArchUnit inspeciona.
  private final ConsultaPatrocinador consultaPatrocinador;

  public EntregaFalidaService(
      EntregaFalidaRepository entregaFalidaRepository,
      PontoCustodiaRepository pontoCustodiaRepository,
      // Injetado pela INTERFACE, e não pela implementação: o tipo declarado no campo é o que o
      // ArchUnit inspeciona, e nomear MissaoService aqui faria logistica alcançar missoes.dominio.
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
     * Sem patrocinador que financie o pote: o fato foi gravado, e nenhuma missão nasceu.
     *
     * <p>Desfecho SEPARADO de RECUSADA, e não um motivo dentro dela, porque a reação da
     * transportadora é diferente: ponto lotado pode abrir vaga, patrocínio ausente não. Continua
     * sendo 200 — a requisição foi bem formada, autenticada e processada, e devolver 4xx faria a
     * transportadora reenviar em laço contra uma condição que o reenvio não muda (ADR 0021).
     */
    SEM_PATROCINIO
  }

  /**
   * @param replay verdadeiro quando a requisição era repetição de uma já processada. O corpo
   *     devolvido é o mesmo da primeira vez — é o que faz o retry da transportadora ser seguro.
   */
  public record ResultadoRegistro(
      UUID entregaFalidaId, Desfecho desfecho, UUID missaoId, boolean replay)
      implements RecursoAuditavel {

    /**
     * A entrega falida é o recurso auditado. Sem isto o {@code AuditoriaAspecto} gravaria {@code
     * entidade_id} nulo, e a trilha diria "uma entrega foi registrada" sem dizer qual — inútil para
     * reconstruir um incidente com a transportadora.
     */
    @Override
    public UUID idAuditoria() {
      return entregaFalidaId;
    }
  }

  /**
   * O ator da auditoria é NULO aqui, e isso é esperado: não há JWT no webhook, e {@code
   * AuditoriaAspecto.extrairAtorId} devolve nulo fora de contexto de segurança. Quem agiu está no
   * payload do evento e no campo {@code transportadora} da própria linha.
   */
  /**
   * @param risco já calculado pelo chamador, <b>fora desta transação</b>. Recebê-lo pronto não é
   *     detalhe de estilo: calcular aqui exigiria consultar o provedor de clima com o {@code FOR
   *     UPDATE} do ponto de custódia já adquirido logo abaixo, prendendo o lock durante uma chamada
   *     de rede. Ver {@code WebhookTransportadoraController.avaliarRisco}.
   */
  @Auditavel(acao = "ENTREGA_FALIDA_REGISTRADA", entidade = "entrega_falida")
  @Transactional
  public ResultadoRegistro registrar(DadosEntregaFalida dados, ResultadoRisco risco) {
    Instant agora = Instant.now();

    // 1. LOCK. Primeira leitura da transação, obrigatoriamente: se o ponto já estivesse no
    // persistence context, o Hibernate devolveria a instância em cache sem reemitir o FOR UPDATE.
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
      // Nada é reescrito e nada é publicado de novo: a resposta reproduz o primeiro desfecho.
      // Os TRÊS desfechos precisam ser distinguíveis aqui: um replay que respondesse RECUSADA a uma
      // recusa por falta de patrocínio diria à transportadora que o ponto estava lotado — e ela
      // reenviaria esperando que a vaga abrisse.
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

    // 3b. VALIDA o patrocínio, ANTES de escrever qualquer coisa.
    //
    // A missão de retirada paga o executor DO POTE desde a V23, e o pote dela é financiado pelo
    // patrocinador da transportadora. Sem patrocinador ativo não há de onde pagar, e criar a missão
    // assim seria pior que recusá-la: alguém aceitaria, faria a entrega, e a conclusão falharia com
    // 422 para sempre — num caminho que só conclui pela varredura de prazo, então nem apareceria
    // numa requisição.
    //
    // Consulta sem lock, e isso é suficiente AQUI: ela responde "existe contrato?", não "tem
    // saldo?". Quem responde a segunda é a carteira, sob FOR UPDATE, dentro de
    // abrirMissaoDeRetirada — entre esta leitura e aquele lock caberia outro webhook consumindo o
    // saldo, e é por isso que a decisão financeira não mora aqui.
    Optional<UUID> patrocinador =
        consultaPatrocinador.usuarioIdDoPatrocinadorAtivo(dados.transportadora());
    if (patrocinador.isEmpty()) {
      return recusarPorFaltaDePatrocinio(entrega, agora, true);
    }

    // O RETORNO de save() é obrigatório aqui, e não é estilo.
    //
    // EntregaFalida tem @Id ATRIBUÍDO (UUID.randomUUID no construtor) e não tem @Version, então
    // `isNew()` do Spring Data devolve false — o id já está preenchido — e save() chama
    // `em.merge()`, não `em.persist()`. merge() devolve uma instância NOVA e gerenciada; a original
    // continua DESTACADA. Mutar a original depois disto (vincularMissao) não é visto pelo dirty
    // checking e some no commit, sem erro nenhum.
    //
    // O sintoma foi exatamente esse: a linha era gravada, a missão era criada, e `missao_id` ficava
    // nulo para sempre — a entrega falida nunca sabia qual missão a resolveu, e a baixa da custódia
    // na conclusão não achava nada para dar baixa. Nada falha em tempo de compilação.
    EntregaFalida gerenciada = entregaFalidaRepository.save(entrega);

    // Congela na entrega ANTES de abrir a missão: as características que produziram o score estão
    // nesta linha, e é a partir dela que a validação futura contra dados reais vai medir se o
    // modelo
    // acertou. O mesmo multiplicador vai adiante e é congelado também na missão.
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
                // Só tipos JDK atravessam a porta: `missoes` não pode importar `logistica.dominio`,
                // então a faixa viaja como String e o multiplicador como BigDecimal.
                risco.multiplicadorRecompensa(),
                risco.faixaRisco().name(),
                patrocinador.get(),
                agora));

    // Patrocinador existe mas não tem saldo — só o lock da carteira podia dizer isso. Nenhuma
    // missão foi criada e nenhum lançamento existe; a transação segue viva justamente para que a
    // recusa seja GRAVADA.
    if (convertida.isEmpty()) {
      return recusarPorFaltaDePatrocinio(gerenciada, agora, false);
    }

    ConversaoEntregaFalida.MissaoDeRetirada missao = convertida.get();

    // A ocupação sobe SÓ AQUI, depois de a missão existir. Ela ficava antes da conversão, e a
    // posição antiga deixou de servir quando a conversão passou a poder não acontecer: um webhook
    // recusado por falta de saldo teria ocupado uma vaga para uma encomenda sem missão que a
    // retire, e a invariante `ocupacao = pendentes + convertidas não concluídas` de MigracaoTest
    // reprovaria — com razão, porque a vaga estaria perdida até alguém reparar à mão.
    ponto.registrarEntrada();

    gerenciada.vincularMissao(missao.missaoId());

    // Notificação vai pela OUTBOX, na mesma transação do fato. Notificar direto daqui anunciaria
    // antes do commit o que um rollback desfaz; notificar depois perderia o que falhasse.
    Map<String, Object> payload = new HashMap<>();
    payload.put("missaoId", missao.missaoId());
    payload.put("entregaFalidaId", gerenciada.getId());
    payload.put("pontoCustodiaId", ponto.getId());
    payload.put("apelidoPonto", ponto.getApelido());
    payload.put("lat", Coordenadas.latitude(ponto.getPonto()));
    payload.put("lon", Coordenadas.longitude(ponto.getPonto()));
    payload.put("nivelMinimo", missao.nivelMinimo());
    payload.put("tokensRecompensa", missao.tokensRecompensa());
    // A faixa viaja no evento para o fan-out poder priorizar. Sem ela, o despachante teria de
    // consultar `logistica` — que é justamente a dependência que a outbox existe para evitar.
    payload.put("faixaRisco", risco.faixaRisco().name());
    publicadorEventos.publicar(EVENTO_CONVERTIDA, entrega.getId(), payload);

    return new ResultadoRegistro(gerenciada.getId(), Desfecho.CONVERTIDA, missao.missaoId(), false);
  }

  /**
   * Grava a recusa por falta de patrocínio e anuncia o fato. Os dois pontos de chamada diferem só
   * em QUANDO a falta foi descoberta.
   *
   * @param antesDeGravar {@code true} quando a entrega ainda não foi persistida (patrocinador
   *     inexistente ou inativo, descoberto antes de qualquer escrita); {@code false} quando ela já
   *     é entidade gerenciada (saldo insuficiente, descoberto sob o lock da carteira). O {@code
   *     save} só é necessário no primeiro caso — no segundo o dirty checking já cuida da alteração.
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
   * O desfecho já gravado numa entrega, para o replay reproduzi-lo.
   *
   * <p>Lê {@code motivo_recusa} em vez de só {@code recusada_em}: as duas recusas pedem reações
   * diferentes da transportadora, e responder RECUSADA a uma falta de patrocínio a faria reenviar
   * esperando uma vaga que não é o problema.
   */
  private static Desfecho desfechoDe(EntregaFalida entrega) {
    if (!entrega.foiRecusada()) {
      return Desfecho.CONVERTIDA;
    }
    return entrega.getMotivoRecusa() == MotivoRecusa.SEM_PATROCINIO
        ? Desfecho.SEM_PATROCINIO
        : Desfecho.RECUSADA;
  }

  // A baixa da custódia NÃO mora aqui: vive em BaixaCustodiaService, para quebrar o ciclo de beans
  // MissaoService → BaixaCustodia → ConversaoEntregaFalida → MissaoService. Ver o javadoc de lá.
}
