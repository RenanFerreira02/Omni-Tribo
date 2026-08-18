import { http, HttpResponse } from 'msw';
import { Platform } from 'react-native';

import { restaurarSessao } from '@/features/auth/restaurarSessao';
import { gravarSeguro } from '@/lib/armazenamentoSeguro';
import { useSessao } from '@/stores/sessao';
import { servidor } from '../../../testes/servidor';

/**
 * Regressão do boot na web, reproduzida com a forma REAL do módulo quebrado.
 *
 * `node_modules/expo-secure-store/build/ExpoSecureStore.web.js` é literalmente `export default {}`,
 * e `SecureStore.js` chama os métodos sem guarda. O mock global de `jest.setup.ts` é um dublê que
 * FUNCIONA — ótimo para os testes de sessão, e inútil para este: ele esconde exatamente a falha em
 * investigação. Aqui o mock é substituído pelo objeto vazio, que é o que a web realmente entrega.
 *
 * O sintoma original era enganoso, e é o segundo teste abaixo que o cobre: `getItemAsync` estourava
 * primeiro, o `catch` de `restaurarSessao` chamava `encerrar()`, o `deleteItemAsync` estourava DENTRO
 * do catch, e era essa segunda exceção que chegava ao usuário — apontando para a linha errada.
 */
jest.mock('expo-secure-store', () => ({}));

const osOriginal = Platform.OS;

function fingirPlataforma(os: typeof Platform.OS): void {
  Object.defineProperty(Platform, 'OS', { value: os, configurable: true });
}

beforeEach(() => {
  fingirPlataforma('web');
  useSessao.setState({ restaurando: true, accessToken: null, refreshToken: null, usuario: null });
});

afterEach(() => fingirPlataforma(osOriginal));

it('o módulo dublado está de fato quebrado — sem isso os testes abaixo não provariam nada', () => {
  // Guarda contra teste vacuoso: se um dia o mock voltar a expor funções, esta assertion cai e
  // ninguém fica achando que o caso da web continua coberto.
  const secureStore: Record<string, unknown> = jest.requireMock('expo-secure-store');
  expect(secureStore.getItemAsync).toBeUndefined();
  expect(secureStore.deleteItemAsync).toBeUndefined();
});

it('primeiro boot na web: não estoura e libera a navegação', async () => {
  // Sem nada no cofre, `lerRefreshPersistido` devolve null e a restauração sai cedo. Antes da
  // correção nem chegava aqui: a leitura em si já estourava.
  await expect(restaurarSessao()).resolves.toBeUndefined();

  // `restaurando: false` é o que destrava as rotas — sem isso o app fica na splash para sempre.
  expect(useSessao.getState().restaurando).toBe(false);
  expect(useSessao.getState().accessToken).toBeNull();
});

it('boot na web com refresh recusado: limpa a sessão em vez de estourar dentro do catch', async () => {
  // ESTE é o caminho exato do relato. Com um refresh no cofre e o servidor recusando a rotação, a
  // restauração cai no `catch` e chama `encerrar()` — que é onde o `deleteItemAsync` inexistente
  // estourava, mascarando o erro original e escapando como unhandled rejection.
  servidor.use(
    http.post(
      'http://api.teste/api/v1/auth/refresh',
      () => new HttpResponse(null, { status: 401 }),
    ),
  );
  await gravarSeguro('omnitribo.refreshToken', 'refresh-velho');

  await expect(restaurarSessao()).resolves.toBeUndefined();

  expect(useSessao.getState().restaurando).toBe(false);
  expect(useSessao.getState().accessToken).toBeNull();
  expect(useSessao.getState().refreshToken).toBeNull();
});
