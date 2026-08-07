package com.omnitribo.carteira.api;

import java.util.Optional;

/**
 * Porta pública do módulo carteira para crédito de recompensa de missão concluída.
 *
 * <p>Existe porque a regra do ArchUnit proíbe {@code missoes} de acessar {@code carteira.dominio}
 * ou {@code carteira.infra}. Mesmo molde de {@code geolocalizacao.api.RegistroCheckin}: interface
 * em {@code api/}, implementação em {@code dominio/}, só tipos da JDK nos parâmetros.
 */
public interface CreditoRecompensa {

  /**
   * Credita a recompensa de uma missão concluída, NA TRANSAÇÃO DO CHAMADOR.
   *
   * <p>Adquire {@code PESSIMISTIC_WRITE} na carteira do executor — primeira leitura dessa entidade
   * na transação — e só então sonda a idempotência e grava. O chamador já segura o lock da linha da
   * missão; a ordem global {@code missao} → {@code carteira} é o que impede deadlock contra o
   * financiamento, que quer as mesmas duas linhas.
   *
   * <p>NÃO abre transação própria, e isso é obrigatório e não preferência: {@code REQUIRES_NEW}
   * aqui pediria uma segunda conexão enquanto a externa segura {@code FOR UPDATE}, esgotando o pool
   * sob concorrência. Ver o javadoc de {@code RegistroCheckinService}, que documenta o incidente.
   *
   * <p>Idempotente: chave já gravada devolve o estado atual com {@code replay = true}, sem inserir
   * nada.
   */
  ResultadoCredito creditarConclusao(ComandoCreditoConclusao comando);

  /**
   * Sonda um crédito já gravado, sem escrever.
   *
   * <p>Existe para o chamador decidir o desfecho de um replay ANTES de checar a transição de
   * estado. Sem isso, o segundo {@code POST /confirmar} de uma missão já CONCLUIDA bateria na
   * máquina de estados e viraria 409 — e um retry de rede não é conflito, é a mesma operação.
   */
  Optional<ResultadoCredito> consultar(String chaveIdempotencia);
}
