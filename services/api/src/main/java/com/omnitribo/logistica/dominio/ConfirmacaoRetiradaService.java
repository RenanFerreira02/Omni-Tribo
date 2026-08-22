package com.omnitribo.logistica.dominio;

import com.omnitribo.compartilhado.api.RecursoAuditavel;
import com.omnitribo.compartilhado.dominio.Auditavel;
import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import com.omnitribo.logistica.infra.EntregaFalidaRepository;
import com.omnitribo.missoes.api.ConfirmacaoRetirada;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A transportadora confirma que a encomenda chegou ao destinatário.
 *
 * <p>Fecha a lacuna que fazia o executor esperar o prazo para receber: a missão de retirada tem o
 * usuário-sistema como criador, e {@code AtorEsperado.CRIADOR} compara IDENTIDADE, não papel — nem
 * um ADMIN conseguia chamar {@code /confirmar}. Sem este endpoint, o único caminho para CONCLUIDA
 * era a varredura de {@code app.missoes.expiracao.prazo-confirmacao}, 72 horas depois.
 *
 * <p>A varredura CONTINUA existindo e não virou redundância: ela é a rede de segurança para quando
 * a transportadora não confirma. Ver ADR 0026.
 *
 * <h2>Bean SEPARADO de {@link EntregaFalidaService}, e isso é obrigatório</h2>
 *
 * <p>Este serviço chama {@code missoes}, e {@code missoes} chama de volta {@link
 * BaixaCustodiaService} na conclusão. Se a confirmação morasse dentro de {@code
 * EntregaFalidaService} — que já é alvo de {@code ConversaoEntregaFalida} — fecharia o ciclo de
 * beans {@code EntregaFalidaService → MissaoService → EntregaFalidaService}. É a mesma razão pela
 * qual a baixa da custódia foi extraída para um bean próprio; ver o javadoc de {@code
 * BaixaCustodiaService}.
 */
@Service
public class ConfirmacaoRetiradaService {

  private static final Logger log = LoggerFactory.getLogger(ConfirmacaoRetiradaService.class);

  private final EntregaFalidaRepository entregaFalidaRepository;

  /**
   * Injetado pela INTERFACE: é o tipo declarado no campo que o ArchUnit inspeciona, e nomear {@code
   * MissaoService} aqui faria {@code logistica} alcançar {@code missoes.dominio}.
   */
  private final ConfirmacaoRetirada confirmacaoRetirada;

  public ConfirmacaoRetiradaService(
      EntregaFalidaRepository entregaFalidaRepository, ConfirmacaoRetirada confirmacaoRetirada) {
    this.entregaFalidaRepository = entregaFalidaRepository;
    this.confirmacaoRetirada = confirmacaoRetirada;
  }

  /**
   * O que a transportadora recebe de volta.
   *
   * @param tokensCreditados quanto o executor recebeu NESTA chamada. Zero num replay, e é o número
   *     honesto: a segunda confirmação não move dinheiro. Quem quer a recompensa da missão consulta
   *     a missão — atravessar a fronteira só para responder uma leitura gastaria um lock de
   *     escrita.
   */
  public record Resultado(
      UUID entregaFalidaId, UUID missaoId, long tokensCreditados, boolean replay)
      implements RecursoAuditavel {

    /**
     * A entrega falida é o recurso auditado — a mesma entidade do reporte, para que os dois atos
     * apareçam na trilha sob o mesmo {@code entidade_id}. Sem isto o {@code AuditoriaAspecto}
     * gravaria nulo, e o {@code RegrasArquiteturaTest} reprova o build por causa disso.
     */
    @Override
    public UUID idAuditoria() {
      return entregaFalidaId;
    }
  }

  /**
   * Confirma a retirada de {@code (transportadora, codigoRastreio)}.
   *
   * <p>Idempotente em DUAS camadas, e nenhuma delas basta sozinha:
   *
   * <ul>
   *   <li><b>Cinta</b> — {@code convertida_em} preenchido significa que a encomenda já saiu da
   *       custódia, carimbo que {@code BaixaCustodia.darBaixa} põe na conclusão. É o sinal de "já
   *       concluiu" que {@code logistica} possui sem cruzar fronteira nenhuma, e evita a chamada no
   *       caso comum de retry.
   *   <li><b>Suspensório</b> — duas confirmações SIMULTÂNEAS passariam as duas pela checagem acima,
   *       porque nenhuma commitou ainda. Quem as separa é a sondagem da chave de idempotência sob
   *       {@code SELECT ... FOR UPDATE}, lá dentro de {@code concluirComCredito}. Sem ela, a
   *       corrida creditaria duas vezes.
   * </ul>
   *
   * <p><b>Rastreio desconhecido e entrega sem missão são 404, não 200.</b> Diverge do ponto de
   * custódia lotado (ADR 0021) de propósito: lá o 200 existia porque havia um fato NOVO a gravar —
   * a encomenda tinha chegado e a recusa precisava ficar registrada. Aqui não há nada a registrar,
   * e a recusa da conversão já está gravada em {@code entrega_falida}. Um 200 diria "confirmado"
   * para algo que não foi confirmado.
   */
  @Auditavel(acao = "RETIRADA_CONFIRMADA", entidade = "entrega_falida")
  @Transactional
  public Resultado confirmar(String transportadora, String codigoRastreio) {
    EntregaFalida entrega =
        entregaFalidaRepository
            .findByTransportadoraAndCodigoRastreio(transportadora, codigoRastreio)
            .orElseThrow(
                () ->
                    new RecursoNaoEncontradoException(
                        "Nenhuma entrega registrada para este código de rastreio."));

    if (entrega.getMissaoId() == null) {
      // Recusada por ponto lotado ou por falta de patrocínio: o fato já está gravado, e não existe
      // missão para concluir. Reenviar não muda nada — a mensagem precisa deixar isso explícito.
      throw new RecursoNaoEncontradoException(
          "Não há missão de retirada para este código de rastreio: a encomenda não foi convertida.");
    }

    if (entrega.saiuDaCustodia()) {
      log.debug("Confirmação repetida da entrega {} — no-op.", entrega.getId());
      return new Resultado(entrega.getId(), entrega.getMissaoId(), 0L, true);
    }

    long tokens = confirmacaoRetirada.confirmarRetirada(entrega.getMissaoId());
    return new Resultado(entrega.getId(), entrega.getMissaoId(), tokens, false);
  }
}
