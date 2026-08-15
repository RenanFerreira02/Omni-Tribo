package com.omnitribo.integracoes.infra;

import com.omnitribo.integracoes.api.ServicoExternoIndisponivelException;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bulkhead: teto de chamadas SIMULTÂNEAS a um provedor externo.
 *
 * <p><b>O timeout sozinho não resolve o problema que este teto resolve.</b> Os dois clientes já têm
 * connect e read timeout de 2 s, e o cache deliberadamente não guarda falha (guardar tornaria a
 * recuperação lenta de propósito). A consequência é que um provedor fora do ar não reduz o tráfego
 * em nada: toda requisição vai até ele e segura uma thread do servlet por 2 s. Com tráfego
 * suficiente, o pool de threads acaba — e aí o card de clima, que é decoração, derruba o login, que
 * não é. Foi exatamente o cenário que o javadoc do timeout diz querer evitar; o timeout só o
 * deslocou do infinito para 2 s.
 *
 * <p>Uma instância POR PROVEDOR, e é isso que faz dele um bulkhead: o CEP fora do ar não consome as
 * permissões do clima. Compartilhar um contador só transformaria a queda de um na queda dos dois.
 *
 * <p>{@code tryAcquire()} sem espera: sem permissão, a resposta é o 503 imediato que a UI já sabe
 * tratar (ADR 0011 — o recurso some da tela). Enfileirar seria recriar o problema com outro nome,
 * porque quem espera também está segurando uma thread.
 *
 * <p>Deliberadamente sem Resilience4j: seria uma dependência nova para 20 linhas, e o CLAUDE.md
 * corta infraestrutura que não foi pedida. Ver ADR 0023 para a verificação de compatibilidade que
 * fundamentou manter essa escolha.
 *
 * <p><b>Esta classe continua não sendo um circuit breaker, e agora existe um.</b> Ela sozinha deixa
 * o provedor ser consultado durante a queda inteira, só que por no máximo N threads de cada vez —
 * protege a aplicação, não o provedor. Quem para de chamar é {@link DisjuntorDeChamadasExternas},
 * que fica POR FORA desta ({@link ProtecaoDeChamadasExternas} monta a ordem), de modo que uma
 * chamada recusada pelo circuito aberto não chega a consumir permissão nenhuma.
 *
 * <p>Uma consequência da ordem escolhida vale ficar registrada aqui, onde o contador mora: como o
 * retry roda POR DENTRO desta classe, a permissão fica retida pela rajada inteira. É intencional —
 * preserva a invariante afirmada acima, de que permissão retida significa thread nossa presa neste
 * provedor. Uma thread dormindo no backoff está presa do mesmo jeito.
 */
final class LimiteDeChamadasExternas {

  private static final Logger log = LoggerFactory.getLogger(LimiteDeChamadasExternas.class);

  private final String provedor;
  private final Semaphore permissoes;

  LimiteDeChamadasExternas(String provedor, int simultaneas) {
    this.provedor = provedor;
    this.permissoes = new Semaphore(simultaneas);
  }

  <T> T executar(Supplier<T> chamada) {
    if (!permissoes.tryAcquire()) {
      // warn e não error: é degradação prevista, com reação de UI definida. Mas precisa aparecer,
      // porque é o sinal de que o provedor está lento — o 503 sozinho não distingue "provedor
      // respondeu erro" de "nossa fila encheu".
      log.warn(
          "Teto de chamadas simultâneas atingido para {}; devolvendo 503 sem chamar", provedor);
      throw new ServicoExternoIndisponivelException(
          "O serviço externo está congestionado. Tente novamente em instantes.");
    }
    try {
      return chamada.get();
    } finally {
      permissoes.release();
    }
  }
}
