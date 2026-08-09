import { screen, fireEvent } from '@testing-library/react-native';
import { HttpResponse, http } from 'msw';

import DetalheMissao from '../missao/[id]';
import { missao, problema } from '@/testes/fixtures';
import { render } from '@/testes/render';
import { servidor } from '@/testes/servidor';
import { useSessao } from '@/stores/sessao';

const BASE = 'http://api.teste/api/v1';
const EU = 'bbbbbbbb-0000-0000-0000-000000000002';
const OUTRO = 'bbbbbbbb-0000-0000-0000-000000000009';

jest.mock('expo-router', () => ({
  useRouter: () => ({ push: jest.fn(), replace: jest.fn(), back: jest.fn() }),
  useLocalSearchParams: () => ({ id: 'dddddddd-0000-0000-0000-000000000003' }),
}));

/**
 * Detalhe da missão: botão CONTEXTUAL, confirmação, 409 e as três rejeições de check-in.
 *
 * A matriz completa (status × papel) é testada em `src/features/missoes/__tests__/acoes.test.ts`,
 * que é dado puro. Aqui o que se verifica é que a TELA consome aquela tabela — e os caminhos que só
 * existem em tela: diálogo, reversão otimista e a composição das mensagens de check-in a partir dos
 * campos numéricos do ProblemDetail.
 */
describe('detalhe da missão', () => {
  beforeEach(() => {
    useSessao.setState({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      usuario: { id: EU, email: 'alice@omnitribo.dev', papel: 'USUARIO' },
    });
  });

  function comMissao(sobrescrever: Parameters<typeof missao>[0]) {
    servidor.use(http.get(`${BASE}/missoes/:id`, () => HttpResponse.json(missao(sobrescrever))));
  }

  // ─── Botão contextual, um caso por status ─────────────────────────────────────────────────

  it('ABERTA para terceiro: oferece aceitar', async () => {
    comMissao({ status: 'ABERTA', criadorId: OUTRO, executorId: null });
    await render(<DetalheMissao />);

    expect(await screen.findByTestId('acao-aceitar')).toBeTruthy();
    expect(screen.queryByTestId('explicacao-sem-acao')).toBeNull();
  });

  it('ABERTA para o criador: só cancelar, nunca aceitar a própria missão', async () => {
    comMissao({ status: 'ABERTA', criadorId: EU });
    await render(<DetalheMissao />);

    expect(await screen.findByTestId('acao-cancelar')).toBeTruthy();
    expect(screen.queryByTestId('acao-aceitar')).toBeNull();
  });

  it('EM_ANDAMENTO para o executor: check-in', async () => {
    comMissao({ status: 'EM_ANDAMENTO', criadorId: OUTRO, executorId: EU });
    await render(<DetalheMissao />);

    expect(await screen.findByTestId('acao-checkin')).toBeTruthy();
  });

  it('AGUARDANDO_CONFIRMACAO para o criador: confirmar e contestar', async () => {
    comMissao({ status: 'AGUARDANDO_CONFIRMACAO', criadorId: EU, executorId: OUTRO });
    await render(<DetalheMissao />);

    expect(await screen.findByTestId('acao-confirmar')).toBeTruthy();
    expect(screen.getByTestId('acao-contestar')).toBeTruthy();
  });

  /**
   * O caso que motivou extrair a tabela: antes, quatro status deixavam a tela MUDA — sem botão e
   * sem explicação. O usuário não sabia se estava esperando algo ou se o app havia quebrado.
   */
  it('CONCLUIDA: nenhuma ação, mas com explicação na tela', async () => {
    comMissao({ status: 'CONCLUIDA', criadorId: OUTRO, executorId: EU });
    await render(<DetalheMissao />);

    const explicacao = await screen.findByTestId('explicacao-sem-acao');
    expect(explicacao).toHaveTextContent(/sua carteira/i);
  });

  it('ACEITA por outra pessoa: terceiro entende que perdeu a vez', async () => {
    comMissao({
      status: 'ACEITA',
      criadorId: OUTRO,
      executorId: 'bbbbbbbb-0000-0000-0000-00000000000a',
    });
    await render(<DetalheMissao />);

    expect(await screen.findByTestId('explicacao-sem-acao')).toHaveTextContent(/já aceitou/i);
  });

  it('EM_DISPUTA: ninguém age, porque resolver é exclusivo de ADMIN', async () => {
    comMissao({ status: 'EM_DISPUTA', criadorId: EU, executorId: OUTRO });
    await render(<DetalheMissao />);

    expect(await screen.findByTestId('explicacao-sem-acao')).toHaveTextContent(/disputa/i);
  });

  // ─── Confirmação antes do irreversível ────────────────────────────────────────────────────

  it('aceitar NÃO pede confirmação — quem aceitou pode desistir', async () => {
    comMissao({ status: 'ABERTA', criadorId: OUTRO, executorId: null });
    await render(<DetalheMissao />);

    await fireEvent.press(await screen.findByTestId('acao-aceitar'));
    expect(screen.queryByTestId('dialogo-confirmacao-confirmar')).toBeNull();
  });

  it('confirmar conclusão pede confirmação e só dispara depois dela', async () => {
    comMissao({ status: 'AGUARDANDO_CONFIRMACAO', criadorId: EU, executorId: OUTRO });
    let chamou = false;
    servidor.use(
      http.post(`${BASE}/missoes/:id/confirmar`, () => {
        chamou = true;
        return HttpResponse.json(missao({ status: 'CONCLUIDA' }));
      }),
    );

    await render(<DetalheMissao />);
    await fireEvent.press(await screen.findByTestId('acao-confirmar'));

    // Nada foi enviado ainda: o diálogo existe justamente para dar a chance de recuar.
    expect(chamou).toBe(false);
    expect(await screen.findByTestId('dialogo-confirmacao')).toBeTruthy();

    await fireEvent.press(screen.getByTestId('dialogo-confirmacao-confirmar'));
    await screen.findByTestId('chip-status');
    expect(chamou).toBe(true);
  });

  it('cancelar o diálogo não dispara a ação', async () => {
    comMissao({ status: 'ACEITA', criadorId: OUTRO, executorId: EU });
    let chamou = false;
    servidor.use(
      http.post(`${BASE}/missoes/:id/desistir`, () => {
        chamou = true;
        return HttpResponse.json(missao({ status: 'ABERTA' }));
      }),
    );

    await render(<DetalheMissao />);
    await fireEvent.press(await screen.findByTestId('acao-desistir'));
    await fireEvent.press(screen.getByTestId('dialogo-confirmacao-cancelar'));

    expect(chamou).toBe(false);
  });

  // ─── 409: outra pessoa aceitou primeiro ───────────────────────────────────────────────────

  it('409 de transição inválida explica que outra pessoa chegou antes', async () => {
    comMissao({ status: 'ABERTA', criadorId: OUTRO, executorId: null });
    servidor.use(
      http.post(`${BASE}/missoes/:id/aceitar`, () =>
        HttpResponse.json(problema('transicao-invalida', 409, 'Missão não está mais aberta.'), {
          status: 409,
        }),
      ),
    );

    await render(<DetalheMissao />);
    await fireEvent.press(await screen.findByTestId('acao-aceitar'));

    const erro = await screen.findByTestId('erro-acao');
    expect(erro).toHaveTextContent(/outra pessoa aceitou primeiro/i);
  });

  // ─── Check-in: uma mensagem por `type`, montada dos NÚMEROS ───────────────────────────────

  it('fora do raio: usa distanciaM e raioM, não o texto do servidor', async () => {
    comMissao({ status: 'EM_ANDAMENTO', criadorId: OUTRO, executorId: EU });
    servidor.use(
      http.post(`${BASE}/missoes/:id/checkin`, () =>
        HttpResponse.json(
          problema('checkin-fora-do-raio', 422, 'copy do servidor que pode mudar', {
            distanciaM: 180.4,
            raioM: 50,
          }),
          { status: 422 },
        ),
      ),
    );

    await render(<DetalheMissao />);
    await fireEvent.press(await screen.findByTestId('acao-checkin'));

    const orientacao = await screen.findByTestId('orientacao-checkin');
    // A frase exata do requisito, composta dos campos de extensão do RFC 9457. Se um dia a copy do
    // backend mudar, esta instrução continua correta — é por isso que não se lê o `detail`.
    expect(orientacao).toHaveTextContent(/180 m/);
    expect(orientacao).toHaveTextContent(/50 m/);
    expect(orientacao).toHaveTextContent(/aproxime-se/i);
  });

  it('acurácia insuficiente: diz o tamanho do erro e manda esperar, não caminhar', async () => {
    comMissao({ status: 'EM_ANDAMENTO', criadorId: OUTRO, executorId: EU });
    servidor.use(
      http.post(`${BASE}/missoes/:id/checkin`, () =>
        HttpResponse.json(
          problema('checkin-acuracia-insuficiente', 422, 'precisão ruim', {
            acuraciaM: 180,
            acuraciaMaximaM: 50,
          }),
          { status: 422 },
        ),
      ),
    );

    await render(<DetalheMissao />);
    await fireEvent.press(await screen.findByTestId('acao-checkin'));

    const orientacao = await screen.findByTestId('orientacao-checkin');
    expect(orientacao).toHaveTextContent(/180 m de margem de erro/i);
    expect(orientacao).toHaveTextContent(/mesmo lugar/i);
  });

  it('localização simulada: manda desligar o mock, e NÃO manda se aproximar', async () => {
    comMissao({ status: 'EM_ANDAMENTO', criadorId: OUTRO, executorId: EU });
    servidor.use(
      http.post(`${BASE}/missoes/:id/checkin`, () =>
        HttpResponse.json(problema('checkin-localizacao-simulada', 422, 'mock'), { status: 422 }),
      ),
    );

    await render(<DetalheMissao />);
    await fireEvent.press(await screen.findByTestId('acao-checkin'));

    const orientacao = await screen.findByTestId('orientacao-checkin');
    expect(orientacao).toHaveTextContent(/localização simulada/i);
    // As três instruções são mutuamente inúteis: mandar quem está com mock ligado "aproximar-se" o
    // faria caminhar até o local para falhar de novo no mesmo ponto.
    expect(orientacao).not.toHaveTextContent(/aproxime-se/i);
  });

  it('check-in envia acurácia e mocked verbatim do sensor', async () => {
    comMissao({ status: 'EM_ANDAMENTO', criadorId: OUTRO, executorId: EU });
    let corpoEnviado: Record<string, unknown> | null = null;
    let chaveEnviada: string | null = null;
    servidor.use(
      http.post(`${BASE}/missoes/:id/checkin`, async ({ request }) => {
        corpoEnviado = (await request.json()) as Record<string, unknown>;
        chaveEnviada = request.headers.get('Idempotency-Key');
        return HttpResponse.json(missao({ status: 'AGUARDANDO_CONFIRMACAO' }));
      }),
    );

    await render(<DetalheMissao />);
    await fireEvent.press(await screen.findByTestId('acao-checkin'));
    await screen.findByTestId('chip-status');

    // O app NÃO julga nem "melhora" a leitura do sensor: a régua é do servidor.
    expect(corpoEnviado).toEqual({
      lat: -23.564,
      lon: -46.6934,
      acuraciaM: 8,
      mocked: false,
    });
    expect(chaveEnviada).toBeTruthy();
    expect((chaveEnviada as unknown as string).length).toBeGreaterThanOrEqual(8);
  });

  // ─── Economia ─────────────────────────────────────────────────────────────────────────────

  it('não exibe valor em reais em lugar nenhum', async () => {
    comMissao({ status: 'ABERTA', criadorId: OUTRO });
    await render(<DetalheMissao />);
    await screen.findByTestId('recompensa-tokens');

    expect(screen.queryByText(/R\$/)).toBeNull();
    expect(screen.queryByText(/BRL/)).toBeNull();
  });
});
