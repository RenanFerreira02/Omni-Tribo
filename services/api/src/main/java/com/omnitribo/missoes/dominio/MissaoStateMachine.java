package com.omnitribo.missoes.dominio;

import com.omnitribo.compartilhado.dominio.AcessoNegadoException;
import com.omnitribo.compartilhado.dominio.TransicaoInvalidaException;
import java.time.Instant;
import java.util.UUID;

/**
 * Único ponto do sistema onde o status de uma Missao muda.
 *
 * <p>Classe utilitária sem estado e sem Spring: o teste que percorre as 99 combinações status ×
 * evento roda sem contexto de aplicação e sem Testcontainers, em milissegundos.
 *
 * <p>O job de expiração usa exatamente esta máquina (com ator SISTEMA), de modo que não existe
 * caminho paralelo capaz de divergir das regras de transição.
 */
public final class MissaoStateMachine {

  private static final String MSG_TRANSICAO =
      "Esta operação não é permitida no estado atual da missão.";
  private static final String MSG_ACESSO =
      "Você não tem permissão para esta operação nesta missão.";

  private MissaoStateMachine() {}

  /**
   * Valida e aplica a transição, devolvendo o evento de trilha AINDA NÃO PERSISTIDO. Quem grava é o
   * MissaoService, na mesma transação em que salva a missão — status e trilha nunca divergem.
   */
  public static MissaoEvento transicionar(
      Missao missao, EventoMissao evento, AtorMissao ator, String payloadJson, Instant agora) {

    validar(missao, evento, ator);

    StatusMissao origem = missao.getStatus();
    StatusMissao destino = origem.destinoDe(evento).orElseThrow(); // já validado acima

    aplicarEfeitos(missao, evento, ator, agora);
    missao.setStatus(destino);

    return new MissaoEvento(
        UUID.randomUUID(),
        missao.getId(),
        evento.tipoTrilha(),
        ator.usuarioId(),
        origem,
        destino,
        payloadJson,
        agora);
  }

  /**
   * Só valida, não muta. Usada pelo check-in (F6) e pelos endpoints stub de F7, para que o contrato
   * de erro seja o definitivo: 403 e 409 corretos antes de qualquer efeito.
   */
  public static void validar(Missao missao, EventoMissao evento, AtorMissao ator) {
    // Autorização (403) ANTES da transição (409). Na ordem inversa, um estranho descobriria o
    // status de uma missão alheia pela diferença entre as duas respostas.
    validarAutorizacao(missao, evento, ator);

    if (!missao.getStatus().permite(evento)) {
      throw new TransicaoInvalidaException(MSG_TRANSICAO);
    }
  }

  /** PATCH não é transição: exige ser o criador e a missão estar em RASCUNHO ou ABERTA. */
  public static void validarEdicao(Missao missao, AtorMissao ator) {
    if (!ator.ehMesmo(missao.getCriadorId())) {
      throw new AcessoNegadoException(MSG_ACESSO);
    }
    StatusMissao status = missao.getStatus();
    if (status != StatusMissao.RASCUNHO && status != StatusMissao.ABERTA) {
      throw new TransicaoInvalidaException(
          "Missão só pode ser editada enquanto está em rascunho ou aberta.");
    }
  }

  /**
   * Só a metade de autorização do {@link #validar}, sem a checagem de transição.
   *
   * <p>Pública para o check-in idempotente: entre o 403 e o 409 é preciso sondar a chave de
   * idempotência, porque um replay legítimo chega com a missão já em AGUARDANDO_CONFIRMACAO e
   * levaria 409 se a transição fosse checada antes. A autorização continua estritamente primeiro —
   * sondar antes dela permitiria devolver dados da missão a quem não é mais o executor.
   */
  public static void validarAutorizacao(Missao missao, EventoMissao evento, AtorMissao ator) {
    boolean autorizado =
        switch (evento.atorEsperado()) {
          case CRIADOR -> ator.ehMesmo(missao.getCriadorId());
          // executorId nulo nega por construção: ehMesmo(null) é sempre falso.
          case EXECUTOR -> ator.ehMesmo(missao.getExecutorId());
          // Aceitar a própria missão seria autonegócio: o criador confirmaria a si mesmo e
          // liberaria o crédito sem contraparte.
          case CANDIDATO -> !ator.ehSistema() && !ator.ehMesmo(missao.getCriadorId());
          case ADMIN -> ator.ehAdmin();
          case SISTEMA -> ator.ehSistema();
        };

    if (!autorizado) {
      throw new AcessoNegadoException(MSG_ACESSO);
    }
  }

  private static void aplicarEfeitos(
      Missao missao, EventoMissao evento, AtorMissao ator, Instant agora) {
    switch (evento) {
      case ACEITAR -> {
        missao.setExecutorId(ator.usuarioId());
        missao.setAceitaEm(agora);
      }
      case DESISTIR -> {
        // Quem desistiu continua registrado em missao_evento.ator_id — a trilha é a memória,
        // a missão volta a ABERTA sem executor para ser aceita por outra pessoa.
        missao.setExecutorId(null);
        missao.setAceitaEm(null);
      }
      case CONFIRMAR, RESOLVER_CONCLUIR -> missao.setConcluidaEm(agora);
      default -> {
        // Demais eventos não têm efeito além da mudança de status.
      }
    }
  }
}
