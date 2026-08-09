import { fireEvent, screen } from '@testing-library/react-native';
import { Platform } from 'react-native';

import { SeletorDataHora } from '@/components/SeletorDataHora';
import { render } from '@/testes/render';

/**
 * O encadeamento data → hora do Android não tinha NENHUM teste, e foi assim que uma troca de API da
 * biblioteca (`onChange` depreciado em favor de `onValueChange` + `onDismiss`) chegou ao aparelho
 * como aviso em tempo de execução, em vez de virar build vermelho.
 *
 * O que estes testes protegem é a regra que o componente existe para cumprir: **`aoMudar` dispara
 * UMA vez, com o instante completo.** Um disparo por passo faria o formulário validar uma janela com
 * a data nova e a hora velha, e piscar "fim antes do início" no meio da escolha do usuário.
 */
const osOriginal = Platform.OS;

function fingirPlataforma(os: typeof Platform.OS): void {
  Object.defineProperty(Platform, 'OS', { value: os, configurable: true });
}

afterEach(() => fingirPlataforma(osOriginal));

/** O dublê de `jest.setup.ts` repassa as props para uma View, então elas são inspecionáveis. */
function picker() {
  return screen.getByTestId('janela-picker');
}

/**
 * Datas construídas em horário LOCAL (`new Date(ano, mês, dia)`), nunca por string ISO com `Z`.
 *
 * O picker nativo entrega um `Date` no fuso do aparelho, e o componente o lê com `getFullYear`,
 * `getMonth` e `getHours` — todos locais. Um fixture em UTC faria o teste passar aqui (−03) e falhar
 * em UTC, porque `2026-09-15T00:00Z` é dia **14** às 21h no fuso de São Paulo. Seria o mesmo tipo de
 * intermitência de fuso que já custou caro no CI deste projeto.
 */
const VALOR = new Date(2026, 7, 10, 9, 0, 0, 0);

async function renderizar(aoMudar: (d: Date) => void) {
  return render(
    <SeletorDataHora rotulo="Início" valor={VALOR} aoMudar={aoMudar} testID="janela" />,
  );
}

it('Android: encadeia data e hora e só então propaga, uma única vez', async () => {
  fingirPlataforma('android');
  const aoMudar = jest.fn();
  await renderizar(aoMudar);

  await fireEvent.press(screen.getByTestId('janela'));

  // Passo 1 — data. Nada pode ser propagado ainda: a hora não foi escolhida.
  await fireEvent(picker(), 'valueChange', {}, new Date(2026, 8, 15));
  expect(aoMudar).not.toHaveBeenCalled();

  // Passo 2 — hora.
  await fireEvent(picker(), 'valueChange', {}, new Date(2026, 0, 1, 14, 30));

  expect(aoMudar).toHaveBeenCalledTimes(1);
  const escolhido: Date = aoMudar.mock.calls[0][0];
  // Data do primeiro passo, hora do segundo — é a combinação que o componente promete.
  expect(escolhido.getFullYear()).toBe(2026);
  expect(escolhido.getMonth()).toBe(8); // setembro
  expect(escolhido.getDate()).toBe(15);
  expect(escolhido.getHours()).toBe(14);
  expect(escolhido.getMinutes()).toBe(30);
  expect(escolhido.getSeconds()).toBe(0);
});

it('desistir fecha o seletor sem propagar nada', async () => {
  fingirPlataforma('android');
  const aoMudar = jest.fn();
  await renderizar(aoMudar);

  await fireEvent.press(screen.getByTestId('janela'));
  await fireEvent(picker(), 'dismiss');

  // Com o `onChange` antigo, cancelar chegava como "mudou, talvez sem data" e o componente tinha de
  // adivinhar pelo argumento. Agora é um caminho próprio — e o valor do formulário fica intacto.
  expect(aoMudar).not.toHaveBeenCalled();
  expect(screen.queryByTestId('janela-picker')).toBeNull();
});

it('iOS: modo datetime resolve em um passo só', async () => {
  fingirPlataforma('ios');
  const aoMudar = jest.fn();
  await renderizar(aoMudar);

  await fireEvent.press(screen.getByTestId('janela'));
  expect(picker().props.mode).toBe('datetime');

  await fireEvent(picker(), 'valueChange', {}, new Date(2026, 8, 15, 14, 30));

  expect(aoMudar).toHaveBeenCalledTimes(1);
});

it('não passa mais o onChange depreciado para a biblioteca', async () => {
  // Assertion direta sobre a causa do aviso relatado. A biblioteca avisa se `onChange` for passado,
  // MESMO que os callbacks novos também estejam lá — e um `console.warn` não reprova build nenhum.
  fingirPlataforma('android');
  await renderizar(jest.fn());
  await fireEvent.press(screen.getByTestId('janela'));

  expect(picker().props.onChange).toBeUndefined();
  expect(picker().props.onValueChange).toEqual(expect.any(Function));
  expect(picker().props.onDismiss).toEqual(expect.any(Function));
});
