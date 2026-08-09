import * as Crypto from 'expo-crypto';

/** UUID v4. `expo-crypto` usa a fonte de aleatoriedade do sistema, não `Math.random()`. */
export function novoUuid(): string {
  return Crypto.randomUUID();
}

/** Um por requisição HTTP: correlaciona a linha de log do servidor com o que o app viu. */
export const novoCorrelationId = novoUuid;

/**
 * Chave de idempotência — um valor por OPERAÇÃO LÓGICA, não por requisição.
 *
 * A distinção é o ponto: se o usuário toca "check-in" e a rede cai, o retry precisa levar a MESMA
 * chave, senão o servidor grava um segundo check-in. Já um novo toque, depois, é outra operação e
 * precisa de chave nova — reaproveitar prenderia o cliente no replay eterno do primeiro resultado,
 * inclusive no de uma rejeição. Por isso a chave nasce junto com a intenção do usuário e viaja com
 * ela, em vez de ser gerada dentro do interceptor.
 *
 * O backend exige 8..200 caracteres; um UUID tem 36. Ele guarda `sha256(usuario|missao|chave)`,
 * nunca a chave crua, então mandar "1" não expõe ninguém ao replay alheio — mas continua sendo uma
 * péssima chave para quem a manda.
 */
export function novaChaveIdempotencia(): string {
  return novoUuid();
}
