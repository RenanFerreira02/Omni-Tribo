// RNTL 14 já registra os matchers próprios ao ser importado — não existe mais `extend-expect`.
import { notifyManager } from '@tanstack/react-query';

import { servidor } from './src/testes/servidor';

/**
 * Flush SÍNCRONO das notificações do TanStack Query, só em teste.
 *
 * Por padrão o `notifyManager` agrupa as notificações de observers e as descarrega num
 * `setTimeout(cb, 0)`. Em produção isso é o que evita uma cascata de re-render por requisição; em
 * teste é a origem de todo "An update to <Tela> inside a test was not wrapped in act(...)": quando o
 * timer dispara, o `act()` que o RNTL abriu em volta do `render`/`fireEvent` já fechou, e o React
 * acusa uma atualização de estado fora de escopo.
 *
 * O aviso não é cosmético. Ele significa que existe re-render acontecendo DEPOIS do ponto em que o
 * teste afirmou o resultado — ou seja, a asserção passou sobre um estado intermediário e o estado
 * final nunca foi verificado. Num runner mais lento a mesma corrida pode cair do outro lado.
 *
 * Tornar o scheduler síncrono põe a notificação dentro do `act()` da interação que a causou. Não
 * altera o código de produção: `setScheduler` é API do query-core e só este arquivo a chama.
 */
notifyManager.setScheduler((flush) => flush());

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

/**
 * WebView dublê.
 *
 * O mapa é HTML dentro de uma WebView (ADR 0012), e nada disso executa sob o jest-expo — não há
 * motor de renderização web no ambiente. Mockar é a escolha honesta: o teste verifica que a TELA
 * passa os marcadores certos e reage às mensagens, não que o Leaflet os desenhou. Renderização de
 * mapa continua sendo verificação manual, e está dito assim no ADR.
 *
 * O dublê expõe `injectJavaScript` como jest.fn() para o teste poder afirmar o que foi enviado à
 * página, e guarda `onMessage` num campo estático para simular a mensagem de volta.
 */
jest.mock('react-native-webview', () => {
  const React = require('react');
  const { View } = require('react-native');

  const WebView = React.forwardRef((props: Record<string, unknown>, ref: unknown) => {
    React.useImperativeHandle(ref, () => ({ injectJavaScript: jest.fn() }));
    return React.createElement(View, { testID: 'webview-mapa', ...props });
  });
  WebView.displayName = 'WebView';

  return { WebView, default: WebView, __esModule: true };
});

/**
 * Seletor de data/hora dublê: renderiza nada e não abre diálogo nativo. Quem testa a janela da
 * missão dispara `onChange` direto pelo componente que o embrulha.
 */
jest.mock('@react-native-community/datetimepicker', () => {
  const React = require('react');
  const { View } = require('react-native');
  const DateTimePicker = (props: Record<string, unknown>) =>
    React.createElement(View, { testID: 'seletor-data-nativo', ...props });
  return { __esModule: true, default: DateTimePicker };
});

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
