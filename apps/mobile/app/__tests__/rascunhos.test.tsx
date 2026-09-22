import { useEffect as mockUseEffect } from 'react';
import { fireEvent, screen, waitFor } from '@testing-library/react-native';
import { HttpResponse, http } from 'msw';

import Rascunhos from '../(app)/missao/rascunhos';
import { PERFIL, missao, pagina } from '@/testes/fixtures';
import { render } from '@/testes/render';
import { servidor } from '@/testes/servidor';
import { useSessao } from '@/stores/sessao';

const BASE = 'http://api.teste/api/v1';

const mockEmpurrar = jest.fn();

jest.mock('expo-router', () => ({
  useFocusEffect: (efeito: () => void) => mockUseEffect(efeito, [efeito]),
  useRouter: () => ({ push: mockEmpurrar, replace: jest.fn(), back: jest.fn() }),
  useLocalSearchParams: () => ({}),
}));

/**
 * A lista de rascunhos.
 *
 * Existe porque a missão criada e não publicada ficava inalcançável: `criar.tsx` navega para o
 * detalhe, e quem saísse de lá redigitava tudo. Os casos aqui cobrem as duas coisas que a tela
 * precisa acertar — pedir o recorte certo ao servidor, e explicar POR QUE cada rascunho ainda não
 * está no ar.
 */
describe('rascunhos', () => {
  beforeEach(() => {
    mockEmpurrar.mockClear();
    useSessao.setState({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      usuario: { id: PERFIL.id, email: PERFIL.email, papel: 'USUARIO' },
    });
  });

  const rascunhoComunitario = missao({
    id: 'dddddddd-0000-0000-0000-0000000000a1',
    categoria: 'TRIBO',
    status: 'RASCUNHO',
    titulo: 'Mutirão de limpeza da praça',
    fontePote: 'COMUNIDADE',
    tokensRecompensa: 40,
    poteTokens: 15,
    pesoKg: null,
    volumeL: null,
  });

  const rascunhoSoXp = missao({
    id: 'dddddddd-0000-0000-0000-0000000000a2',
    categoria: 'AJUDA',
    status: 'RASCUNHO',
    titulo: 'Ajudar a carregar um móvel',
    fontePote: 'SEM_TOKEN',
    tokensRecompensa: 0,
    poteTokens: 0,
    pesoKg: null,
    volumeL: null,
  });

  it('pede ao servidor SÓ os próprios rascunhos', async () => {
    let url: URL | null = null;
    servidor.use(
      http.get(`${BASE}/missoes`, ({ request }) => {
        url = new URL(request.url);
        return HttpResponse.json(pagina([rascunhoSoXp]));
      }),
    );

    await render(<Rascunhos />);
    await waitFor(() => expect(url).not.toBeNull());

    expect(url!.searchParams.get('status')).toBe('RASCUNHO');
    // `minhas=CRIADAS` não é redundante com o status: o servidor já esconde rascunho alheio dentro
    // da consulta, mas sem o escopo esta lista traria também rascunho de missão que o usuário só
    // executa — que não é o que a tela promete mostrar.
    expect(url!.searchParams.get('minhas')).toBe('CRIADAS');
  });

  it('diz quanto falta financiar, e oferece a saída pela edição', async () => {
    servidor.use(
      http.get(`${BASE}/missoes`, () => HttpResponse.json(pagina([rascunhoComunitario]))),
    );

    await render(<Rascunhos />);

    const situacao = await screen.findByTestId(`situacao-${rascunhoComunitario.id}`);
    // 40 de recompensa, 15 no pote: a conta é do CLIENTE, com dois números que ele já tem.
    expect(situacao).toHaveTextContent(/faltam 25 tokens/i);
    // A frase precisa apontar a saída, não só o obstáculo — é a diferença entre um aviso e um beco.
    expect(situacao).toHaveTextContent(/valendo só XP/i);
  });

  it('rascunho só-XP aparece como pronto para publicar, sem falar em financiamento', async () => {
    servidor.use(http.get(`${BASE}/missoes`, () => HttpResponse.json(pagina([rascunhoSoXp]))));

    await render(<Rascunhos />);

    const situacao = await screen.findByTestId(`situacao-${rascunhoSoXp.id}`);
    expect(situacao).toHaveTextContent(/pronta para publicar/i);
    expect(situacao).not.toHaveTextContent(/financ/i);
  });

  it('publicar chama a ação da missão daquela linha', async () => {
    let publicou: string | null = null;
    servidor.use(
      http.get(`${BASE}/missoes`, () => HttpResponse.json(pagina([rascunhoSoXp]))),
      http.post(`${BASE}/missoes/:id/publicar`, ({ params }) => {
        publicou = String(params.id);
        return HttpResponse.json(missao({ status: 'ABERTA' }));
      }),
    );

    await render(<Rascunhos />);
    await fireEvent.press(await screen.findByTestId(`publicar-${rascunhoSoXp.id}`));

    await waitFor(() => expect(publicou).toBe(rascunhoSoXp.id));
  });

  it('descartar CONFIRMA antes, porque cancelar rascunho é terminal', async () => {
    let cancelou = false;
    servidor.use(
      http.get(`${BASE}/missoes`, () => HttpResponse.json(pagina([rascunhoSoXp]))),
      http.post(`${BASE}/missoes/:id/cancelar`, () => {
        cancelou = true;
        return HttpResponse.json(missao({ status: 'CANCELADA' }));
      }),
    );

    await render(<Rascunhos />);
    await fireEvent.press(await screen.findByTestId(`descartar-${rascunhoSoXp.id}`));

    // O toque sozinho NÃO cancela: RASCUNHO --CANCELAR--> CANCELADA é terminal e estorna o pote.
    expect(cancelou).toBe(false);

    const dialogo = await screen.findByTestId(`dialogo-descartar-${rascunhoSoXp.id}`);
    expect(dialogo).toBeTruthy();
  });

  it('a falha de publicar fica NA LINHA, sem derrubar a lista', async () => {
    servidor.use(
      http.get(`${BASE}/missoes`, () => HttpResponse.json(pagina([rascunhoComunitario]))),
      http.post(`${BASE}/missoes/:id/publicar`, () =>
        HttpResponse.json(
          {
            type: 'https://omnitribo.dev/problemas/pote-insuficiente',
            title: 'Regra de negócio violada',
            status: 422,
            detail: 'Missão TRIBO precisa do pote financiado antes de publicar.',
            recompensaTokens: 40,
            poteTokens: 15,
          },
          { status: 422 },
        ),
      ),
    );

    await render(<Rascunhos />);
    await fireEvent.press(await screen.findByTestId(`publicar-${rascunhoComunitario.id}`));

    expect(await screen.findByTestId(`erro-acao-${rascunhoComunitario.id}`)).toBeTruthy();
    // A linha continua lá, com as ações — um erro de uma missão não pode apagar as outras.
    expect(screen.getByTestId(`editar-${rascunhoComunitario.id}`)).toBeTruthy();
  });

  it('sem rascunhos, o estado vazio leva a criar', async () => {
    servidor.use(http.get(`${BASE}/missoes`, () => HttpResponse.json(pagina([]))));

    await render(<Rascunhos />);
    expect(await screen.findByTestId('rascunhos-vazio')).toBeTruthy();
  });
});
