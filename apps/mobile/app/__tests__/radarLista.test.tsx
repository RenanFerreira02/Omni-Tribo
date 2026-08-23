import { useEffect as mockUseEffect } from 'react';
import { fireEvent, screen, waitFor } from '@testing-library/react-native';
import { HttpResponse, http } from 'msw';

import DetalheMissao from '../(app)/missao/[id]';
import TelaLogin from '../(auth)/login';
import TelaMapa from '../(tabs)/mapa';
import { PONTO_CUSTODIA, missao, proxima } from '@/testes/fixtures';
import { render } from '@/testes/render';
import { servidor } from '@/testes/servidor';
import { useSessao } from '@/stores/sessao';

const BASE = 'http://api.teste/api/v1';

// `await` no `unmount()`, e não é estilo: na RNTL 14 ele é assíncrono, como `render` e `fireEvent`.
// Sem o await, o segundo `render` do mesmo teste trava o processo INTEIRO — não falha, não estoura
// o `testTimeout`, apenas para. É a quarta armadilha do ambiente de teste que o CLAUDE.md lista,
// aparecendo num método que a documentação da RNTL não destaca.

const mockEmpurrar = jest.fn();
const mockSubstituir = jest.fn();

jest.mock('expo-router', () => {
  const ReactLocal = require('react');
  const { Text } = require('react-native');
  return {
    useRouter: () => ({ push: mockEmpurrar, replace: mockSubstituir, back: jest.fn() }),
    useLocalSearchParams: () => ({ id: PERTO_ID }),
    useFocusEffect: (efeito: () => void) => mockUseEffect(efeito, [efeito]),
    Link: ({ children }: { children: unknown }) => ReactLocal.createElement(Text, null, children),
  };
});

const PERTO_ID = 'dddddddd-0000-0000-0000-00000000aaaa';
const LONGE_ID = 'dddddddd-0000-0000-0000-00000000bbbb';

/** Uma hora à frente do relógio do teste, para o prazo não sair como "encerrada". */
function daquiAUmaHora() {
  return new Date(Date.now() + 60 * 60 * 1000).toISOString();
}

const MISSAO_PERTO = {
  id: PERTO_ID,
  titulo: 'Retirar encomenda na portaria',
  categoria: 'ENTREGA' as const,
  xpRecompensa: 69,
  tokensRecompensa: 23,
  janelaFim: daquiAUmaHora(),
};

const MISSAO_LONGE = {
  id: LONGE_ID,
  titulo: 'Mutirão de limpeza da praça',
  categoria: 'TRIBO' as const,
  xpRecompensa: 40,
  tokensRecompensa: 10,
  janelaFim: daquiAUmaHora(),
};

beforeEach(async () => {
  jest.clearAllMocks();
  await useSessao.getState().encerrar();
  servidor.use(
    // O servidor devolve JÁ ORDENADO por distância crescente — `ORDER BY distancia_m ASC` sobre
    // `geography`, em ConsultasGeoespaciaisPostgis. Aqui vem na ordem certa de propósito.
    http.get(`${BASE}/missoes/proximas`, () =>
      HttpResponse.json([proxima(180, MISSAO_PERTO), proxima(2400, MISSAO_LONGE)]),
    ),
    http.get(`${BASE}/pontos-custodia`, () =>
      HttpResponse.json([{ ...PONTO_CUSTODIA, distanciaM: 240 }]),
    ),
    http.get(`${BASE}/missoes/${PERTO_ID}`, () =>
      HttpResponse.json(missao({ ...MISSAO_PERTO, status: 'ABERTA' })),
    ),
  );
});

/**
 * O RADAR SEM O MAPA.
 *
 * O mapa é WebView + Leaflet (ADR 0012) e não expõe semântica nenhuma: o inventário de
 * acessibilidade mediu isso como A2, BLOQUEIA. A saída não foi remendar a WebView — foi dar ao mesmo
 * destino de rota uma segunda apresentação (ADR 0030).
 *
 * <b>Este arquivo consulta EXCLUSIVAMENTE por papel e nome acessível. Nenhum `testID`.</b> É a
 * diferença entre "o elemento existe na árvore" e "o elemento é alcançável por quem não vê a tela":
 * um teste por `testID` continua verde com o rótulo ausente ou errado, que é exatamente o defeito
 * que ele deveria pegar. Se qualquer passo do percurso perder papel ou rótulo, isto fica vermelho.
 *
 * <b>O que este arquivo NÃO prova:</b> que o TalkBack lê o que está aqui, na ordem que está aqui.
 * Isso exige aparelho, e é a LACUNA L4 — ainda aberta. O que ele prova é a condição NECESSÁRIA:
 * todo passo tem papel e nome, e o percurso fecha sem tocar no mapa.
 */
describe('radar em lista', () => {
  it('percurso completo: login → lista → detalhe → aceitar, sem tocar no mapa', async () => {
    // ── 1. Entrar ────────────────────────────────────────────────────────────────────────────
    const login = await render(<TelaLogin />);
    await fireEvent.changeText(screen.getByLabelText('E-mail'), 'alice@omnitribo.dev');
    await fireEvent.changeText(screen.getByLabelText('Senha'), 'Senha@123');
    await fireEvent.press(screen.getByRole('button', { name: 'Entrar' }));
    await waitFor(() => expect(mockSubstituir).toHaveBeenCalledWith('/(tabs)'));
    await login.unmount();

    // ── 2. Autorizar a localização, pelo botão que a justificativa oferece ───────────────────
    const radar = await render(<TelaMapa />);
    await fireEvent.press(await screen.findByRole('button', { name: 'Permitir localização' }));

    // ── 3. Trocar para a lista ───────────────────────────────────────────────────────────────
    await fireEvent.press(await screen.findByRole('button', { name: 'Lista' }));

    // ── 4. Achar a missão pelo que ela ANUNCIA ───────────────────────────────────────────────
    // Categoria, recompensa, distância e prazo — nessa ordem, que é a ordem da decisão.
    const alvo = await screen.findByRole('button', {
      name: /^Entrega, 69 XP e 23 tokens, a 180 m, termina em 1 h, Retirar encomenda na portaria/,
    });
    await fireEvent.press(alvo);
    expect(mockEmpurrar).toHaveBeenCalledWith(`/missao/${PERTO_ID}`);
    await radar.unmount();

    // ── 5. Aceitar, no detalhe ───────────────────────────────────────────────────────────────
    let aceitou = false;
    servidor.use(
      http.post(`${BASE}/missoes/${PERTO_ID}/aceitar`, () => {
        aceitou = true;
        return HttpResponse.json(
          missao({
            ...MISSAO_PERTO,
            status: 'ACEITA',
            executorId: useSessao.getState().usuario?.id,
          }),
        );
      }),
    );

    await render(<DetalheMissao />);
    await fireEvent.press(await screen.findByRole('button', { name: 'Aceitar missão' }));

    await waitFor(() => expect(aceitou).toBe(true));
  });

  it('o ponto de custódia é alcançável — era o que só existia dentro da WebView', async () => {
    await render(<TelaMapa />);
    await fireEvent.press(await screen.findByRole('button', { name: 'Permitir localização' }));
    await fireEvent.press(await screen.findByRole('button', { name: 'Lista' }));

    // Tipo, distância e ocupação: o que decide se vale ir. Ponto lotado recusa a encomenda.
    const ponto = await screen.findByRole('button', {
      name: 'Ponto de custódia, loja, a 240 m, 47 de 50 vagas livres, Leroy Merlin Pinheiros.',
    });
    await fireEvent.press(ponto);

    // Abre a MESMA folha que o marcador do mapa abre — uma descrição só do ponto no app inteiro.
    expect(await screen.findByRole('header', { name: PONTO_CUSTODIA.apelido })).toBeTruthy();
  });

  it('a ordem é a do SERVIDOR — o cliente não reordena', async () => {
    // Reordenar aqui exigiria um segundo cálculo de distância, quase igual e ocasionalmente
    // diferente do que o mapa desenha. Mesma razão pela qual `formatarDistancia` só formata.
    await render(<TelaMapa />);
    await fireEvent.press(await screen.findByRole('button', { name: 'Permitir localização' }));
    await fireEvent.press(await screen.findByRole('button', { name: 'Lista' }));

    const itens = await screen.findAllByRole('button', { name: /XP e \d+ tokens, a / });
    expect(itens[0].props.accessibilityLabel).toContain('a 180 m');
    expect(itens[1].props.accessibilityLabel).toContain('a 2,4 km');
  });

  it('a escolha sobrevive a fechar e reabrir o app', async () => {
    // Preferência de acessibilidade que não é lembrada é uma barreira cobrada por sessão: quem
    // depende da lista teria de reencontrar o alternador a cada abertura, com leitor de tela.
    const primeira = await render(<TelaMapa />);
    await fireEvent.press(await screen.findByRole('button', { name: 'Permitir localização' }));
    await fireEvent.press(await screen.findByRole('button', { name: 'Lista' }));
    await screen.findByRole('header', { name: /Missões próximas/ });
    await primeira.unmount();

    await render(<TelaMapa />);
    await fireEvent.press(await screen.findByRole('button', { name: 'Permitir localização' }));

    // Sem tocar no alternador: a lista já está lá.
    expect(await screen.findByRole('header', { name: /Missões próximas/ })).toBeTruthy();
  });

  it('a lista vazia EXPLICA, em vez de deixar a tela muda', async () => {
    servidor.use(
      http.get(`${BASE}/missoes/proximas`, () => HttpResponse.json([])),
      http.get(`${BASE}/pontos-custodia`, () => HttpResponse.json([])),
    );

    await render(<TelaMapa />);
    await fireEvent.press(await screen.findByRole('button', { name: 'Permitir localização' }));
    await fireEvent.press(await screen.findByRole('button', { name: 'Lista' }));

    expect(await screen.findByRole('header', { name: 'Nenhuma missão por aqui' })).toBeTruthy();
    expect(screen.getByRole('header', { name: 'Nenhum ponto por perto' })).toBeTruthy();
  });
});
