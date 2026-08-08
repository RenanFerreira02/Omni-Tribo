// RNTL 14 já registra os matchers próprios ao ser importado — não existe mais `extend-expect`.
import { servidor } from './src/testes/servidor';

/**
 * baseURL fixa e previsível.
 *
 * `resolverBaseUrl()` deriva o endereço do host do Metro em dev, o que é ótimo no aparelho e
 * péssimo em teste — o valor mudaria conforme a máquina. Fixar `extra.apiUrl` aqui faz os
 * manipuladores do MSW casarem sempre, e de quebra exercita o mesmo caminho de configuração que o
 * app usa em produção.
 */
jest.mock('expo-constants', () => ({
  __esModule: true,
  default: { expoConfig: { extra: { apiUrl: 'http://api.teste' } } },
}));

// Keystore nativo não existe no ambiente de teste. O dublê guarda em memória — o que importa é que
// a sessão continue passando por AQUI, e não por AsyncStorage.
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
  randomUUID: jest.fn(() => `uuid-${Math.random().toString(36).slice(2, 10)}`),
}));

jest.mock('expo-location', () => ({
  requestForegroundPermissionsAsync: jest.fn(async () => ({ granted: true })),
  getCurrentPositionAsync: jest.fn(async () => ({
    coords: { latitude: -23.564, longitude: -46.6934, accuracy: 8 },
    mocked: false,
  })),
  Accuracy: { High: 4 },
}));

beforeAll(() => {
  // `error` e não `warn`: uma requisição sem manipulador é um teste testando a rede de verdade,
  // e isso precisa falhar alto em vez de virar um timeout misterioso.
  servidor.listen({ onUnhandledRequest: 'error' });
});

afterEach(() => {
  servidor.resetHandlers();
});

afterAll(() => {
  servidor.close();
});
