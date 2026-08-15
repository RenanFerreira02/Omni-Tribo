package com.omnitribo.identidade.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Porta para descobrir, em massa, quem pode ser notificado.
 *
 * <p>Existe porque o consentimento vive em {@code identidade.dominio} e quem precisa dele é {@code
 * notificacoes}, que o ArchUnit impede de alcançar o domínio alheio. Mesmo molde de {@link
 * ConsultaSessao} e {@link ConsultaAfiliacao}.
 *
 * <p><b>Os tipos entram como String, não como {@code TipoConsentimento}.</b> Não é desleixo: esta
 * interface é ORIGEM na regra direcional do ArchUnit, e expor o enum faria todo chamador importar
 * {@code identidade.dominio}. É a mesma razão pela qual {@code ConsultasGeoespaciais} recebe status
 * e categoria como String. Os valores vêm sempre de {@code .name()} de um enum já validado, nunca
 * de texto livre.
 */
public interface ConsultaConsentimento {

  /**
   * Nomes dos tipos, para quem está FORA de {@code identidade} montar a chamada.
   *
   * <p>Derivados de {@code TipoConsentimento.name()} e não escritos como literal: o compilador
   * passa a garantir que renomear a constante do enum quebre aqui, em vez de produzir
   * silenciosamente uma consulta que não casa com nada e não notifica ninguém. A importação do enum
   * é legal neste arquivo porque ele também mora em {@code identidade} — o que o ArchUnit proíbe é
   * o CHAMADOR de fora alcançar {@code identidade.dominio}, e ele só enxerga estas Strings.
   */
  String NOTIFICACAO = com.omnitribo.identidade.dominio.TipoConsentimento.NOTIFICACAO.name();

  String LOCALIZACAO = com.omnitribo.identidade.dominio.TipoConsentimento.LOCALIZACAO.name();

  /**
   * Usuários ATIVOS das tribos dadas que concederam TODOS os tipos listados, e não revogaram
   * depois.
   *
   * <p>Uma consulta só, e não N chamadas a {@code ConsentimentoService.listar}. Aquele método traz
   * o histórico inteiro de um usuário e resolve o estado atual em Java — correto para uma tela de
   * perfil, inviável para o fan-out de uma notificação de bairro, onde N é a população da tribo.
   *
   * <p>"Não revogaram depois" é o ponto delicado: {@code consentimento} é append-only, então o
   * estado atual é a linha MAIS RECENTE de cada tipo. Uma consulta ingênua por {@code concedido =
   * true} notificaria exatamente quem revogou, porque a linha antiga de concessão continua lá.
   *
   * @param triboIds tribos alvo; lista vazia devolve lista vazia.
   * @param tipos nomes de {@code TipoConsentimento}. Exigidos em conjunção — quem concedeu só um
   *     dos dois não entra.
   */
  List<UUID> usuariosComConsentimento(Collection<UUID> triboIds, Collection<String> tipos);
}
