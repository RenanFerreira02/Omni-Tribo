/**
 * Setup do teste de integração REAL — sem MSW.
 *
 * A diferença para `jest.setup.ts` é uma só e é o ponto todo: aqui nada intercepta a rede. O que os
 * testes exercitam é o cliente HTTP de verdade contra o Spring Boot em execução.
 */

jest.mock('expo-constants', () => ({
  __esModule: true,
  // `process.env` lido DENTRO da fábrica: jest.mock é içado para antes de qualquer declaração do
  // módulo, então uma constante externa aqui quebraria o build com "not allowed to reference
  // out-of-scope variables".
  default: {
    expoConfig: { extra: { apiUrl: process.env.E2E_API_URL ?? 'http://localhost:8080' } },
  },
}));

jest.mock('expo-secure-store', () => {
  const cofre = new Map<string, string>();
  return {
    setItemAsync: jest.fn(async (chave: string, valor: string) => {
      cofre.set(chave, valor);
    }),
    getItemAsync: jest.fn(async (chave: string) => cofre.get(chave) ?? null),
    deleteItemAsync: jest.fn(async (chave: string) => {
      cofre.delete(chave);
    }),
  };
});

jest.mock('expo-crypto', () => ({
  randomUUID: jest.fn(
    () => `${Date.now().toString(16)}-${Math.random().toString(16).slice(2, 10)}`,
  ),
}));
