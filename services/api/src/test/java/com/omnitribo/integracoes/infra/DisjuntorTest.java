package com.omnitribo.integracoes.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omnitribo.integracoes.api.ServicoExternoIndisponivelException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * A máquina de estados do disjuntor, sem rede e sem Spring.
 *
 * <p><b>O relógio é injetado e ajustável, e é isso que torna o caso da recuperação honesto.</b> A
 * alternativa seria baixar a espera para milissegundos e dormir — o que passaria a medir o
 * escalonador do sistema operacional em vez da regra, e produziria o tipo de teste que falha uma
 * vez em cinquenta no CI. Aqui o tempo é um valor, então "passaram 31 segundos" é uma afirmação
 * exata.
 */
class DisjuntorTest {

  private static final Duration ESPERA = Duration.ofSeconds(30);
  private static final int LIMIAR = 5;

  private final RelogioAjustavel relogio = new RelogioAjustavel();
  private final DisjuntorDeChamadasExternas disjuntor =
      new DisjuntorDeChamadasExternas("ProvedorTeste", LIMIAR, ESPERA, relogio);

  private final AtomicInteger chamadas = new AtomicInteger();

  /** Supplier que conta invocações e falha com a exceção pedida. */
  private <T> T chamarFalhando(RuntimeException falha) {
    return disjuntor.executar(
        () -> {
          chamadas.incrementAndGet();
          throw falha;
        });
  }

  private String chamarComSucesso() {
    return disjuntor.executar(
        () -> {
          chamadas.incrementAndGet();
          return "ok";
        });
  }

  private static HttpServerErrorException erro500() {
    return HttpServerErrorException.create(
        HttpStatus.INTERNAL_SERVER_ERROR, "erro", null, null, null);
  }

  @Test
  void falhas_abaixo_do_limiar_mantem_o_circuito_fechado() {
    for (int i = 0; i < LIMIAR - 1; i++) {
      assertThatThrownBy(() -> chamarFalhando(erro500()))
          .isInstanceOf(HttpServerErrorException.class);
    }

    assertThat(disjuntor.situacao()).isEqualTo(DisjuntorDeChamadasExternas.Situacao.FECHADO);
    // A prova de que ainda está fechado não é o enum: é o provedor continuar sendo chamado.
    assertThat(chamadas.get()).isEqualTo(LIMIAR - 1);
    assertThatThrownBy(() -> chamarFalhando(erro500()))
        .isInstanceOf(HttpServerErrorException.class);
    assertThat(chamadas.get()).isEqualTo(LIMIAR);
  }

  @Test
  void sucesso_zera_o_contador_de_falhas_consecutivas() {
    chamarFalhando2x();
    chamarComSucesso();
    chamarFalhando2x();
    chamarFalhando2x();

    // Seis falhas no total, mas nunca cinco SEGUIDAS: é a diferença entre janela acumulada e
    // consecutiva, e é a única razão de este caso existir.
    assertThat(disjuntor.situacao()).isEqualTo(DisjuntorDeChamadasExternas.Situacao.FECHADO);
  }

  private void chamarFalhando2x() {
    for (int i = 0; i < 2; i++) {
      assertThatThrownBy(() -> chamarFalhando(erro500()))
          .isInstanceOf(HttpServerErrorException.class);
    }
  }

  @Test
  void abre_no_limiar_e_para_de_chamar_o_provedor() {
    abrir();

    assertThat(disjuntor.situacao()).isEqualTo(DisjuntorDeChamadasExternas.Situacao.ABERTO);
    int aposAbrir = chamadas.get();

    assertThatThrownBy(this::chamarComSucesso)
        .isInstanceOf(ServicoExternoIndisponivelException.class);

    // A asserção que importa: a contagem NÃO se moveu. O supplier nem foi invocado.
    assertThat(chamadas.get()).isEqualTo(aposAbrir);
  }

  @Test
  void volta_a_fechar_depois_da_espera() {
    abrir();
    int aposAbrir = chamadas.get();

    // Antes da espera: continua recusando sem tocar o provedor.
    relogio.avancar(ESPERA.minusSeconds(1));
    assertThatThrownBy(this::chamarComSucesso)
        .isInstanceOf(ServicoExternoIndisponivelException.class);
    assertThat(chamadas.get()).isEqualTo(aposAbrir);

    // Cumprida a espera, UMA sonda passa — e o sucesso dela fecha o circuito.
    relogio.avancar(Duration.ofSeconds(2));
    assertThat(chamarComSucesso()).isEqualTo("ok");
    assertThat(chamadas.get()).isEqualTo(aposAbrir + 1);
    assertThat(disjuntor.situacao()).isEqualTo(DisjuntorDeChamadasExternas.Situacao.FECHADO);

    // E o circuito fechado volta a deixar tudo passar.
    assertThat(chamarComSucesso()).isEqualTo("ok");
    assertThat(chamadas.get()).isEqualTo(aposAbrir + 2);
  }

  @Test
  void sonda_que_falha_reabre_e_reinicia_a_espera() {
    abrir();
    relogio.avancar(ESPERA.plusSeconds(1));

    assertThatThrownBy(() -> chamarFalhando(erro500()))
        .isInstanceOf(HttpServerErrorException.class);
    assertThat(disjuntor.situacao()).isEqualTo(DisjuntorDeChamadasExternas.Situacao.ABERTO);

    // A espera recomeçou do zero: quase toda ela decorrida ainda não basta.
    int aposSonda = chamadas.get();
    relogio.avancar(ESPERA.minusSeconds(1));
    assertThatThrownBy(this::chamarComSucesso)
        .isInstanceOf(ServicoExternoIndisponivelException.class);
    assertThat(chamadas.get()).isEqualTo(aposSonda);
  }

  @Test
  void segunda_chamada_durante_a_sonda_e_recusada() {
    abrir();
    relogio.avancar(ESPERA.plusSeconds(1));

    // A sonda entra e, de dentro dela, uma segunda chamada tenta passar. É o análogo determinístico
    // de duas threads chegando juntas em meia-abertura: sem o token de sonda única, as duas iriam
    // ao
    // provedor e a "meia-abertura" seria abertura total.
    String resultado =
        disjuntor.executar(
            () -> {
              chamadas.incrementAndGet();
              assertThatThrownBy(this::chamarComSucesso)
                  .isInstanceOf(ServicoExternoIndisponivelException.class);
              return "ok";
            });

    assertThat(resultado).isEqualTo("ok");
    assertThat(disjuntor.situacao()).isEqualTo(DisjuntorDeChamadasExternas.Situacao.FECHADO);
  }

  @Test
  void sonda_recusada_pelo_bulkhead_devolve_o_token() {
    abrir();
    relogio.avancar(ESPERA.plusSeconds(1));

    // A sonda não chega ao provedor: o bulkhead a recusa. Se o token não voltasse, o disjuntor
    // ficaria preso em meia-abertura PARA SEMPRE — nunca fecharia, nunca reabriria, e o provedor
    // poderia voltar sem que o recurso reaparecesse na tela. Sem log de erro nenhum.
    assertThatThrownBy(
            () ->
                disjuntor.executar(
                    () -> {
                      throw new ServicoExternoIndisponivelException("congestionado");
                    }))
        .isInstanceOf(ServicoExternoIndisponivelException.class);

    assertThat(disjuntor.situacao()).isEqualTo(DisjuntorDeChamadasExternas.Situacao.MEIO_ABERTO);

    // Prova de que o token voltou: uma nova sonda é admitida e chega ao provedor.
    int antes = chamadas.get();
    assertThat(chamarComSucesso()).isEqualTo("ok");
    assertThat(chamadas.get()).isEqualTo(antes + 1);
    assertThat(disjuntor.situacao()).isEqualTo(DisjuntorDeChamadasExternas.Situacao.FECHADO);
  }

  @Test
  void erro_4xx_nunca_abre_o_circuito() {
    HttpClientErrorException naoEncontrado =
        HttpClientErrorException.create(HttpStatus.NOT_FOUND, "nao encontrado", null, null, null);

    for (int i = 0; i < LIMIAR * 4; i++) {
      assertThatThrownBy(() -> chamarFalhando(naoEncontrado))
          .isInstanceOf(HttpClientErrorException.class);
    }

    // 4xx é problema NOSSO — URL errada, parâmetro inválido. Contá-lo transformaria um defeito
    // nosso
    // em "provedor fora do ar" e o esconderia atrás de um 503.
    assertThat(disjuntor.situacao()).isEqualTo(DisjuntorDeChamadasExternas.Situacao.FECHADO);
    assertThat(chamadas.get()).isEqualTo(LIMIAR * 4);
  }

  @Test
  void erro_429_abre_ainda_que_seja_4xx() {
    HttpClientErrorException limitado =
        HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "calma", null, null, null);

    for (int i = 0; i < LIMIAR; i++) {
      assertThatThrownBy(() -> chamarFalhando(limitado))
          .isInstanceOf(HttpClientErrorException.class);
    }

    // O provedor está dizendo, no protocolo dele, que não quer mais tráfego nosso. Parar de chamar
    // é
    // a reação certa — ainda que repetir a mesma requisição seja a errada (ver a política de
    // retry).
    assertThat(disjuntor.situacao()).isEqualTo(DisjuntorDeChamadasExternas.Situacao.ABERTO);
  }

  @Test
  void timeout_conta_como_falha_do_provedor() {
    for (int i = 0; i < LIMIAR; i++) {
      assertThatThrownBy(() -> chamarFalhando(new ResourceAccessException("tempo esgotado")))
          .isInstanceOf(ResourceAccessException.class);
    }

    assertThat(disjuntor.situacao()).isEqualTo(DisjuntorDeChamadasExternas.Situacao.ABERTO);
  }

  private void abrir() {
    for (int i = 0; i < LIMIAR; i++) {
      assertThatThrownBy(() -> chamarFalhando(erro500()))
          .isInstanceOf(HttpServerErrorException.class);
    }
  }

  /** Relógio que anda quando o teste manda, e só então. */
  private static final class RelogioAjustavel extends Clock {

    private Instant agora = Instant.parse("2026-08-15T12:00:00Z");

    void avancar(Duration quanto) {
      agora = agora.plus(quanto);
    }

    @Override
    public Instant instant() {
      return agora;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zona) {
      return this;
    }
  }
}
