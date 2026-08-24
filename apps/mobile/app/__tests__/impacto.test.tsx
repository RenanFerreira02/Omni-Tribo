import { useEffect as mockUseEffect } from 'react';
import { screen } from '@testing-library/react-native';
import { HttpResponse, http } from 'msw';

import TelaImpacto from '../(app)/impacto';
import { CARTEIRA, IMPACTO, problema } from '@/testes/fixtures';
import { render } from '@/testes/render';
import { servidor } from '@/testes/servidor';
import { useSessao } from '@/stores/sessao';

const BASE = 'http://api.teste/api/v1';

jest.mock('expo-router', () => ({
  // `useFocusEffect` entra no dublê porque `TituloTela` o usa para mover o foco do leitor de tela
  // ao entrar na rota. Aqui ele roda como um `useEffect` comum: numa árvore de teste não há pilha
  // de navegação, e o comportamento que interessa — disparar uma vez na montagem — é o mesmo.
  useFocusEffect: (efeito: () => void) => mockUseEffect(efeito, [efeito]),
  useRouter: () => ({ push: jest.fn(), replace: jest.fn(), back: jest.fn() }),
  useLocalSearchParams: () => ({}),
}));

beforeEach(() => {
  useSessao.setState({
    accessToken: 'access-1',
    refreshToken: 'refresh-1',
    usuario: { id: CARTEIRA.usuarioId, email: 'admin@omnitribo.dev', papel: 'ADMIN' },
  });
});

/**
 * Painel de impacto — a única tela do app que fala de VALOR.
 *
 * O que estes testes protegem não é o layout: é a HONESTIDADE dos números. Três coisas específicas
 * podem regredir sem quebrar nada visualmente, e cada uma tem teste próprio aqui — a premissa
 * deixar de ser declarada como premissa, uma taxa nula virar "0%", e o tamanho da amostra sumir de
 * uma mediana pequena.
 *
 * Consultas por rótulo, não por `testID`: um teste que acha o número pelo id continua verde com o
 * `accessibilityLabel` ausente, e o rótulo é o que um leitor de tela entrega.
 */
describe('painel de impacto', () => {
  it('mostra o custo evitado com a conta que o produziu', async () => {
    await render(<TelaImpacto />);

    expect(await screen.findByLabelText('Custo evitado estimado: R$ 75,00')).toBeTruthy();
    // A conta aparece na tela: ninguém precisa acreditar no total sem ver de onde ele veio.
    expect(screen.getByText('3 re-entregas evitadas × R$ 25,00')).toBeTruthy();
  });

  it('declara a premissa como premissa e mostra a faixa de ±50%', async () => {
    // É o requisito central da tela. Se este teste cair, o painel voltou a apresentar uma suposição
    // com aparência de medição — que é exatamente o que uma banca ataca.
    await render(<TelaImpacto />);

    expect(await screen.findByText(/Premissa, não medição/)).toBeTruthy();
    // A faixa inteira precisa estar LEGÍVEL na tela, não só disponível na resposta.
    expect(screen.getByText(/R\$ 37,50/)).toBeTruthy();
    expect(screen.getByText(/R\$ 112,50/)).toBeTruthy();
    expect(screen.getByText(/não mediu esse custo/)).toBeTruthy();
  });

  it('diz que re-entrega evitada é a missão concluída, e não outra medição', async () => {
    await render(<TelaImpacto />);

    expect(
      await screen.findByText(/é a missão de retirada concluída, com outro nome/),
    ).toBeTruthy();
  });

  it('mostra o funil inteiro, incluindo as encomendas paradas na custódia', async () => {
    await render(<TelaImpacto />);

    expect(await screen.findByLabelText('Recebidas: 22')).toBeTruthy();
    // `pendentes` é o número que explica a conversão baixa. Sem ele, 27,3% leria como "o bairro não
    // responde", quando 12 das 22 nunca foram oferecidas a ninguém.
    expect(screen.getByLabelText('Na custódia, sem missão: 12')).toBeTruthy();
    expect(screen.getByLabelText('Recusadas: ponto lotado: 3')).toBeTruthy();
    expect(screen.getByLabelText('Recusadas: sem patrocínio: 1')).toBeTruthy();

    expect(screen.getByLabelText('Convertidas em missão: 6 de 22, 27,3%')).toBeTruthy();
  });

  it('explica por que as missões criadas são menos que as convertidas', async () => {
    // Sem esta explicação, "10 convertidas" seguido de "4 criadas" lê como conversão gravada pela
    // metade. A causa é histórica — seed anterior ao usuário-sistema — e não é defeito.
    await render(<TelaImpacto />);

    expect(await screen.findByLabelText('Missões de retirada criadas: 4')).toBeTruthy();
    expect(
      screen.getByText(/Menor que as 6 convertidas porque parte delas é de dados históricos/),
    ).toBeTruthy();
  });

  it('converte a mediana em unidade legível e publica o tamanho da amostra', async () => {
    await render(<TelaImpacto />);

    // 8100 s = 2 h 15 min. "8100 s" está correto e é ilegível.
    expect(await screen.findByLabelText('Tempo mediano até o check-in: 2 h 15 min')).toBeTruthy();
    expect(screen.getByText('Amostra: 5 missões.')).toBeTruthy();
  });

  it('avisa quando a amostra é pequena demais para concluir tendência', async () => {
    servidor.use(
      http.get(`${BASE}/admin/impacto`, () =>
        HttpResponse.json({
          ...IMPACTO,
          missoesDeRetirada: { ...IMPACTO.missoesDeRetirada, amostraMediana: 3 },
        }),
      ),
    );

    await render(<TelaImpacto />);

    expect(await screen.findByText(/Pequena demais para concluir tendência/)).toBeTruthy();
  });

  it('taxa sem denominador vira travessão, NUNCA 0%', async () => {
    // Sistema recém-instalado: nada recebido. "0% de conversão" afirmaria fracasso onde não houve
    // tentativa — e é o erro que um painel comete quando trata `null` como número.
    servidor.use(
      http.get(`${BASE}/admin/impacto`, () =>
        HttpResponse.json({
          ...IMPACTO,
          entregasFalidas: {
            recebidas: 0,
            convertidas: 0,
            pendentes: 0,
            recusadasPontoLotado: 0,
            recusadasSemPatrocinio: 0,
            taxaConversao: null,
          },
          missoesDeRetirada: {
            criadas: 0,
            concluidas: 0,
            taxaConclusao: null,
            medianaAteCheckinSegundos: null,
            amostraMediana: 0,
          },
        }),
      ),
    );

    await render(<TelaImpacto />);

    expect(
      await screen.findByLabelText('Convertidas em missão: 0 de 0, sem dados suficientes'),
    ).toBeTruthy();
    expect(
      screen.getByLabelText('Tempo mediano até o check-in: sem dados suficientes'),
    ).toBeTruthy();
    expect(screen.queryByText('0,0% do passo anterior')).toBeNull();
    expect(screen.getByText(/Nenhuma missão com check-in ainda/)).toBeTruthy();
  });

  it('403 vira aviso de painel restrito, discriminado pelo type', async () => {
    // Pelo `type` do RFC 9457, nunca pelo `detail`: o texto do servidor muda a cada revisão de copy
    // e um `if` sobre ele quebraria sem quebrar teste nenhum.
    servidor.use(
      http.get(`${BASE}/admin/impacto`, () =>
        HttpResponse.json(problema('acesso-negado', 403, 'Acesso negado'), { status: 403 }),
      ),
    );

    await render(<TelaImpacto />);

    expect(await screen.findByText('Painel restrito')).toBeTruthy();
    expect(screen.queryByLabelText(/Custo evitado estimado/)).toBeNull();
  });

  it('mostra a circulação como carteiras mais potes', async () => {
    await render(<TelaImpacto />);

    expect(await screen.findByLabelText('Em carteiras: 38200')).toBeTruthy();
    expect(screen.getByLabelText('Em potes de missão: 1200')).toBeTruthy();
    expect(screen.getByLabelText('Em circulação: 39400')).toBeTruthy();
    expect(screen.getByLabelText('Resgatados (queimados): 600')).toBeTruthy();
  });
});
