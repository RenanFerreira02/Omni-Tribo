package com.omnitribo.compartilhado.api;

/**
 * PORTA do bloqueio progressivo de login: 5 tentativas/min por (IP, e-mail), e 15 min de bloqueio
 * após 10 falhas.
 *
 * <p>Existe pelo mesmo motivo de {@link EmissorDeToken}: {@code AutenticacaoService}, em {@code
 * identidade/dominio}, chamava direto a implementação em {@code compartilhado/infra}.
 *
 * <p>A chave é {@code sha256(ip + ":" + email)}, e vale saber de onde vem o {@code ip} que chega
 * aqui: {@code EnderecoDoCliente}, que devolve o peer TCP real e <b>ignora {@code
 * X-Forwarded-For}</b>. Enquanto o IP saía desse header, cada tentativa podia trazer uma chave nova
 * e este controle inteiro era contornável com uma linha de curl.
 */
public interface ControleDeTentativasLogin {

  /** Bloqueio em vigor para esta chave, ou {@code null} quando pode tentar. */
  BloqueioAtivo verificar(String ip, String email);

  /** Conta mais uma falha e, no limiar, abre o bloqueio — gravando na trilha de auditoria. */
  void registrarFalha(String ip, String email);

  /** Zera o contador de falhas. Só o login bem-sucedido chama. */
  void registrarSucesso(String ip, String email);

  /** Quanto falta para poder tentar de novo, em segundos. Vira o header {@code Retry-After}. */
  record BloqueioAtivo(long segundosRestantes) {}
}
