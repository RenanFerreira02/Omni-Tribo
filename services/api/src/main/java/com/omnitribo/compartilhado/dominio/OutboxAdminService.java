package com.omnitribo.compartilhado.dominio;

import com.omnitribo.compartilhado.api.EventoEsgotadoResponse;
import com.omnitribo.compartilhado.api.ReenfileiramentoResponse;
import com.omnitribo.compartilhado.infra.OutboxRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Carta-morta da outbox: enxergar o que o drenador desistiu de entregar, e devolvê-lo à fila.
 *
 * <p>Existe porque {@code DrenadorOutboxService} para na quinta falha e a linha sai do predicado do
 * lote para sempre — {@code publicado_em} nulo, {@code tentativas} no teto, {@code ultimo_erro}
 * preenchido, e nada no sistema a mostrando. O fato perdido mais caro é um {@code MissaoConcluida}:
 * o executor recebeu o token e nunca foi avisado. Ver ADR 0031.
 *
 * <p><b>Este serviço não despacha nada.</b> Reenfileirar só torna a linha elegível outra vez; quem
 * entrega continua sendo o {@code DrenadorOutboxService}, na varredura seguinte do {@code
 * DrenadorOutboxJob}. Chamar {@code drenarLote} daqui seria o atalho: ele opera sobre o LOTE, não
 * sobre uma linha, e faria o resultado do despacho virar o status HTTP de uma operação
 * administrativa — um destino fora do ar devolveria 500 a quem só pediu "tente de novo".
 *
 * <p>{@code maximoTentativas} é lido da MESMA chave que o drenador usa. É o que garante que "o que
 * saiu do lote" e "o que esta consulta mostra" não divirjam se o teto mudar no YAML.
 */
@Service
public class OutboxAdminService {

  private static final String NAO_ENCONTRADO = "Evento de outbox não encontrado.";

  private final OutboxRepository outboxRepository;
  private final int maximoTentativas;

  public OutboxAdminService(
      OutboxRepository outboxRepository,
      @Value("${app.outbox.maximo-tentativas:5}") int maximoTentativas) {
    this.outboxRepository = outboxRepository;
    this.maximoTentativas = maximoTentativas;
  }

  @Transactional(readOnly = true)
  public Page<EventoEsgotadoResponse> listarEsgotados(Pageable paginacao) {
    return outboxRepository
        .buscarEsgotados(maximoTentativas, paginacao)
        .map(EventoEsgotadoResponse::de);
  }

  /**
   * Devolve um evento esgotado ao predicado do lote. Idempotente por ESTADO DA LINHA.
   *
   * <p>Ordem canônica do projeto, com a sondagem no meio — o 403 já foi resolvido pelo
   * {@code @PreAuthorize} do controller, antes de qualquer leitura que revelasse estado:
   *
   * <ol>
   *   <li><b>LOCK</b>, primeira leitura da transação. É ele que fecha a corrida entre sondar e
   *       escrever, e é ele que serializa este endpoint contra o próprio drenador, que trava a
   *       mesma linha. Sem SKIP LOCKED aqui de propósito: pular uma linha travada devolveria 404
   *       para um evento que existe.
   *   <li><b>SONDA</b>, sob o lock, e portanto autoritativa.
   *   <li><b>409</b> se já publicado.
   *   <li><b>ESCREVE</b> só no caso que sobra.
   * </ol>
   *
   * <p>Não há {@code Idempotency-Key}, e a ausência é deliberada: a chave existe no projeto para
   * impedir que um retry de rede CRIE UMA SEGUNDA LINHA no ledger. Aqui não há linha a criar — o
   * efeito é um UPDATE para um estado fixo, sobre um alvo nomeado na URL, sob {@code FOR UPDATE}. A
   * segunda chamada é no-op por construção, e a sondagem a reconhece.
   *
   * <p><b>A auditoria registra a REQUISIÇÃO, não necessariamente uma mudança de estado.</b> O
   * {@code AuditoriaAspecto} é {@code @AfterReturning}: ele grava também quando este método devolve
   * {@code reenfileirado = false}. Contar linhas de {@code auditoria} superestima quantos
   * reenfileiramentos de fato aconteceram; o discriminador é o campo da resposta, e ele não é
   * persistido.
   */
  @Auditavel(acao = "OUTBOX_REENFILEIRADA", entidade = "outbox")
  @Transactional
  public ReenfileiramentoResponse reenfileirar(UUID eventoId) {
    Outbox evento =
        outboxRepository
            .buscarParaAtualizar(eventoId)
            .orElseThrow(() -> new RecursoNaoEncontradoException(NAO_ENCONTRADO));

    // 409, não 200: reenfileirar um evento entregue produziria entrega duplicada. Responder no-op
    // aqui faria um ADMIN acreditar que recuperou algo que nunca esteve perdido.
    if (evento.getPublicadoEm() != null) {
      throw new TransicaoInvalidaException(
          "Evento já foi entregue em "
              + evento.getPublicadoEm()
              + " e não pode ser reenfileirado.");
    }

    // No-op idempotente, e os DOIS casos que caem aqui dão no mesmo: o replay do próprio POST
    // (tentativas já zeradas) e o evento ainda em backoff. Nos dois a linha já vai ser tentada de
    // novo — e mexer em proxima_tentativa_em atropelaria o backoff, que existe para não transformar
    // retry em rajada contra um destino fora do ar. Não é o que este endpoint existe para fazer.
    if (evento.getTentativas() < maximoTentativas) {
      return ReenfileiramentoResponse.de(evento, false);
    }

    evento.reenfileirar(Instant.now());
    return ReenfileiramentoResponse.de(evento, true);
  }
}
