package com.omnitribo.carteira.dominio;

import com.omnitribo.carteira.infra.CarteiraRepository;
import com.omnitribo.carteira.infra.LancamentoRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * O ÚNICO ponto do sistema onde um saldo muda.
 *
 * <p>Modelo: {@code lancamento} é a verdade e é append-only; {@code carteira.saldo_*} é projeção
 * derivada, mantida junto para leitura barata. Toda mutação é um INSERT no ledger seguido da
 * atualização da projeção, na MESMA transação — as duas não têm como divergir. Corrigir é ESTORNAR
 * (linha nova com sinal oposto), nunca UPDATE: o banco revoga UPDATE e DELETE em {@code lancamento}
 * para o role {@code omnitribo_app} desde a V5.
 *
 * <h2>Contrato não negociável do chamador</h2>
 *
 * Quem chama {@link #registrar} JÁ SEGURA {@code PESSIMISTIC_WRITE} sobre a carteira passada, e
 * esse lock foi a PRIMEIRA leitura dessa entidade na transação. Não é recomendação de estilo — é a
 * premissa que torna tudo aqui correto:
 *
 * <ul>
 *   <li>O saldo lido para calcular {@code saldo_apos_*} é o commitado mais recente. Sob READ
 *       COMMITTED, quando uma transação bloqueia num {@code FOR UPDATE} e o detentor commita, o
 *       PostgreSQL faz EvalPlanQual: relê a versão mais nova da linha em vez de seguir com o
 *       snapshot antigo. {@code FOR UPDATE} não é só exclusão mútua, ele PROMOVE a leitura a
 *       leitura fresca — e é por isso que READ COMMITTED basta aqui, sem REPEATABLE READ nem
 *       SERIALIZABLE.
 *   <li>A janela entre SONDAR a chave de idempotência e INSERIR está fechada. Duas transações
 *       capazes de produzir a mesma chave estão serializadas pela mesma linha, então a sondagem do
 *       perdedor roda estritamente depois do commit do vencedor e enxerga a linha dele.
 * </ul>
 *
 * <p>Ordem obrigatória em todo caminho: <b>adquira todos os locks → sonde → valide → escreva.</b>
 *
 * <h2>Por que nada aqui captura DataIntegrityViolationException</h2>
 *
 * {@code uk_lancamento_idempotencia} é a garantia final de unicidade, não o mecanismo. O no-op de
 * replay é decidido ANTES do INSERT, pela sondagem sob o lock. Se a constraint algum dia disparar,
 * significa que a serialização acima deixou de valer — é defeito, não corrida a recuperar, e sobe
 * como 500 com {@code log.error}. Capturar aqui seria pior de duas formas: mascararia o defeito, e
 * em Spring um catch de violação de constraint dentro da transação já a marcou rollback-only, então
 * o "tratamento" produziria um commit impossível.
 *
 * <p>{@code REQUIRES_NEW} está fora de cogitação neste módulo inteiro. A transação externa segura
 * {@code SELECT ... FOR UPDATE} enquanto a interna pediria uma SEGUNDA conexão: com N requisições
 * simultâneas e pool de tamanho P, basta N ≥ P para todas as conexões ficarem presas esperando
 * conexões que nunca virão. É deadlock de pool, e já aconteceu neste repositório — ver o javadoc de
 * {@code RegistroCheckinService}.
 */
@Service
public class LivroRazaoService {

  private final CarteiraRepository carteiraRepository;
  private final LancamentoRepository lancamentoRepository;

  public LivroRazaoService(
      CarteiraRepository carteiraRepository, LancamentoRepository lancamentoRepository) {
    this.carteiraRepository = carteiraRepository;
    this.lancamentoRepository = lancamentoRepository;
  }

  /**
   * Grava um movimento no ledger e atualiza a projeção de saldo.
   *
   * <p>{@code MANDATORY}: chamar isto fora de uma transação é erro de programação. Com {@code
   * REQUIRED} o método abriria transação própria e o lock que o contrato exige não existiria —
   * falhar na chamada é melhor que gravar sem a serialização que dá sentido ao resultado.
   *
   * @param carteira entidade JÁ TRAVADA pelo chamador
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Lancamento registrar(Carteira carteira, Movimento movimento) {
    if (movimento.sinal() == SinalLancamento.CREDITO) {
      carteira.creditar(movimento.valorBrl(), movimento.valorTokens());
    } else {
      // Guarda de erro de programação: o chamador já recusou com 422 sob o lock.
      carteira.debitar(movimento.valorBrl(), movimento.valorTokens());
    }

    Lancamento lancamento =
        new Lancamento(
            UUID.randomUUID(),
            carteira.getId(),
            movimento.sinal(),
            movimento.motivo(),
            movimento.valorBrl(),
            movimento.valorTokens(),
            movimento.missaoId(),
            movimento.contraparteCarteiraId(),
            movimento.chaveIdempotencia(),
            // Saldos LIDOS DEPOIS da mutação: a linha registra o estado resultante, que é o que
            // permite auditar o ledger sem recalcular a soma inteira a cada verificação.
            carteira.getSaldoBrl(),
            carteira.getSaldoTokens(),
            movimento.mensagem(),
            movimento.agora());

    // saveAndFlush, e não save: força o INSERT a chegar ao PostgreSQL AGORA, dentro da transação do
    // chamador, em vez de ser adiado para o flush do commit. Duas consequências, ambas desejadas.
    // Primeira, a constraint de idempotência é avaliada enquanto ainda há como reagir. Segunda, o
    // teste de rollback deixa de ser vácuo: com save, o INSERT nunca seria emitido numa transação
    // que aborta, e o teste afirmaria a ausência de uma linha que jamais foi tentada.
    Lancamento gravado = lancamentoRepository.saveAndFlush(lancamento);
    carteiraRepository.save(carteira);
    return gravado;
  }

  /**
   * Sonda um lançamento já gravado pela chave derivada.
   *
   * <p>Só é autoritativa sob o lock exigido pelo contrato da classe. Fora dele, é um palpite: a
   * linha pode ser inserida por outra transação entre esta leitura e a escrita seguinte.
   */
  @Transactional(readOnly = true)
  public Optional<Lancamento> consultar(String chaveIdempotencia) {
    return lancamentoRepository.findByChaveIdempotencia(chaveIdempotencia);
  }
}
