import { setupServer } from 'msw/native';

import { manipuladores } from './manipuladores';

/**
 * `msw/native` — e não `msw/node`.
 *
 * O ambiente do jest-expo é o do React Native: `msw/node` intercepta o módulo `http` do Node, que
 * não é por onde o axios sai aqui. O ponto de entrada `native` instala interceptadores de
 * XMLHttpRequest e fetch, que é o que o app de fato usa. Trocar por `msw/node` faz os testes
 * passarem pela rede de verdade — e falharem por conexão recusada, o que é lido como bug de código.
 */
export const servidor = setupServer(...manipuladores);

/** Base usada nos manipuladores; casa com o que `resolverBaseUrl()` devolve sob teste. */
export const BASE_TESTE = 'http://api.teste/api/v1';
