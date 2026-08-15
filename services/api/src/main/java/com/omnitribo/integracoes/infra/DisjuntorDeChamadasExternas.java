package com.omnitribo.integracoes.infra;

import com.omnitribo.integracoes.api.ServicoExternoIndisponivelException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Circuit breaker: para de chamar um provedor que está comprovadamente fora do ar.
 *
 * <p><b>O que ele acrescenta ao bulkhead que já existe.</b> {@link LimiteDeChamadasExternas} limita
 * QUANTAS threads esperam por um provedor ao mesmo tempo; ele protege a aplicação e diz, no próprio
 * javadoc, que não é circuit breaker — durante uma queda o provedor continua sendo consultado, só
 * que por no máximo N threads. Este aqui fecha a outra metade: depois de N falhas seguidas, ninguém
 * mais é consultado, e a resposta sai sem tocar a rede. Protege o provedor, e devolve ao usuário um
 * 503 imediato em vez de 2 s de espera para o mesmo 503.
 *
 * <p><b>Falhas CONSECUTIVAS, não janela deslizante.</b> Uma janela por tempo degenera exatamente no
 * cenário que mais importa aqui: três falhas espalhadas por dez minutos de madrugada, sem nenhum
 * sucesso no meio, não abririam o circuito. E uma janela por razão ("50% das últimas 20") exige um
 * segundo parâmetro — volume mínimo — sem o qual a primeira falha do dia abre o disjuntor com
 * amostra de 1. Os dois modos de falha reais destes provedores (fora do ar, e estourando o timeout)
 * são SUSTENTADOS, e um contador de inteiro é o detector exato disso. O preço, aceito
 * conscientemente: um provedor oscilante (falha, ok, falha, ok) nunca abre — e está certo que não
 * abra, porque metade das requisições está sendo atendida.
 *
 * <p><b>{@link AtomicReference} sobre record imutável, e não {@code synchronized}.</b> O caminho
 * quente é FECHADO + sucesso, que aqui é uma leitura volátil e um CAS que só dispara se havia falha
 * anterior. Um monitor no método serializaria TODAS as chamadas ao provedor numa thread só,
 * destruindo o bulkhead de 8 permissões logo abaixo. Não há risco de ABA: todo estado é uma
 * instância nova de record, comparada por identidade de referência.
 *
 * <p><b>UMA sonda em meia-abertura.</b> É a menor quantidade de informação que responde à única
 * pergunta ("voltou?"), custa uma requisição a um provedor que acreditamos morto e produz resultado
 * inequívoco. Com N sondas seria preciso decidir quórum — parâmetro novo, sem ganho. O campo {@code
 * sondaEmVoo} existe porque, sem ele, mil threads chegando em meia-abertura viram mil sondas e a
 * "meia-abertura" vira abertura total.
 *
 * <p><b>O desfecho NEUTRO não é um detalhe.</b> Se a sonda for recusada pelo bulkhead — ou falhar
 * com um 4xx, que não conta —, o token {@code sondaEmVoo} precisa VOLTAR. Sem isso o disjuntor fica
 * preso em meia-abertura para sempre: nunca fecha, nunca reabre, e o sintoma é o pior possível — o
 * provedor volta e o recurso continua sumido da tela, sem uma linha de erro no log.
 *
 * <p>A recusa reusa {@link ServicoExternoIndisponivelException} de propósito. O catálogo de tipos
 * de problema tem uma URI por REAÇÃO DE UI (ADR 0010), e "circuito aberto" e "provedor fora do ar"
 * pedem a mesma reação: esconder o recurso. Um tipo novo obrigaria o app a aprender uma distinção
 * que não muda nada do lado dele.
 */
final class DisjuntorDeChamadasExternas {

  private static final Logger log = LoggerFactory.getLogger(DisjuntorDeChamadasExternas.class);

  enum Situacao {
    FECHADO,
    ABERTO,
    MEIO_ABERTO
  }

  private record Estado(
      Situacao situacao, int falhasSeguidas, Instant abertoDesde, boolean sondaEmVoo) {

    static final Estado INICIAL = new Estado(Situacao.FECHADO, 0, null, false);
  }

  private final String provedor;
  private final int falhasParaAbrir;
  private final Duration esperaAberto;
  private final Clock relogio;
  private final AtomicReference<Estado> estado = new AtomicReference<>(Estado.INICIAL);

  DisjuntorDeChamadasExternas(
      String provedor, int falhasParaAbrir, Duration esperaAberto, Clock relogio) {
    this.provedor = provedor;
    this.falhasParaAbrir = falhasParaAbrir;
    this.esperaAberto = esperaAberto;
    this.relogio = relogio;
  }

  <T> T executar(Supplier<T> chamada) {
    admitir();
    try {
      T resultado = chamada.get();
      registrarSucesso();
      return resultado;
    } catch (RuntimeException e) {
      if (ClassificacaoDeFalhaExterna.indicaProvedorDoente(e)) {
        registrarFalha();
      } else {
        // 4xx, erro de desserialização, recusa do bulkhead: não é sintoma do provedor. Só devolve a
        // sonda, se havia uma em voo, e deixa o contador onde estava.
        registrarNeutro();
      }
      throw e;
    }
  }

  /** Situação atual — existe para o teste afirmar sobre o estado, não para o código de produção. */
  Situacao situacao() {
    return estado.get().situacao();
  }

  /**
   * Deixa passar, ou recusa sem tocar a rede.
   *
   * <p>A transição ABERTO → MEIO_ABERTO acontece AQUI, preguiçosamente, comparando o relógio
   * injetado. Não há job agendado: além de ser infraestrutura a mais, um {@code @Scheduled}
   * tornaria impossível o teste que avança o tempo em vez de dormir 30 s.
   */
  private void admitir() {
    while (true) {
      Estado antes = estado.get();
      switch (antes.situacao()) {
        case FECHADO -> {
          return;
        }
        case ABERTO -> {
          if (!esperaCumprida(antes)) {
            throw recusa();
          }
          Estado depois = new Estado(Situacao.MEIO_ABERTO, antes.falhasSeguidas(), null, true);
          if (estado.compareAndSet(antes, depois)) {
            log.info("Disjuntor de {} em meia-abertura; enviando uma sonda", provedor);
            return;
          }
        }
        case MEIO_ABERTO -> {
          if (antes.sondaEmVoo()) {
            throw recusa();
          }
          Estado depois = new Estado(Situacao.MEIO_ABERTO, antes.falhasSeguidas(), null, true);
          if (estado.compareAndSet(antes, depois)) {
            return;
          }
        }
      }
    }
  }

  private boolean esperaCumprida(Estado aberto) {
    return aberto.abertoDesde() == null
        || !relogio.instant().isBefore(aberto.abertoDesde().plus(esperaAberto));
  }

  private void registrarSucesso() {
    while (true) {
      Estado antes = estado.get();
      if (antes.situacao() == Situacao.FECHADO && antes.falhasSeguidas() == 0) {
        return; // Caminho quente: nada a escrever.
      }
      boolean fechando = antes.situacao() == Situacao.MEIO_ABERTO;
      if (estado.compareAndSet(antes, Estado.INICIAL)) {
        if (fechando) {
          log.info("Disjuntor de {} FECHADO: a sonda passou", provedor);
        }
        return;
      }
    }
  }

  private void registrarFalha() {
    while (true) {
      Estado antes = estado.get();
      Estado depois;
      if (antes.situacao() == Situacao.MEIO_ABERTO) {
        // A sonda falhou: reabre e recomeça a espera do zero. Sem escalada exponencial — previsível
        // vale mais que ótimo para uma espera que já é de dezenas de segundos.
        depois = new Estado(Situacao.ABERTO, antes.falhasSeguidas(), relogio.instant(), false);
      } else {
        int falhas = antes.falhasSeguidas() + 1;
        depois =
            falhas >= falhasParaAbrir
                ? new Estado(Situacao.ABERTO, falhas, relogio.instant(), false)
                : new Estado(Situacao.FECHADO, falhas, null, false);
      }
      if (estado.compareAndSet(antes, depois)) {
        if (depois.situacao() == Situacao.ABERTO && antes.situacao() != Situacao.ABERTO) {
          log.warn(
              "Disjuntor de {} ABERTO após {} falhas seguidas; parando de chamar por {}",
              provedor,
              depois.falhasSeguidas(),
              esperaAberto);
        }
        return;
      }
    }
  }

  /** Devolve a sonda sem julgar o provedor. Ver o parágrafo sobre o desfecho NEUTRO no javadoc. */
  private void registrarNeutro() {
    while (true) {
      Estado antes = estado.get();
      if (antes.situacao() != Situacao.MEIO_ABERTO || !antes.sondaEmVoo()) {
        return;
      }
      Estado depois = new Estado(Situacao.MEIO_ABERTO, antes.falhasSeguidas(), null, false);
      if (estado.compareAndSet(antes, depois)) {
        return;
      }
    }
  }

  private ServicoExternoIndisponivelException recusa() {
    // debug, e não warn: em queda prolongada esta linha sairia a cada requisição. O warn que
    // importa
    // é o da ABERTURA, que sai uma vez por queda e é o que se procura no log.
    log.debug("Disjuntor de {} aberto; recusando sem chamar", provedor);
    return new ServicoExternoIndisponivelException(
        "O serviço externo está indisponível. Tente novamente em instantes.");
  }
}
