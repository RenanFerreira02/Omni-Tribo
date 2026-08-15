package com.omnitribo.logistica.dominio;

import com.omnitribo.compartilhado.api.PublicadorEventos;
import com.omnitribo.compartilhado.api.RecursoAuditavel;
import com.omnitribo.compartilhado.dominio.Auditavel;
import com.omnitribo.compartilhado.dominio.Coordenadas;
import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
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

  private final EntregaFalidaRepository entregaFalidaRepository;
  private final PontoCustodiaRepository pontoCustodiaRepository;
  private final ConversaoEntregaFalida conversaoEntregaFalida;
  private final PublicadorEventos publicadorEventos;

  public EntregaFalidaService(
      EntregaFalidaRepository entregaFalidaRepository,
      PontoCustodiaRepository pontoCustodiaRepository,
      // Injetado pela INTERFACE, e não pela implementação: o tipo declarado no campo é o que o
      // ArchUnit inspeciona, e nomear MissaoService aqui faria logistica alcançar missoes.dominio.
      ConversaoEntregaFalida conversaoEntregaFalida,
      PublicadorEventos publicadorEventos) {
    this.entregaFalidaRepository = entregaFalidaRepository;
    this.pontoCustodiaRepository = pontoCustodiaRepository;
    this.conversaoEntregaFalida = conversaoEntregaFalida;
    this.publicadorEventos = publicadorEventos;
  }

  /** Desfecho do registro, para o controller traduzir em resposta. */
  public enum Desfecho {
    /** Virou missão de retirada, ABERTA, e a ocupação do ponto subiu. */
    CONVERTIDA,
    /** Ponto sem vaga: o fato foi gravado, mas nenhuma missão nasceu. */
    RECUSADA
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
  @Auditavel(acao = "ENTREGA_FALIDA_REGISTRADA", entidade = "entrega_falida")
  @Transactional
  public ResultadoRegistro registrar(DadosEntregaFalida dados) {
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
      return new ResultadoRegistro(
          existente.getId(),
          existente.foiRecusada() ? Desfecho.RECUSADA : Desfecho.CONVERTIDA,
          existente.getMissaoId(),
          true);
    }

    EntregaFalida entrega = new EntregaFalida(UUID.randomUUID(), dados, agora);

    // 3. VALIDA. Vaga é regra de negócio, e a recusa é um desfecho normal — não um erro.
    if (!ponto.temVaga()) {
      entrega.recusar(agora);
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

    // 4. ESCREVE. A ordem interna importa: a encomenda entra na custódia, a missão é criada
    // apontando para o ponto, e só então a entrega guarda o id da missão.
    ponto.registrarEntrada();

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

    ConversaoEntregaFalida.MissaoDeRetirada missao =
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
                agora));

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
    publicadorEventos.publicar(EVENTO_CONVERTIDA, entrega.getId(), payload);

    return new ResultadoRegistro(gerenciada.getId(), Desfecho.CONVERTIDA, missao.missaoId(), false);
  }

  // A baixa da custódia NÃO mora aqui: vive em BaixaCustodiaService, para quebrar o ciclo de beans
  // MissaoService → BaixaCustodia → ConversaoEntregaFalida → MissaoService. Ver o javadoc de lá.

  /** Só para leitura em teste e diagnóstico. */
  @Transactional(readOnly = true)
  public Optional<EntregaFalida> porRastreio(String transportadora, String codigoRastreio) {
    return entregaFalidaRepository.findByTransportadoraAndCodigoRastreio(
        transportadora, codigoRastreio);
  }
}
