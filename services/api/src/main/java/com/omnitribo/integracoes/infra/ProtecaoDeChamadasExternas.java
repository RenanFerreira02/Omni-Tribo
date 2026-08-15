package com.omnitribo.integracoes.infra;

import java.util.function.Supplier;
import org.springframework.core.retry.RetryTemplate;

/**
 * As três proteções de uma chamada externa, na ordem que importa.
 *
 * <pre>
 *   cache (dominio) → DISJUNTOR → BULKHEAD → RETRY → HTTP
 * </pre>
 *
 * <p><b>Disjuntor por FORA do bulkhead.</b> Com o circuito aberto a chamada morre antes de tocar o
 * semáforo, e "provedor morto não custa nada" vira propriedade literal, não aproximada. Na ordem
 * inversa, cada recusa ainda adquiriria e devolveria uma permissão — trabalho inútil justamente no
 * caminho mais quente de uma queda.
 *
 * <p><b>Bulkhead por FORA do retry, e isto preserva uma invariante que já estava escrita.</b> O
 * javadoc de {@link LimiteDeChamadasExternas} afirma que permissão retida == thread nossa presa
 * neste provedor. Se o semáforo fosse adquirido por TENTATIVA, uma thread dormindo no backoff
 * estaria presa sem consumir permissão, e o contador passaria a subnotificar exatamente o que
 * existe para medir. O preço é que a permissão fica retida pela rajada inteira — no pior caso
 * {@code 2 × timeout + espera}, hoje ~4,2 s contra os 2 s de antes. É aceitável porque o teto de
 * permissões continua sendo 8 por provedor: no pior caso são 8 threads ocupadas, não o pool.
 *
 * <p><b>Retry por DENTRO do disjuntor, e esta é a decisão central.</b> Uma rajada de tentativas
 * precisa contar como UMA falha lógica. Com o retry por fora, 3 tentativas incrementariam o
 * contador 3 vezes e o disjuntor abriria em um terço do limiar configurado: o número no YAML
 * deixaria de significar o que diz, e mudar o número de tentativas re-sintonizaria o disjuntor em
 * silêncio. Dois parâmetros que precisam ser independentes ficariam acoplados. Aqui isso não exige
 * código nenhum — cai da ordem de aninhamento.
 *
 * <p>A tradução para 503 fica FORA de tudo isto, nos clientes: o retry e o disjuntor precisam ver a
 * exceção original ({@code ResourceAccessException}, {@code HttpServerErrorException}) para
 * classificá-la. Converter antes cegaria os dois.
 */
final class ProtecaoDeChamadasExternas {

  private final DisjuntorDeChamadasExternas disjuntor;
  private final LimiteDeChamadasExternas limite;
  private final RetryTemplate repeticao;

  ProtecaoDeChamadasExternas(
      DisjuntorDeChamadasExternas disjuntor,
      LimiteDeChamadasExternas limite,
      RetryTemplate repeticao) {
    this.disjuntor = disjuntor;
    this.limite = limite;
    this.repeticao = repeticao;
  }

  /**
   * {@code invoke(Supplier)}, e NUNCA {@code execute(Retryable)}: só o primeiro desembrulha o
   * {@code RetryException} e relança a exceção original. Com {@code execute}, o disjuntor veria
   * {@code RetryException} em vez de {@code HttpServerErrorException}, classificaria tudo como "não
   * é falha do provedor" e NUNCA ABRIRIA — sem nenhum teste ficar vermelho.
   */
  <T> T executar(Supplier<T> chamada) {
    return disjuntor.executar(() -> limite.executar(() -> repeticao.invoke(chamada)));
  }

  DisjuntorDeChamadasExternas disjuntor() {
    return disjuntor;
  }
}
