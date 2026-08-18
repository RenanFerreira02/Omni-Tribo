package com.omnitribo.missoes.dominio;

/**
 * Eventos que disparam transições na máquina de estados de Missao.
 *
 * <p>Cada evento carrega o ator autorizado a dispará-lo e o tipo gravado na trilha append-only.
 * Este enum nunca referencia {@link StatusMissao} — o destino de cada transição mora na tabela
 * dentro de StatusMissao — o que evita ciclo de inicialização estática entre os dois enums.
 */
public enum EventoMissao {
  PUBLICAR(AtorEsperado.CRIADOR, TipoMissaoEvento.PUBLICADA),
  ACEITAR(AtorEsperado.CANDIDATO, TipoMissaoEvento.ACEITA),
  INICIAR(AtorEsperado.EXECUTOR, TipoMissaoEvento.INICIADA),
  DESISTIR(AtorEsperado.EXECUTOR, TipoMissaoEvento.DESISTIDA),
  CANCELAR(AtorEsperado.CRIADOR, TipoMissaoEvento.CANCELADA),
  CHECKIN(AtorEsperado.EXECUTOR, TipoMissaoEvento.CHECK_IN_REGISTRADO),
  CONFIRMAR(AtorEsperado.CRIADOR, TipoMissaoEvento.CONFIRMADA),
  CONTESTAR(AtorEsperado.CRIADOR, TipoMissaoEvento.CONTESTADA),
  RESOLVER_CONCLUIR(AtorEsperado.ADMIN, TipoMissaoEvento.DISPUTA_RESOLVIDA),
  RESOLVER_CANCELAR(AtorEsperado.ADMIN, TipoMissaoEvento.DISPUTA_RESOLVIDA),

  /** Janela de oferta venceu sem ninguém aceitar: ABERTA → EXPIRADA, com estorno do pote. */
  EXPIRAR(AtorEsperado.SISTEMA, TipoMissaoEvento.EXPIRADA),

  /**
   * Executor abandonou depois de iniciar: EM_ANDAMENTO → EXPIRADA, com estorno.
   *
   * <p>Estorna e não paga porque NÃO HOUVE CHECK-IN — não existe nenhuma evidência de que o
   * trabalho foi feito. É a diferença que separa este evento de {@link #EXPIRAR_CONFIRMACAO}.
   */
  EXPIRAR_EXECUCAO(AtorEsperado.SISTEMA, TipoMissaoEvento.EXECUCAO_EXPIRADA),

  /**
   * Criador sumiu depois do check-in: AGUARDANDO_CONFIRMACAO → CONCLUIDA, <b>pagando o
   * executor</b>.
   *
   * <p>A decisão de pagar é do produto, e a alternativa foi descartada com motivo: expirar
   * estornando puniria quem executou por uma omissão do criador, e o check-in geolocalizado
   * validado no servidor já é a evidência de presença que o sistema aceita como prova em qualquer
   * outro caminho. Uma plataforma de cuidado que deixa o executor sem receber quando o outro lado
   * some destrói exatamente a confiança que é a tese do produto.
   *
   * <p>O risco reconhecido é conluio ou check-in sem execução — que a documentação de antifraude já
   * registra como não detectável, e que um criador distraído confirmaria do mesmo jeito. A porta de
   * escape é {@link #DESTRAVAR}, do ADMIN.
   *
   * <p>Passa por CONCLUIDA, então a regra "só CONCLUIDA credita" fica intacta e o pagamento reusa o
   * caminho de crédito que já existe.
   */
  EXPIRAR_CONFIRMACAO(AtorEsperado.SISTEMA, TipoMissaoEvento.CONFIRMACAO_EXPIRADA),

  /**
   * Saída manual de ADMIN para missão parada: cancela e estorna o pote.
   *
   * <p>Existe porque a varredura automática resolve o caso comum, não o excepcional — missão
   * legítima em disputa silenciosa, ou parada por um motivo que a regra de prazo não previu. Sem
   * uma porta humana, o único desfecho possível seria esperar o prazo e aceitar o que ele decidir.
   */
  DESTRAVAR(AtorEsperado.ADMIN, TipoMissaoEvento.DESTRAVADA_POR_ADMIN);

  private final AtorEsperado atorEsperado;
  private final TipoMissaoEvento tipoTrilha;

  EventoMissao(AtorEsperado atorEsperado, TipoMissaoEvento tipoTrilha) {
    this.atorEsperado = atorEsperado;
    this.tipoTrilha = tipoTrilha;
  }

  public AtorEsperado atorEsperado() {
    return atorEsperado;
  }

  public TipoMissaoEvento tipoTrilha() {
    return tipoTrilha;
  }
}
