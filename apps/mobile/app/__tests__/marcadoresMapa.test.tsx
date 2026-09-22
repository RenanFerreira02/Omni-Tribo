import { fireEvent, screen } from '@testing-library/react-native';
import { HttpResponse, http } from 'msw';

import TelaMapa from '../(tabs)/mapa';
import { PERFIL, proxima } from '@/testes/fixtures';
import { render } from '@/testes/render';
import { servidor } from '@/testes/servidor';
import { useSessao } from '@/stores/sessao';
import { cores } from '@/theme';
import type { MarcadorMapa } from '@/components/MapaLeaflet';

const BASE = 'http://api.teste/api/v1';

/**
 * Captura dos marcadores que a TELA monta.
 *
 * O `MapaLeaflet` de verdade entrega os marcadores à página por `injectJavaScript`, dentro de uma
 * WebView que já é dublê — o que a tela decidiu não sobrevive até nenhum nó inspecionável. Trocando
 * o componente por um capturador, a asserção recai exatamente sobre a fronteira que estava errada:
 * a prop que `mapa.tsx` monta.
 */
const marcadoresCapturados: MarcadorMapa[][] = [];

jest.mock('@/components/MapaLeaflet', () => {
  const React = jest.requireActual('react');
  const { View } = jest.requireActual('react-native');
  return {
    MapaLeaflet: (props: { marcadores: unknown[]; testID?: string }) => {
      marcadoresCapturados.push(props.marcadores as never);
      return React.createElement(View, { testID: props.testID });
    },
  };
});

jest.mock('expo-router', () => ({
  useFocusEffect: jest.fn(),
  useRouter: () => ({ push: jest.fn(), replace: jest.fn(), back: jest.fn() }),
  useLocalSearchParams: () => ({}),
}));

beforeEach(() => {
  marcadoresCapturados.length = 0;
  useSessao.setState({
    accessToken: 'access-1',
    refreshToken: 'refresh-1',
    usuario: { id: PERFIL.id, email: PERFIL.email, papel: 'USUARIO' },
  });
});

/** O último conjunto montado — os anteriores são renders intermediários da mesma tela. */
function marcadoresDeMissao() {
  const ultimo = marcadoresCapturados[marcadoresCapturados.length - 1] ?? [];
  return ultimo.filter((m) => m.id.startsWith('missao:'));
}

/**
 * A CATEGORIA TRIBO ERA INVISÍVEL NO MAPA.
 *
 * A tela usava `coresCategoria[categoria].texto` como preenchimento do pino. Para TRIBO isso é
 * `cores.branco`, e o pino já é branco em tudo o mais — borda de 2px branca e glifo branco. O
 * resultado era uma mancha branca sobre tiles claros, sem contorno: uma das quatro categorias
 * simplesmente não aparecia, e nenhum teste olhava para a cor do marcador.
 */
it('o pino de uma missão TRIBO não é branco', async () => {
  servidor.use(
    http.get(`${BASE}/missoes/proximas`, () =>
      HttpResponse.json([proxima(300, { categoria: 'TRIBO' })]),
    ),
  );

  await render(<TelaMapa />);
  await fireEvent.press(screen.getByTestId('botao-permitir'));
  await screen.findByTestId('mapa');

  const [pino] = marcadoresDeMissao();
  expect(pino).toBeDefined();
  expect(pino.cor).not.toBe(cores.branco);
  expect(pino.glifo).toBe('▲');
});

/**
 * A regra geral por trás do caso acima: o preenchimento carrega um glifo BRANCO por cima, então
 * nenhuma categoria pode usar como pino uma cor clara. Sem isto, trocar o mapa de cores por um dos
 * outros dois — `fundo` é claro nas quatro — reintroduziria o defeito numa categoria diferente.
 */
it('nenhuma categoria usa cor clara como preenchimento de pino', async () => {
  const claras: string[] = [
    cores.branco,
    cores.papel,
    cores.verdeClaro,
    cores.ambarClaro,
    cores.coralClaro,
  ];

  for (const categoria of ['ENTREGA', 'COLETA', 'TRIBO', 'AJUDA'] as const) {
    marcadoresCapturados.length = 0;
    servidor.use(
      http.get(`${BASE}/missoes/proximas`, () => HttpResponse.json([proxima(300, { categoria })])),
    );

    const tela = await render(<TelaMapa />);
    await fireEvent.press(screen.getByTestId('botao-permitir'));
    await screen.findByTestId('mapa');

    const [pino] = marcadoresDeMissao();
    expect(claras).not.toContain(pino.cor);
    // `unmount` é ASSÍNCRONO na RNTL 14: sem o await, o próximo `render` do laço pendura o
    // processo inteiro, sem falha e sem estourar o timeout.
    await tela.unmount();
  }
});
