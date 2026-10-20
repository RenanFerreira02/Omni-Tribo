import { useEffect as mockUseEffect } from 'react';
import { fireEvent, screen, waitFor } from '@testing-library/react-native';
import { HttpResponse, http } from 'msw';

import TelaMissoes from '../(tabs)/index';
import TelaMapa from '../(tabs)/mapa';
import { PONTO_CUSTODIA, proxima } from '@/testes/fixtures';
import { novoPerfil } from '@/testes/perfilador';
import { render } from '@/testes/render';
import { servidor } from '@/testes/servidor';

const BASE = 'http://api.teste/api/v1';

jest.mock('expo-router', () => ({
  useFocusEffect: (efeito: () => void) => mockUseEffect(efeito, [efeito]),
  useRouter: () => ({ push: jest.fn(), replace: jest.fn(), back: jest.fn() }),
}));

/** O teto que o servidor de fato devolve: `limite: 50` em `useMissoesProximas`. */
const MISSOES = 50;

/** O default de `GET /pontos-custodia` — o cliente não passa `limite`. */
const PONTOS = 50;

function muitasMissoes() {
  return Array.from({ length: MISSOES }, (_, i) =>
    proxima(100 + i * 10, {
      id: `dddddddd-0000-0000-0000-${String(i).padStart(12, '0')}`,
      titulo: `Missão de carga ${i}`,
      categoria: i % 2 === 0 ? 'ENTREGA' : 'TRIBO',
      janelaFim: new Date(Date.now() + 3_600_000).toISOString(),
    }),
  );
}

function muitosPontos() {
  return Array.from({ length: PONTOS }, (_, i) => ({
    ...PONTO_CUSTODIA,
    id: `cccccccc-0000-0000-0000-${String(i).padStart(12, '0')}`,
    apelido: `Ponto de carga ${i}`,
    distanciaM: 100 + i * 10,
  }));
}

beforeEach(() => {
  servidor.use(
    http.get(`${BASE}/missoes/proximas`, () => HttpResponse.json(muitasMissoes())),
    http.get(`${BASE}/pontos-custodia`, () => HttpResponse.json(muitosPontos())),
  );
});

/**
 * MEDIÇÃO DE RENDERIZAÇÃO — não é teste de comportamento.
 *
 * Estes casos existem para produzir NÚMERO, e o número é o que a evidência de performance cola. Eles
 * não afirmam que a tela está lenta: afirmam quanto trabalho de render uma interação custa hoje,
 * para que o mesmo teste diga, depois, se custou menos.
 *
 * As asserções são propositalmente FROUXAS no valor e FIRMES na forma. Um limiar de milissegundos
 * viraria teste instável em runner compartilhado — o que se assere é a contagem de elementos
 * montados, que é determinística, e o tempo vai para o log.
 */
describe('custo de renderização das listas', () => {
  it('a lista do radar virtualiza missões E pontos — nenhuma das duas seções monta inteira', async () => {
    const perfil = novoPerfil('radar-lista');
    await render(perfil.sonda(<TelaMapa />));
    await fireEvent.press(await screen.findByTestId('botao-permitir'));
    await fireEvent.press(await screen.findByTestId('apresentacao-lista'));
    await screen.findByTestId('lista-radar');

    await waitFor(() =>
      expect(screen.queryAllByTestId(/^radar-missao-/).length).toBeGreaterThan(0),
    );

    const missoesMontadas = screen.queryAllByTestId(/^radar-missao-/).length;
    const pontosMontados = screen.queryAllByTestId(/^radar-ponto-/).length;

    console.log(
      `radar em lista: ${missoesMontadas}/${MISSOES} missões montadas, ` +
        `${pontosMontados}/${PONTOS} pontos montados · ${perfil.resumo()}`,
    );

    expect(missoesMontadas).toBeLessThan(MISSOES);
    // Os pontos moravam no `ListFooterComponent`, que a FlatList renderiza inteiro: montavam os 50
    // de uma vez (medido: 50/50). Agora são linhas da mesma lista e entram na janela como as missões.
    expect(pontosMontados).toBeLessThan(PONTOS);
  });

  it('com 50 pontos na janela, só uma parte monta — o custo deixou de acompanhar a lista', async () => {
    // Sem missões, para os pontos começarem DENTRO da janela inicial: é o pior caso do antigo
    // rodapé. Mede montados e custo com 5 e com 50; a razão vai para o log.
    const medir = async (quantos: number) => {
      servidor.use(
        http.get(`${BASE}/missoes/proximas`, () => HttpResponse.json([])),
        http.get(`${BASE}/pontos-custodia`, () =>
          HttpResponse.json(muitosPontos().slice(0, quantos)),
        ),
      );
      const perfil = novoPerfil(`lista-pontos-${quantos}`);
      const tela = await render(perfil.sonda(<TelaMapa />));
      await fireEvent.press(await screen.findByTestId('botao-permitir'));
      await fireEvent.press(await screen.findByTestId('apresentacao-lista'));
      await screen.findByTestId('lista-radar');
      await waitFor(() =>
        expect(screen.queryAllByTestId(/^radar-ponto-/).length).toBeGreaterThan(0),
      );
      const montados = screen.queryAllByTestId(/^radar-ponto-/).length;
      const custo = perfil.commits().reduce((soma, c) => soma + c.atualMs, 0);
      // `await` obrigatório: `unmount()` é assíncrono na RNTL 14, e sem ele o render seguinte
      // TRAVA o processo — sem falhar e sem estourar o testTimeout.
      await tela.unmount();
      return { montados, custo };
    };

    const cinco = await medir(5);
    const cinquenta = await medir(50);

    console.log(
      `lista de pontos: 5 → ${cinco.montados} montados, ${cinco.custo.toFixed(2)} ms; ` +
        `50 → ${cinquenta.montados} montados, ${cinquenta.custo.toFixed(2)} ms ` +
        `(${(cinquenta.custo / cinco.custo).toFixed(2)}×)`,
    );

    expect(cinco.montados).toBe(5);
    expect(cinquenta.montados).toBeLessThan(PONTOS);
  });

  it('a lista do radar mantém as seções, a ordem do servidor, e o toque no ponto abre a folha', async () => {
    servidor.use(
      http.get(`${BASE}/missoes/proximas`, () => HttpResponse.json(muitasMissoes().slice(0, 2))),
      http.get(`${BASE}/pontos-custodia`, () => HttpResponse.json(muitosPontos().slice(0, 3))),
    );
    await render(<TelaMapa />);
    await fireEvent.press(await screen.findByTestId('botao-permitir'));
    await fireEvent.press(await screen.findByTestId('apresentacao-lista'));

    expect(await screen.findByText('Missões próximas (2)')).toBeTruthy();
    expect(screen.getByText('Pontos de custódia (3)')).toBeTruthy();

    const ids = screen.queryAllByTestId(/^radar-ponto-/).map((n) => n.props.testID as string);
    expect(ids).toEqual(
      muitosPontos()
        .slice(0, 3)
        .map((p) => `radar-ponto-${p.id}`),
    );

    await fireEvent.press(screen.getByTestId(ids[1]!));
    expect(await screen.findByTestId('folha-ponto')).toBeTruthy();
    expect(screen.getAllByText('Ponto de carga 1').length).toBeGreaterThan(0);
  });

  it('sem missões e sem pontos, a lista mostra os dois estados vazios', async () => {
    servidor.use(
      http.get(`${BASE}/missoes/proximas`, () => HttpResponse.json([])),
      http.get(`${BASE}/pontos-custodia`, () => HttpResponse.json([])),
    );
    await render(<TelaMapa />);
    await fireEvent.press(await screen.findByTestId('botao-permitir'));
    await fireEvent.press(await screen.findByTestId('apresentacao-lista'));

    expect(await screen.findByTestId('radar-sem-missoes')).toBeTruthy();
    expect(await screen.findByTestId('radar-sem-pontos')).toBeTruthy();
  });

  it('trocar o filtro de categoria re-renderiza a lista inteira de missões', async () => {
    const perfil = novoPerfil('aba-missoes');
    await render(perfil.sonda(<TelaMissoes />));
    await fireEvent.press(await screen.findByTestId('botao-permitir'));
    await waitFor(() => expect(screen.queryAllByTestId(/^missao-/).length).toBeGreaterThan(0));

    // Zera DEPOIS da montagem: o custo de montar domina qualquer soma e esconderia o que a
    // interação custa, que é o que se quer comparar antes e depois.
    perfil.zerar();

    await fireEvent.press(screen.getByTestId('filtro-ENTREGA'));
    await waitFor(() => expect(perfil.atualizacoes().length).toBeGreaterThan(0));

    const montadas = screen.queryAllByTestId(/^missao-/).length;
    console.log(
      `toque no chip ENTREGA: ${perfil.atualizacoes().length} commits, ` +
        `${perfil.custoDasAtualizacoesMs().toFixed(2)} ms de render, ` +
        `${montadas} cards montados`,
    );

    // O que se afirma é só que a interação PRODUZ trabalho de render mensurável. O tamanho desse
    // trabalho é o dado, e ele fica no log — fixá-lo aqui transformaria variação de máquina em
    // build vermelho.
    expect(perfil.custoDasAtualizacoesMs()).toBeGreaterThan(0);
  });
});
