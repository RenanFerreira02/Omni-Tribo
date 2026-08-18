package com.omnitribo.identidade.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Estado ATUAL da conta por trás de um access token — a segunda metade da autenticação.
 *
 * <p><b>O problema que esta porta existe para resolver.</b> Um access token vive 15 minutos e é
 * autocontido: quem o tem prova que autenticou, não que a conta ainda existe. {@code
 * StatusUsuario.ATIVO} era verificado em exatamente UM lugar, o login. Consequências medidas:
 *
 * <ul>
 *   <li>Depois de {@code DELETE /usuarios/me}, o token emitido antes continuava valendo: {@code
 *       POST /api/v1/missoes} respondia <b>201</b>, com {@code criadorId} apontando para o usuário
 *       já anonimizado. Revogar refresh token não invalida access token já emitido — a janela
 *       existe SEMPRE, não só quando a revogação falha.
 *   <li>O claim {@code papel} era confiado do mesmo jeito: rebaixar um ADMIN no banco não tinha
 *       efeito por até 15 min, e nesse intervalo ele seguia resolvendo disputa e lendo o saldo de
 *       todos os usuários.
 * </ul>
 *
 * <p><b>Blocklist de {@code jti} foi descartada</b>, embora o token já carregue um. Ela resolveria
 * "revogar UM token" (logout de um aparelho), exige estado persistente ou distribuído — e Redis
 * está fora do MVP por decisão registrada — e morre no restart. O dano real dos dois casos acima é
 * por CONTA, não por token, e uma leitura de conta por requisição resolve os dois com menos
 * infraestrutura. Ver ADR 0016.
 *
 * <p>Porta em {@code identidade/api} porque quem chama é o {@code JwtAuthFilter}, que vive em
 * {@code compartilhado/infra} e não pode alcançar {@code identidade/dominio}.
 */
public interface ConsultaSessao {

  /**
   * Devolve o principal quando a conta pode agir AGORA.
   *
   * <p>Vazio quando a conta não existe, não está {@code ATIVO}, ou foi anonimizada. Os três casos
   * são deliberadamente indistinguíveis para quem chama: a resposta é 401 em todos, e
   * diferenciá-los contaria a um portador de token roubado o que aconteceu com a conta.
   *
   * <p>O principal vem daqui, e não dos claims, porque é isto que faz o papel ser reconferido: se
   * viesse do token, a autoridade continuaria sendo a do momento da emissão.
   */
  Optional<AutenticadoPrincipal> sessaoAtiva(UUID usuarioId);

  /**
   * Descarta o estado em cache de um usuário. Chame SEMPRE depois do commit de quem muda {@code
   * status}, {@code papel} ou anonimiza.
   *
   * <p>Depois do commit, não dentro da transação: invalidar antes deixa uma leitura concorrente
   * repopular o cache com o estado PRÉ-commit, e aí a entrada obsoleta sobrevive o TTL inteiro. É a
   * mesma armadilha já documentada em {@code CacheMissoesProximas}.
   */
  void invalidar(UUID usuarioId);
}
