package com.omnitribo.missoes.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omnitribo.compartilhado.dominio.AcessoNegadoException;
import com.omnitribo.compartilhado.dominio.TransicaoInvalidaException;
import com.omnitribo.missoes.MissaoFixture;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

/**
 * Matriz completa da máquina de estados: 9 status × 11 eventos = 99 combinações.
 *
 * <p>Roda sem Spring e sem Testcontainers de propósito — MissaoStateMachine é utilitária e sem
 * estado, então a matriz inteira custa milissegundos e o erro aparece no ponto exato da regra, não
 * atrás de um roundtrip HTTP.
 */
class MissaoStateMachineTest {

  /**
   * Tabela ESPERADA escrita à mão, deliberadamente independente da tabela declarada em
   * StatusMissao. Derivá-la do próprio enum tornaria o teste tautológico: ele continuaria verde se
   * alguém apagasse uma transição.
   */
  private static final Map<String, StatusMissao> ESPERADAS = new LinkedHashMap<>();

  static {
    ESPERADAS.put(chave(StatusMissao.RASCUNHO, EventoMissao.PUBLICAR), StatusMissao.ABERTA);
    // Saída de rascunho financiado: sem ela, o pote de uma missão comunitária abandonada antes da
    // publicação ficaria preso, porque o estorno só roda em CANCELADA e EXPIRADA.
    ESPERADAS.put(chave(StatusMissao.RASCUNHO, EventoMissao.CANCELAR), StatusMissao.CANCELADA);
    ESPERADAS.put(chave(StatusMissao.ABERTA, EventoMissao.ACEITAR), StatusMissao.ACEITA);
    ESPERADAS.put(chave(StatusMissao.ABERTA, EventoMissao.CANCELAR), StatusMissao.CANCELADA);
    ESPERADAS.put(chave(StatusMissao.ABERTA, EventoMissao.EXPIRAR), StatusMissao.EXPIRADA);
    ESPERADAS.put(chave(StatusMissao.ACEITA, EventoMissao.INICIAR), StatusMissao.EM_ANDAMENTO);
    ESPERADAS.put(chave(StatusMissao.ACEITA, EventoMissao.DESISTIR), StatusMissao.ABERTA);
    ESPERADAS.put(chave(StatusMissao.ACEITA, EventoMissao.CANCELAR), StatusMissao.CANCELADA);
    ESPERADAS.put(
        chave(StatusMissao.EM_ANDAMENTO, EventoMissao.CHECKIN),
        StatusMissao.AGUARDANDO_CONFIRMACAO);
    // Saídas do beco sem saída de EM_ANDAMENTO: antes CHECKIN era a ÚNICA, e um executor que
    // abandonava prendia o pote da missão em custódia para sempre — sem que nem um ADMIN pudesse
    // intervir. EXPIRADA e não CONCLUIDA porque não houve check-in: não há evidência de trabalho.
    ESPERADAS.put(
        chave(StatusMissao.EM_ANDAMENTO, EventoMissao.EXPIRAR_EXECUCAO), StatusMissao.EXPIRADA);
    ESPERADAS.put(chave(StatusMissao.EM_ANDAMENTO, EventoMissao.DESTRAVAR), StatusMissao.CANCELADA);
    ESPERADAS.put(
        chave(StatusMissao.AGUARDANDO_CONFIRMACAO, EventoMissao.CONFIRMAR), StatusMissao.CONCLUIDA);
    ESPERADAS.put(
        chave(StatusMissao.AGUARDANDO_CONFIRMACAO, EventoMissao.CONTESTAR),
        StatusMissao.EM_DISPUTA);
    // Mesmo beco do outro lado: as duas saídas acima são do CRIADOR, então um criador que sumia
    // deixava a missão parada indefinidamente, e nem o ADMIN entrava (EM_DISPUTA só se alcança por
    // CONTESTAR, que também é dele).
    //
    // CONCLUIDA, PAGANDO o executor — houve check-in, e o check-in geolocalizado validado no
    // servidor é a evidência que o sistema aceita como prova em todo outro caminho. Ver o javadoc
    // de EventoMissao.EXPIRAR_CONFIRMACAO para a alternativa descartada.
    ESPERADAS.put(
        chave(StatusMissao.AGUARDANDO_CONFIRMACAO, EventoMissao.EXPIRAR_CONFIRMACAO),
        StatusMissao.CONCLUIDA);
    ESPERADAS.put(
        chave(StatusMissao.AGUARDANDO_CONFIRMACAO, EventoMissao.DESTRAVAR), StatusMissao.CANCELADA);
    ESPERADAS.put(
        chave(StatusMissao.EM_DISPUTA, EventoMissao.RESOLVER_CONCLUIR), StatusMissao.CONCLUIDA);
    ESPERADAS.put(
        chave(StatusMissao.EM_DISPUTA, EventoMissao.RESOLVER_CANCELAR), StatusMissao.CANCELADA);
  }

  private static String chave(StatusMissao origem, EventoMissao evento) {
    return origem.name() + "|" + evento.name();
  }

  static Stream<Arguments> todasAsCombinacoes() {
    return Stream.of(StatusMissao.values())
        .flatMap(
            origem -> Stream.of(EventoMissao.values()).map(evento -> Arguments.of(origem, evento)));
  }

  @ParameterizedTest(name = "{0} --{1}--> ?")
  @MethodSource("todasAsCombinacoes")
  void matrizCompletaDeTransicoes(StatusMissao origem, EventoMissao evento) {
    Missao missao = MissaoFixture.no(origem);
    AtorMissao ator = MissaoFixture.atorCorretoPara(evento, missao);
    StatusMissao destinoEsperado = ESPERADAS.get(chave(origem, evento));

    if (destinoEsperado != null) {
      MissaoEvento trilha =
          MissaoStateMachine.transicionar(missao, evento, ator, null, MissaoFixture.AGORA);

      assertThat(missao.getStatus()).isEqualTo(destinoEsperado);
      assertThat(trilha.getMissaoId()).isEqualTo(missao.getId());
      assertThat(trilha.getTipo()).isEqualTo(evento.tipoTrilha());
      assertThat(trilha.getDeStatus()).isEqualTo(origem.name());
      assertThat(trilha.getParaStatus()).isEqualTo(destinoEsperado.name());
      assertThat(trilha.getCriadoEm()).isEqualTo(MissaoFixture.AGORA);
    } else {
      assertThatThrownBy(
              () ->
                  MissaoStateMachine.transicionar(missao, evento, ator, null, MissaoFixture.AGORA))
          .isInstanceOf(TransicaoInvalidaException.class)
          .extracting(e -> ((TransicaoInvalidaException) e).getHttpStatus())
          .isEqualTo(HttpStatus.CONFLICT);

      assertThat(missao.getStatus())
          .as("transição recusada não pode deixar mutação parcial")
          .isEqualTo(origem);
    }
  }

  /** Exigido nominalmente: não existe atalho de ABERTA para CONCLUIDA por nenhum evento. */
  @Test
  void abertaNaoPodeSaltarParaConcluida() {
    assertThat(StatusMissao.ABERTA.destinoDe(EventoMissao.CONFIRMAR)).isEmpty();
    assertThat(StatusMissao.ABERTA.destinoDe(EventoMissao.RESOLVER_CONCLUIR)).isEmpty();

    Missao missao = MissaoFixture.no(StatusMissao.ABERTA);
    assertThatThrownBy(
            () ->
                MissaoStateMachine.transicionar(
                    missao,
                    EventoMissao.CONFIRMAR,
                    AtorMissao.usuario(missao.getCriadorId()),
                    null,
                    MissaoFixture.AGORA))
        .isInstanceOf(TransicaoInvalidaException.class);

    assertThat(missao.getStatus()).isEqualTo(StatusMissao.ABERTA);
    assertThat(missao.getConcluidaEm()).isNull();
  }

  /**
   * Ator errado sempre 403 — e 403 ANTES de 409, mesmo quando a transição também seria inválida. Na
   * ordem inversa, a diferença entre as respostas revelaria o status de uma missão alheia.
   */
  @ParameterizedTest
  @EnumSource(EventoMissao.class)
  void atorErradoRecebeAcessoNegado(EventoMissao evento) {
    // CANCELADA é terminal: nenhum evento é permitido a partir dela, então o 403 só pode vir
    // da checagem de autorização ter rodado primeiro.
    Missao missao = MissaoFixture.no(StatusMissao.CANCELADA);
    AtorMissao ator = MissaoFixture.atorErradoPara(evento, missao);

    assertThatThrownBy(
            () -> MissaoStateMachine.transicionar(missao, evento, ator, null, MissaoFixture.AGORA))
        .isInstanceOf(AcessoNegadoException.class)
        .extracting(e -> ((AcessoNegadoException) e).getHttpStatus())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void executorNuloNaoAutorizaEventosDeExecutor() {
    Missao missao = MissaoFixture.no(StatusMissao.ACEITA);
    missao.setExecutorId(null);

    assertThatThrownBy(
            () ->
                MissaoStateMachine.transicionar(
                    missao,
                    EventoMissao.INICIAR,
                    AtorMissao.usuario(MissaoFixture.ESTRANHO),
                    null,
                    MissaoFixture.AGORA))
        .isInstanceOf(AcessoNegadoException.class);
  }

  @Test
  void aceiteVinculaExecutorEDesistenciaDesvincula() {
    Missao missao = MissaoFixture.no(StatusMissao.ABERTA);
    missao.setExecutorId(null);
    AtorMissao candidato = AtorMissao.usuario(MissaoFixture.ESTRANHO);

    MissaoStateMachine.transicionar(
        missao, EventoMissao.ACEITAR, candidato, null, MissaoFixture.AGORA);
    assertThat(missao.getExecutorId()).isEqualTo(MissaoFixture.ESTRANHO);
    assertThat(missao.getAceitaEm()).isEqualTo(MissaoFixture.AGORA);

    MissaoStateMachine.transicionar(
        missao, EventoMissao.DESISTIR, candidato, null, MissaoFixture.AGORA);
    assertThat(missao.getStatus()).isEqualTo(StatusMissao.ABERTA);
    assertThat(missao.getExecutorId()).isNull();
    assertThat(missao.getAceitaEm()).isNull();
  }

  @Test
  void criadorNaoPodeAceitarAPropriaMissao() {
    Missao missao = MissaoFixture.no(StatusMissao.ABERTA);

    assertThatThrownBy(
            () ->
                MissaoStateMachine.transicionar(
                    missao,
                    EventoMissao.ACEITAR,
                    AtorMissao.usuario(missao.getCriadorId()),
                    null,
                    MissaoFixture.AGORA))
        .isInstanceOf(AcessoNegadoException.class);
  }

  @Test
  void conclusaoRegistraMomentoDaConclusao() {
    Missao missao = MissaoFixture.no(StatusMissao.AGUARDANDO_CONFIRMACAO);

    MissaoStateMachine.transicionar(
        missao,
        EventoMissao.CONFIRMAR,
        AtorMissao.usuario(missao.getCriadorId()),
        null,
        MissaoFixture.AGORA);

    assertThat(missao.getStatus()).isEqualTo(StatusMissao.CONCLUIDA);
    assertThat(missao.getConcluidaEm()).isEqualTo(MissaoFixture.AGORA);
  }

  @Test
  void terminaisNaoTemSaida() {
    assertThat(StatusMissao.CONCLUIDA.ehTerminal()).isTrue();
    assertThat(StatusMissao.CANCELADA.ehTerminal()).isTrue();
    assertThat(StatusMissao.EXPIRADA.ehTerminal()).isTrue();

    assertThat(StatusMissao.CONCLUIDA.eventosPermitidos()).isEmpty();
    assertThat(StatusMissao.ABERTA.ehTerminal()).isFalse();
  }

  @Test
  void edicaoSoPeloCriadorEApenasEmRascunhoOuAberta() {
    Missao rascunho = MissaoFixture.no(StatusMissao.RASCUNHO);
    MissaoStateMachine.validarEdicao(rascunho, AtorMissao.usuario(rascunho.getCriadorId()));

    Missao aberta = MissaoFixture.no(StatusMissao.ABERTA);
    MissaoStateMachine.validarEdicao(aberta, AtorMissao.usuario(aberta.getCriadorId()));

    assertThatThrownBy(
            () ->
                MissaoStateMachine.validarEdicao(
                    aberta, AtorMissao.usuario(MissaoFixture.ESTRANHO)))
        .isInstanceOf(AcessoNegadoException.class);

    Missao emAndamento = MissaoFixture.no(StatusMissao.EM_ANDAMENTO);
    assertThatThrownBy(
            () ->
                MissaoStateMachine.validarEdicao(
                    emAndamento, AtorMissao.usuario(emAndamento.getCriadorId())))
        .isInstanceOf(TransicaoInvalidaException.class);
  }

  @Test
  void validarNaoMutaAMissao() {
    // Contrato do stub de F7: valida 403/409 sem tocar no agregado antes de lançar 501.
    Missao missao = MissaoFixture.no(StatusMissao.AGUARDANDO_CONFIRMACAO);

    MissaoStateMachine.validar(
        missao, EventoMissao.CONFIRMAR, AtorMissao.usuario(missao.getCriadorId()));

    assertThat(missao.getStatus()).isEqualTo(StatusMissao.AGUARDANDO_CONFIRMACAO);
    assertThat(missao.getConcluidaEm()).isNull();
  }
}
