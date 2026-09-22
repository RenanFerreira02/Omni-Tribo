import { Dimensions } from 'react-native';
import { fireEvent, screen } from '@testing-library/react-native';

import Onboarding from '../onboarding';
import { render } from '@/testes/render';

const mockScrollTo = jest.fn();

/**
 * Dublê do `ScrollView` que EXPÕE o `scrollTo`.
 *
 * Não há outro jeito de observar este defeito. `scrollTo` é propriedade de INSTÂNCIA do
 * `ScrollView` (atribuída no corpo da classe, não no protótipo), e a RNTL 14 removeu todo acesso a
 * instância de componente composto — `UNSAFE_getByType` e `root.findAllByType` já não existem, e o
 * que sobra é a árvore de nós de host. Sem rolagem real num ambiente de teste, a chamada é o único
 * vestígio de que o carrossel andou.
 *
 * O dublê vive num arquivo SÓ deste caso porque substitui o `ScrollView` do módulo inteiro: nas
 * outras suítes de tela há folhas, listas e cartões que dependem do componente de verdade.
 */
jest.mock('react-native', () => {
  const real = jest.requireActual('react-native');
  const React = jest.requireActual('react');
  const Espiao = React.forwardRef(
    (props: Record<string, unknown>, ref: React.Ref<{ scrollTo: () => void }>) => {
      React.useImperativeHandle(ref, () => ({ scrollTo: mockScrollTo }), []);
      return React.createElement(real.ScrollView, props);
    },
  );
  Espiao.displayName = 'ScrollViewEspiao';
  // `Proxy`, e NÃO `{ ...real }`. O módulo `react-native` expõe quase tudo por getter preguiçoso:
  // espalhá-lo avalia todos de uma vez, e o primeiro a estourar é o `DevMenu`, que pede um
  // TurboModule que não existe no ambiente de teste. O erro não menciona spread nem getter — só um
  // TurboModuleRegistry fundo na pilha.
  return new Proxy(real, {
    get: (alvo, prop) => (prop === 'ScrollView' ? Espiao : alvo[prop]),
  });
});

const mockSubstituir = jest.fn();

jest.mock('expo-router', () => ({
  useFocusEffect: jest.fn(),
  useRouter: () => ({ push: jest.fn(), replace: mockSubstituir, back: jest.fn() }),
  useLocalSearchParams: () => ({}),
}));

beforeEach(() => {
  mockScrollTo.mockClear();
  mockSubstituir.mockClear();
});

/**
 * O BOTÃO PRECISA MOVER O CARROSSEL, e não só as bolinhas.
 *
 * O handler antigo chamava apenas `setAtual`: o indicador avançava, o conteúdo ficava parado, e
 * dois toques concluíam o onboarding com a pessoa ainda olhando o primeiro slide — depois de o
 * indicador ter anunciado "Página 2 de 3" e "Página 3 de 3" sobre um slide que nunca mudou.
 * Nenhum teste tocava neste botão.
 */
it('"Entendi" ROLA o carrossel, e não só avança o indicador', async () => {
  await render(<Onboarding />);

  await fireEvent.press(screen.getByTestId('botao-avancar'));

  expect(mockScrollTo).toHaveBeenCalledTimes(1);
  // O slide ocupa a janela inteira, então o deslocamento do primeiro avanço é uma largura. A
  // largura vem do ambiente em vez de um literal: `useWindowDimensions` lê as dimensões que o
  // preset do jest-expo publica, e fixar o número aqui amarraria o teste a elas.
  const largura = Dimensions.get('window').width;
  expect(mockScrollTo).toHaveBeenCalledWith({ x: largura, animated: true });
});

it('o último slide conclui em vez de rolar', async () => {
  await render(<Onboarding />);

  await fireEvent.press(screen.getByTestId('botao-avancar'));
  await fireEvent.press(screen.getByTestId('botao-avancar'));
  // Terceiro slide: o rótulo vira "Começar" e o botão passa a concluir.
  await fireEvent.press(screen.getByTestId('botao-avancar'));

  expect(mockScrollTo).toHaveBeenCalledTimes(2);
  expect(mockSubstituir).toHaveBeenCalledWith('/(auth)/login');
});
