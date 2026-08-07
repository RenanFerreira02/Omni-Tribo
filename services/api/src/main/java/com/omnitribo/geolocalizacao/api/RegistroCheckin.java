package com.omnitribo.geolocalizacao.api;

import java.util.Optional;

/**
 * Porta pública do módulo geolocalizacao.
 *
 * <p>missoes.dominio depende DESTA interface, nunca da implementação: a regra do ArchUnit proíbe
 * qualquer classe fora de {@code com.omnitribo.geolocalizacao..} de acessar {@code
 * geolocalizacao.dominio..} ou {@code geolocalizacao.infra..}. Como o Spring injeta pela interface,
 * o bytecode de MissaoService nunca nomeia uma classe dos pacotes internos.
 */
public interface RegistroCheckin {

  /**
   * Avalia as regras antifraude e GRAVA o check-in — válido ou rejeitado — em TRANSAÇÃO PRÓPRIA.
   *
   * <p>NUNCA lança por rejeição de regra: o veredito volta no {@link ResultadoCheckin}. Quem chama
   * decide o status HTTP. Essa separação é o que faz a trilha de auditoria sobreviver ao 422.
   *
   * <p>CONTRATO CRÍTICO: a implementação não lê nem escreve a tabela {@code missao}. O chamador
   * mantém {@code SELECT ... FOR UPDATE} sobre a linha da missão enquanto esta transação separada
   * roda; tocar {@code missao} aqui seria deadlock entre duas conexões.
   */
  ResultadoCheckin registrar(ComandoCheckin comando);

  /**
   * Consulta um check-in já gravado pela chave de idempotência, sem gravar nada.
   *
   * <p>Existe para o chamador poder decidir o desfecho de um replay ANTES de checar a transição de
   * estado: a segunda chamada com a mesma chave chega com a missão já em AGUARDANDO_CONFIRMACAO e
   * levaria 409 se a máquina de estados falasse primeiro.
   *
   * <p>O {@code replay} do resultado devolvido é sempre true — por definição, veio de uma linha que
   * já existia.
   */
  Optional<ResultadoCheckin> consultar(String chaveIdempotencia);
}
