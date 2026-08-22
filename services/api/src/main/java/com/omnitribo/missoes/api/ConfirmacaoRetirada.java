package com.omnitribo.missoes.api;

import java.util.UUID;

/**
 * Porta pela qual {@code logistica} confirma que a encomenda chegou ao destinatário.
 *
 * <p>Existe porque o ArchUnit proíbe {@code logistica} de importar {@code missoes.dominio}, onde
 * vive o {@code MissaoService}. Mesmo molde de {@link ConversaoEntregaFalida}, e só tipos da JDK na
 * assinatura.
 *
 * <h2>Por que a transportadora, e não o executor</h2>
 *
 * <p>A missão de retirada tem o usuário-sistema como criador, e {@code AtorEsperado.CRIADOR}
 * compara IDENTIDADE, não papel — nenhum humano confirma, nem um ADMIN. A saída óbvia seria
 * autoconfirmar no check-in, e ela está errada: <b>o check-in prova PRESENÇA, não RECEBIMENTO</b>.
 * Confirmar ali faria o executor confirmar a si mesmo, e uma confirmação emitida pela parte
 * interessada não distingue entrega feita de entrega alegada.
 *
 * <p>A transportadora é a contraparte com interesse OPOSTO ao do executor: ela paga a recompensa (o
 * patrocinador dela financia o pote) e é ela que responde ao destinatário se o pacote não chegar. É
 * essa oposição que faz a confirmação significar alguma coisa — mesma razão pela qual {@code
 * AtorEsperado.CANDIDATO} proíbe o criador de aceitar a própria missão. Ver ADR 0026.
 *
 * <p><b>A varredura de prazo continua existindo</b>, e não vira redundância: ela é a rede de
 * segurança para quando a transportadora NÃO confirma. O desfecho dela é o mesmo — conclui pagando
 * o executor —, só que depois de {@code app.missoes.expiracao.prazo-confirmacao}.
 */
public interface ConfirmacaoRetirada {

  /**
   * Aplica CONFIRMAR na missão de retirada, com ator SISTEMA.
   *
   * <p><b>Não é preciso ajustar autorização nenhuma para isto funcionar.</b> {@code
   * AtorMissao.ehMesmo} compara {@code usuarioId}, e o criador desta missão É o usuário-sistema —
   * então {@code AtorMissao(UsuarioSistema.ID, SISTEMA)} satisfaz {@code CRIADOR} por construção. É
   * o mesmo ator que {@code abrirMissaoDeRetirada} usa para PUBLICAR. O conjunto de transições da
   * máquina de estados não muda.
   *
   * <p>Idempotente: {@code concluirComCredito} sonda a chave de replay ANTES de validar a
   * transição, então um segundo CONFIRMAR na mesma missão devolve o estado atual em vez de 409, sem
   * creditar de novo.
   *
   * @param missaoId a missão de retirada, resolvida pelo chamador a partir da entrega falida
   * @return a recompensa em tokens CONGELADA na missão — o que o executor recebeu. Não distingue
   *     crédito novo de replay, e não deve: quem sabe se já tinha concluído é o chamador, pelo
   *     carimbo de saída da custódia. Devolver "zero em replay" exigiria vazar para fora da
   *     fronteira um detalhe da sondagem de idempotência
   * @throws com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException missão inexistente
   */
  long confirmarRetirada(UUID missaoId);
}
