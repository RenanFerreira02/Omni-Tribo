import { useEffect as mockUseEffect } from 'react';
import { fireEvent, screen, waitFor } from '@testing-library/react-native';
import { HttpResponse, http } from 'msw';

import EditarMissao from '../(app)/missao/editar/[id]';
import { PERFIL, missao } from '@/testes/fixtures';
import { render } from '@/testes/render';
import { servidor } from '@/testes/servidor';
import { useSessao } from '@/stores/sessao';

const BASE = 'http://api.teste/api/v1';
const ID = 'dddddddd-0000-0000-0000-0000000000b1';

const mockVoltar = jest.fn();

jest.mock('expo-router', () => ({
  useFocusEffect: (efeito: () => void) => mockUseEffect(efeito, [efeito]),
  useRouter: () => ({ push: jest.fn(), replace: jest.fn(), back: mockVoltar }),
  useLocalSearchParams: () => ({ id: 'dddddddd-0000-0000-0000-0000000000b1' }),
}));

/**
 * Edição de missão.
 *
 * O caso que dá nome a esta tela é o último teste: trocar "com token" por "só XP" num rascunho que
 * não consegue publicar. É a edição que destrava quem ficou preso, e o resto da tela existe para
 * que ela caiba num fluxo normal.
 */
describe('editar missão', () => {
  beforeEach(() => {
    mockVoltar.mockClear();
    useSessao.setState({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      usuario: { id: PERFIL.id, email: PERFIL.email, papel: 'USUARIO' },
    });
  });

  const rascunhoTribo = missao({
    id: ID,
    criadorId: PERFIL.id,
    categoria: 'TRIBO',
    status: 'RASCUNHO',
    titulo: 'Mutirão de limpeza da praça',
    fontePote: 'COMUNIDADE',
    tokensRecompensa: 40,
    poteTokens: 0,
    pesoKg: null,
    volumeL: null,
    complexidade: 'MEDIA',
  });

  it('carrega os valores atuais da missão no formulário', async () => {
    servidor.use(http.get(`${BASE}/missoes/:id`, () => HttpResponse.json(rascunhoTribo)));

    await render(<EditarMissao />);

    expect((await screen.findByTestId('campo-titulo')).props.value).toBe(
      'Mutirão de limpeza da praça',
    );
    expect(screen.getByTestId('campo-bairro').props.value).toBe('Pinheiros');
    // A missão veio com token: o chip correspondente é o selecionado.
    expect(screen.getByTestId('recompensa-com-token').props.accessibilityState.selected).toBe(true);
  });

  it('a categoria não é editável, e a tela diz isso em vez de esconder', async () => {
    servidor.use(http.get(`${BASE}/missoes/:id`, () => HttpResponse.json(rascunhoTribo)));

    await render(<EditarMissao />);
    await screen.findByTestId('campo-titulo');

    // A categoria da missão continua VISÍVEL — quem edita precisa saber o que está editando —, e as
    // outras três somem, porque um chip que parece clicável e não faz nada é pior que ausência.
    expect(screen.getByTestId('categoria-TRIBO')).toBeTruthy();
    expect(screen.queryByTestId('categoria-ENTREGA')).toBeNull();
    expect(screen.getByText(/categoria não muda/i)).toBeTruthy();
  });

  it('missão já aceita não abre para edição', async () => {
    servidor.use(
      http.get(`${BASE}/missoes/:id`, () =>
        HttpResponse.json(missao({ id: ID, criadorId: PERFIL.id, status: 'EM_ANDAMENTO' })),
      ),
    );

    await render(<EditarMissao />);

    expect(await screen.findByTestId('erro-carregar-edicao')).toHaveTextContent(
      /não pode mais ser editada/i,
    );
    expect(screen.queryByTestId('campo-titulo')).toBeNull();
  });

  it('em missão ABERTA o PATCH não manda recompensaEmToken nem complexidade', async () => {
    let corpo: Record<string, unknown> | null = null;
    servidor.use(
      http.get(`${BASE}/missoes/:id`, () =>
        HttpResponse.json({ ...rascunhoTribo, status: 'ABERTA' }),
      ),
      http.patch(`${BASE}/missoes/:id`, async ({ request }) => {
        corpo = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ ...rascunhoTribo, status: 'ABERTA' });
      }),
    );

    await render(<EditarMissao />);
    await fireEvent.changeText(await screen.findByTestId('campo-titulo'), 'Mutirão da praça nova');
    await fireEvent.press(screen.getByTestId('botao-salvar'));

    await waitFor(() => expect(corpo).not.toBeNull());
    // A partir de ABERTA a recompensa é promessa, e os dois campos são 409 no servidor. Mandá-los
    // faria TODA edição de missão publicada falhar — inclusive a que só corrige uma vírgula.
    expect(corpo!.recompensaEmToken).toBeUndefined();
    expect(corpo!.complexidade).toBeUndefined();
    expect(corpo!.titulo).toBe('Mutirão da praça nova');
  });

  it('trocar para só-XP num rascunho manda recompensaEmToken false e volta', async () => {
    let corpo: Record<string, unknown> | null = null;
    servidor.use(
      http.get(`${BASE}/missoes/:id`, () => HttpResponse.json(rascunhoTribo)),
      http.patch(`${BASE}/missoes/:id`, async ({ request }) => {
        corpo = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({
          ...rascunhoTribo,
          fontePote: 'SEM_TOKEN',
          tokensRecompensa: 0,
        });
      }),
    );

    await render(<EditarMissao />);
    await fireEvent.press(await screen.findByTestId('recompensa-so-xp'));
    await fireEvent.press(screen.getByTestId('botao-salvar'));

    await waitFor(() => expect(corpo).not.toBeNull());
    expect(corpo!.recompensaEmToken).toBe(false);
    // Em rascunho a complexidade VAI, porque é o único insumo que TRIBO e AJUDA têm — sem ela não
    // haveria como mudar a recompensa dessas categorias.
    expect(corpo!.complexidade).toBe('MEDIA');
    await waitFor(() => expect(mockVoltar).toHaveBeenCalled());
  });

  it('a recusa por pote já financiado é exibida sem perder o que foi digitado', async () => {
    servidor.use(
      http.get(`${BASE}/missoes/:id`, () =>
        HttpResponse.json({ ...rascunhoTribo, poteTokens: 40 }),
      ),
      http.patch(`${BASE}/missoes/:id`, () =>
        HttpResponse.json(
          {
            type: 'https://omnitribo.dev/problemas/pote-insuficiente',
            title: 'Regra de negócio violada',
            status: 422,
            detail:
              'Esta edição baixaria a recompensa para 0 tokens, abaixo dos 40 já financiados.',
            recompensaTokens: 0,
            poteTokens: 40,
          },
          { status: 422 },
        ),
      ),
    );

    await render(<EditarMissao />);
    await fireEvent.changeText(await screen.findByTestId('campo-titulo'), 'Mutirão renomeado');
    await fireEvent.press(screen.getByTestId('recompensa-so-xp'));
    await fireEvent.press(screen.getByTestId('botao-salvar'));

    expect(await screen.findByTestId('erro-formulario')).toHaveTextContent(/já financiados/i);
    // O formulário sobrevive ao erro: quem digitou não redigita.
    expect(screen.getByTestId('campo-titulo').props.value).toBe('Mutirão renomeado');
    expect(mockVoltar).not.toHaveBeenCalled();
  });
});
