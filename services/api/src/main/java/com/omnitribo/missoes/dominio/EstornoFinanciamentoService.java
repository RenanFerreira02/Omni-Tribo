package com.omnitribo.missoes.dominio;

import com.omnitribo.carteira.api.EstornoPote;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Devolve o pote aos financiadores quando a missão termina sem pagar ninguém.
 *
 * <p>Existe porque a conservação da moeda, sozinha, não basta. Uma missão TRIBO pode ser financiada
 * e depois CANCELADA pelo criador ou EXPIRADA pelo job de expiração. Sem estorno, os tokens
 * ficariam em {@code missao.pote_tokens} para sempre: a soma (carteiras + potes) continuaria
 * fechando, mas uma parte dela estaria em custódia que ninguém mais alcança — economicamente
 * idêntico a queimar dinheiro de terceiros, e o teste de conservação passaria contando tokens que
 * na prática sumiram.
 *
 * <p>Vive em {@code missoes} porque quem é dono do pote é a missão. O crédito de volta nas
 * carteiras vai pela porta {@link EstornoPote}, já que o ArchUnit proíbe {@code missoes} de tocar
 * {@code carteira.dominio}.
 */
@Service
public class EstornoFinanciamentoService {

  private final EstornoPote estornoPote;

  public EstornoFinanciamentoService(EstornoPote estornoPote) {
    this.estornoPote = estornoPote;
  }

  /**
   * Estorna todo o pote e o zera, na transação do chamador.
   *
   * <p>O chamador já segura o lock da linha da missão, então a ordem global {@code missao} → {@code
   * carteira} é respeitada mesmo quando isto roda dentro do lote do job de expiração.
   *
   * <p>Idempotente por construção: a chave de cada estorno é derivada de {@code (missaoId,
   * carteiraFinanciadorId)}, sem participação do cliente. Se o lote de expiração reprocessar a
   * mesma missão, os lançamentos já existem e nada é gravado de novo.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void estornarPote(Missao missao, Instant agora) {
    long noPote = missao.getPoteTokens();
    long devolvido = estornoPote.estornarFinanciadores(missao.getId(), agora);

    // O pote só cresce por financiamento e só encolhe pagando a conclusão. Num estado terminal SEM
    // pagamento, a soma dos financiamentos tem de bater exatamente com o pote. Divergir aqui
    // significa que token entrou ou saiu do pote por um caminho não contabilizado — falhar alto,
    // porque a alternativa é uma economia que não fecha e ninguém percebe.
    if (devolvido != noPote) {
      throw new IllegalStateException(
          "Estorno da missão "
              + missao.getId()
              + " devolveu "
              + devolvido
              + " tokens mas o pote tinha "
              + noPote
              + ".");
    }
    missao.zerarPote();
  }
}
