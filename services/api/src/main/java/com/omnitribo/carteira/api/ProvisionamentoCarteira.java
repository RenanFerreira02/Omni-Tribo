package com.omnitribo.carteira.api;

import java.util.UUID;

/**
 * Criação da carteira de um usuário.
 *
 * <p>Existe porque toda operação de valor pressupõe que a carteira do usuário já está lá: creditar
 * a recompensa de uma missão, transferir tokens ou consultar o extrato falham com 404 se não
 * estiver. Criá-la sob demanda no primeiro crédito seria pior — a criação aconteceria dentro de uma
 * transação que segura locks, e dois créditos simultâneos para o mesmo usuário novo disputariam a
 * criação. Provisionar no registro elimina a questão: a carteira nasce junto com o usuário, na
 * mesma transação, e daí em diante toda operação a encontra pronta.
 */
public interface ProvisionamentoCarteira {

  /**
   * Garante a existência da carteira do usuário, na transação do chamador.
   *
   * <p>Idempotente: chamar para quem já tem carteira não faz nada e não é erro. A unicidade final é
   * de {@code uk_carteira_usuario} no banco.
   *
   * @return id da carteira, nova ou preexistente
   */
  UUID garantirCarteira(UUID usuarioId);
}
