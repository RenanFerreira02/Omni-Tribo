import { screen, waitFor } from '@testing-library/react-native';
import { HttpResponse, http } from 'msw';

import TelaMissoes from '../(tabs)/index';
import { missao, pagina, problema, proxima } from '@/testes/fixtures';
import { render } from '@/testes/render';
import { servidor } from '@/testes/servidor';

const BASE = 'http://api.teste/api/v1';

jest.mock('expo-router', () => ({
  useRouter: () => ({ push: jest.fn(), replace: jest.fn() }),
}));

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

    await render(<TelaMissoes />);

    expect(await screen.findByText('Entrega perto')).toBeVisible();
    expect(screen.getByText('Coleta mais longe')).toBeVisible();

    // Formatação, não cálculo: 412,7 m vem do PostGIS e é só arredondado para exibição.
    expect(screen.getByText('413 m')).toBeVisible();
    expect(screen.getByText('1,8 km')).toBeVisible();
  });

  it('radar vazio mostra o estado vazio, não uma lista em branco', async () => {
    servidor.use(http.get(`${BASE}/missoes/proximas`, () => HttpResponse.json([])));

    await render(<TelaMissoes />);

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

    await render(<TelaMissoes />);

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

    await render(<TelaMissoes />);
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

    await render(<TelaMissoes />);

    // Sem permissão o radar é impossível — o servidor exige lat/lon. A tela cai para "Todas" e
    // avisa, em vez de mostrar uma lista permanentemente vazia.
    expect(await screen.findByTestId('aviso-localizacao')).toBeVisible();
    await waitFor(() => expect(screen.getByText('Missão da lista completa')).toBeVisible());
  });
});
