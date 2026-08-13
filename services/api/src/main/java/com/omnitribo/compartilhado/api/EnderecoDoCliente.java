package com.omnitribo.compartilhado.api;

import jakarta.servlet.http.HttpServletRequest;

/**
 * ÚNICO ponto do sistema que decide qual é o IP de quem chamou.
 *
 * <p><b>Não lê {@code X-Forwarded-For}, e essa é a correção — não um esquecimento.</b> Antes três
 * lugares liam o header por conta própria ({@code AuthController}, {@code AuditoriaAspecto} e
 * {@code RateLimitFilter}), com dois deles comentando que "em produção é preciso validar que o
 * proxy é confiável" e nenhum validando. O efeito não era teórico: a chave do bloqueio progressivo
 * de login é {@code sha256(ip + ":" + email)}, então bastava incrementar o header a cada tentativa
 * para que cada requisição tivesse uma chave nova e o bloqueio de 5/min nunca acumulasse.
 * Credential stuffing ilimitado contra um único e-mail, com o Argon2 do servidor trabalhando contra
 * o defensor.
 *
 * <p>Quem resolve proxy agora é a {@code RemoteIpValve} do Tomcat, configurada por {@code
 * server.tomcat.remoteip.trusted-proxies}: ausente em dev e test (nenhum proxy é confiável, e
 * {@code getRemoteAddr()} é o peer real), preenchida em produção com o CIDR do proxy de verdade.
 * Com isso a confiança no header vira um dado de AMBIENTE e não uma linha de código, e nenhum call
 * site novo consegue reabrir o buraco por descuido.
 *
 * <p>Alternativa descartada: {@code server.forward-headers-strategy: FRAMEWORK} ou {@code NATIVE}.
 * Os dois fazem {@code getRemoteAddr()} honrar {@code X-Forwarded-For} <b>incondicionalmente</b> —
 * trocaria três leituras inseguras visíveis por uma leitura insegura embutida no container, o que é
 * pior justamente por sumir do código. Ver ADR 0019.
 *
 * <p>Trunca em 45 porque {@code auditoria.ip} é {@code VARCHAR(45)} (comprimento de um IPv6 com
 * sufixo IPv4 mapeado). A entidade trunca de novo, por ser o choke point de todos os gravadores;
 * aqui é para que nem o valor em memória exceda o que cabe.
 *
 * <p>Mora em {@code api/} e não em {@code infra/} porque {@code AuthController}, de outro módulo,
 * precisa dele — e a regra do ArchUnit reserva {@code compartilhado/infra} para adaptador privado.
 */
public final class EnderecoDoCliente {

  /** {@code auditoria.ip} é VARCHAR(45): um IPv6 com IPv4 mapeado cabe, nada maior existe. */
  public static final int TAMANHO_MAXIMO = 45;

  private EnderecoDoCliente() {}

  public static String de(HttpServletRequest request) {
    String remoto = request.getRemoteAddr();
    if (remoto == null) {
      return null;
    }
    return remoto.length() <= TAMANHO_MAXIMO ? remoto : remoto.substring(0, TAMANHO_MAXIMO);
  }
}
