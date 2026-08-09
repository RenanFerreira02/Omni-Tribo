import { fireEvent, screen, waitFor } from '@testing-library/react-native';
import { HttpResponse, http } from 'msw';

import TelaMissoes from '../(tabs)/index';
import { missao, pagina, problema, proxima } from '@/testes/fixtures';
import { render } from '@/testes/render';
import { servidor } from '@/testes/servidor';

const BASE = 'http://api.teste/api/v1';

jest.mock('expo-router', () => ({
  useRouter: () => ({ push: jest.fn(), replace: jest.fn() }),
}));

/**
 * Renderiza a tela e concede a permissão pelo BOTÃO, como o usuário faz.
 *
 * A tela não pede localização ao montar — o diálogo do sistema só dispara depois da justificativa.
 * Antes desta correção estes testes chamavam `render` e o radar já vinha carregado, porque a tela
 * gastava o prompt sozinha. Passavam justamente por causa do defeito.
 */
async function renderComLocalizacaoPermitida() {
  const resultado = await render(<TelaMissoes />);
  await fireEvent.press(await screen.findByTestId('botao-permitir'));
  return resultado;
}

/**
 * O teste que faltava, e cuja ausência deixou o defeito passar.
 *
 * Havia um caso garantindo que o mapa mostra a justificativa antes do prompt — e ele passava,
 * porque renderizava a tela de mapa ISOLADA. Só que quem monta primeiro é esta aba, e era ELA que
 * disparava o diálogo do sistema sem explicar nada. Uma auditoria provou isso com um teste
 * descartável fora do projeto.
 *
 * Este caso mora aqui, na primeira tela da área logada, que é onde o risco real está.
 */
describe('permissão de localização', () => {
  it('a tela de missões NÃO pede localização ao montar', async () => {
    const expoLocation = jest.requireMock('expo-location');
    expoLocation.requestForegroundPermissionsAsync.mockClear();

    await render(<TelaMissoes />);

    expect(expoLocation.requestForegroundPermissionsAsync).not.toHaveBeenCalled();
    expect(screen.getByTestId('justificativa-localizacao')).toBeVisible();
  });

  it('o diálogo do sistema só dispara depois do toque em "Permitir"', async () => {
    const expoLocation = jest.requireMock('expo-location');
    expoLocation.requestForegroundPermissionsAsync.mockClear();

    await render(<TelaMissoes />);
    await fireEvent.press(screen.getByTestId('botao-permitir'));

    expect(expoLocation.requestForegroundPermissionsAsync).toHaveBeenCalledTimes(1);
  });

  it('a justificativa diz para que serve e o que continua funcionando sem ela', async () => {
    await render(<TelaMissoes />);

    // Pedir permissão sem dizer o propósito nem a alternativa é o que gasta a única chance que o
    // Android dá — negado uma vez, o diálogo não volta.
    expect(screen.getByText(/missões mais próximas/i)).toBeVisible();
    expect(screen.getByText(/nunca é compartilhada com outros usuários/i)).toBeVisible();
    expect(screen.getByText(/Sem permissão/i)).toBeVisible();
  });
});

describe('tela de missões — modo "Perto de mim"', () => {
  it('lista o que o radar devolveu, com a distância medida pelo servidor', async () => {
    servidor.use(
      http.get(`${BASE}/missoes/proximas`, () =>
        HttpResponse.json([
          proxima(412.7, { id: 'dddddddd-0000-0000-0000-000000000003', titulo: 'Entrega perto' }),
          proxima(1840, {
            id: 'dddddddd-0000-0000-0000-000000000007',
            titulo: 'Coleta mais longe',
            categoria: 'COLETA',
          }),
        ]),
      ),
    );

    await renderComLocalizacaoPermitida();

    expect(await screen.findByText('Entrega perto')).toBeVisible();
    expect(screen.getByText('Coleta mais longe')).toBeVisible();

    // Formatação, não cálculo: 412,7 m vem do PostGIS e é só arredondado para exibição.
    expect(screen.getByText('413 m')).toBeVisible();
    expect(screen.getByText('1,8 km')).toBeVisible();
  });

  it('radar vazio mostra o estado vazio, não uma lista em branco', async () => {
    servidor.use(http.get(`${BASE}/missoes/proximas`, () => HttpResponse.json([])));

    await renderComLocalizacaoPermitida();

    expect(await screen.findByTestId('lista-vazia')).toBeVisible();
    expect(screen.getByText('Nenhuma missão por aqui')).toBeVisible();
  });

  it('erro do radar mostra a mensagem do backend e oferece nova tentativa', async () => {
    servidor.use(
      http.get(`${BASE}/missoes/proximas`, () =>
        HttpResponse.json(problema('erro-interno', 500, 'Falha inesperada no servidor.'), {
          status: 500,
        }),
      ),
    );

    await renderComLocalizacaoPermitida();

    expect(await screen.findByTestId('lista-erro')).toBeVisible();
    expect(screen.getByText('Falha inesperada no servidor.')).toBeVisible();
    expect(screen.getByText('Tentar de novo')).toBeVisible();
  });
});

describe('economia do cuidado na UI', () => {
  it('o card mostra XP e TOKEN, e NUNCA valor em reais', async () => {
    servidor.use(
      http.get(`${BASE}/missoes/proximas`, () =>
        HttpResponse.json([
          proxima(100, { xpRecompensa: 69, tokensRecompensa: 23, valorBrl: 0, titulo: 'Missão X' }),
        ]),
      ),
    );

    await renderComLocalizacaoPermitida();
    await screen.findByText('Missão X');

    expect(screen.getByText('69')).toBeVisible();
    expect(screen.getByText('XP')).toBeVisible();
    expect(screen.getByTestId('recompensa-tokens')).toBeVisible();

    // A asserção que importa é a NEGATIVA. `valorBrl` chega no DTO e vale 0 (ADR 0009); qualquer
    // formatação monetária renderizada significa que alguém a exibiu — inclusive um inofensivo
    // "R$ 0,00", que sugeriria ao usuário que um dia haverá outro número ali.
    expect(screen.queryByText(/R\$/)).toBeNull();
    expect(screen.queryByText(/BRL/)).toBeNull();
    // E o token sai como número puro: nem símbolo, nem duas casas decimais de moeda.
    expect(screen.queryByText('23,00')).toBeNull();
    expect(screen.getByText('23')).toBeVisible();
  });
});

describe('tela de missões — modo "Todas"', () => {
  it('usa a lista paginada quando a localização é negada', async () => {
    const expoLocation = jest.requireMock('expo-location');
    expoLocation.requestForegroundPermissionsAsync.mockResolvedValueOnce({ granted: false });

    servidor.use(
      http.get(`${BASE}/missoes`, () =>
        HttpResponse.json(pagina([missao({ titulo: 'Missão da lista completa' })])),
      ),
    );

    await renderComLocalizacaoPermitida();

    // Sem permissão o radar é impossível — o servidor exige lat/lon. A tela cai para "Todas" e
    // avisa, em vez de mostrar uma lista permanentemente vazia.
    expect(await screen.findByTestId('aviso-localizacao')).toBeVisible();
    await waitFor(() => expect(screen.getByText('Missão da lista completa')).toBeVisible());
  });
});
