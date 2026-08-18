import { fireEvent, screen, waitFor } from '@testing-library/react-native';
import { HttpResponse, http } from 'msw';

import CriarMissao from '../(app)/missao/criar';
import { PERFIL, missao, problema } from '@/testes/fixtures';
import { render } from '@/testes/render';
import { servidor } from '@/testes/servidor';
import { useSessao } from '@/stores/sessao';

const BASE = 'http://api.teste/api/v1';

const mockSubstituir = jest.fn();

jest.mock('expo-router', () => ({
  useRouter: () => ({ push: jest.fn(), replace: mockSubstituir, back: jest.fn() }),
  useLocalSearchParams: () => ({}),
}));

/**
 * Criação de missão — a tela onde a ECONOMIA DO CUIDADO pode ser violada por descuido.
 *
 * A maior parte destes casos é sobre o que a tela NÃO faz: não tem campo de valor, não tem campo de
 * recompensa, não reimplementa a fórmula, e não manda `xpRecompensa` nem `tokensRecompensa` no
 * corpo. Um teste que só verificasse o caminho feliz deixaria todas essas portas abertas.
 */
describe('criar missão', () => {
  beforeEach(() => {
    mockSubstituir.mockClear();
    useSessao.setState({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      usuario: { id: PERFIL.id, email: PERFIL.email, papel: 'USUARIO' },
    });
  });

  /** Preenche o mínimo para o formulário ficar válido numa categoria que declara complexidade. */
  async function preencherAjudaValida() {
    await fireEvent.changeText(screen.getByTestId('campo-titulo'), 'Ajudar com a feira');
    await fireEvent.changeText(
      screen.getByTestId('campo-descricao'),
      'Levar as compras da vizinha do mercado até o apartamento.',
    );
    await fireEvent.changeText(screen.getByTestId('campo-cep'), '01001000');
    await fireEvent.changeText(screen.getByTestId('campo-logradouro'), 'Praça da Sé');
    await fireEvent.changeText(screen.getByTestId('campo-bairro'), 'Sé');
    await fireEvent.changeText(screen.getByTestId('campo-cidade'), 'São Paulo');
    await fireEvent.changeText(screen.getByTestId('campo-uf'), 'SP');
    await fireEvent.press(screen.getByTestId('complexidade-MEDIA'));
  }

  // ─── A ausência que define o produto ──────────────────────────────────────────────────────

  it('NÃO tem campo de valor em reais nem de recompensa', async () => {
    await render(<CriarMissao />);

    expect(screen.queryByText(/R\$/)).toBeNull();
    expect(screen.queryByText(/valor em reais/i)).toBeNull();
    // Recompensa aparece como LEITURA, nunca como entrada.
    expect(screen.queryByTestId('campo-xp')).toBeNull();
    expect(screen.queryByTestId('campo-tokens')).toBeNull();
    expect(screen.getByText(/Recompensa calculada/)).toBeTruthy();
    expect(screen.getByText(/Quem cria a missão não paga/)).toBeTruthy();
  });

  it('o payload manda valorBrl 0 e NENHUM campo de recompensa', async () => {
    let corpo: Record<string, unknown> | null = null;
    servidor.use(
      http.post(`${BASE}/missoes`, async ({ request }) => {
        corpo = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(missao({ status: 'RASCUNHO' }));
      }),
    );

    await render(<CriarMissao />);
    await preencherAjudaValida();
    await fireEvent.press(screen.getByTestId('botao-criar'));

    await waitFor(() => expect(corpo).not.toBeNull());
    const enviado = corpo as unknown as Record<string, unknown>;

    // `valorBrl` é @NotNull no servidor e recusado se > 0 — mandar 0 explicitamente é o contrato.
    expect(enviado.valorBrl).toBe(0);
    // A recompensa é calculada e congelada pelo servidor. Mandá-la seria ignorado em silêncio, e o
    // criador veria um número na tela e outro na missão publicada.
    expect(enviado).not.toHaveProperty('xpRecompensa');
    expect(enviado).not.toHaveProperty('tokensRecompensa');
    expect(enviado).not.toHaveProperty('poteTokens');
    expect(enviado.complexidade).toBe('MEDIA');
  });

  // ─── Prévia: leitura, com debounce, e que não bloqueia ────────────────────────────────────

  it('mostra a prévia calculada pelo servidor, em card de leitura', async () => {
    await render(<CriarMissao />);
    await preencherAjudaValida();

    // Regex, e não string: `toHaveTextContent` com string exige o texto CONCATENADO exato do nó, e
    // o card junta "69 XP" com o saldo de tokens ("69 XP23").
    const previa = await screen.findByTestId('previa-recompensa');
    expect(previa).toHaveTextContent(/69 XP/);
    expect(previa).toHaveTextContent(/23/);
  });

  it('prévia indisponível NÃO bloqueia a criação', async () => {
    servidor.use(
      http.post(`${BASE}/missoes/previa-recompensa`, () =>
        HttpResponse.json(problema('erro-interno', 500, 'falhou'), { status: 500 }),
      ),
    );
    let criou = false;
    servidor.use(
      http.post(`${BASE}/missoes`, () => {
        criou = true;
        return HttpResponse.json(missao({ status: 'RASCUNHO' }));
      }),
    );

    await render(<CriarMissao />);
    await preencherAjudaValida();

    // A prévia é informativa; o valor definitivo é congelado pelo servidor na criação de qualquer
    // forma. Bloquear aqui impediria criar missão por causa de um enfeite.
    expect(await screen.findByTestId('previa-indisponivel')).toHaveTextContent(
      /calculada ao publicar/i,
    );

    await fireEvent.press(screen.getByTestId('botao-criar'));
    await waitFor(() => expect(criou).toBe(true));
  });

  // ─── As regras cruzadas do servidor, espelhadas ───────────────────────────────────────────

  it('ENTREGA pede peso e volume, e NÃO oferece complexidade', async () => {
    await render(<CriarMissao />);
    await fireEvent.press(screen.getByTestId('categoria-ENTREGA'));

    expect(screen.getByTestId('campo-peso')).toBeTruthy();
    expect(screen.getByTestId('campo-volume')).toBeTruthy();
    // Com peso e volume o servidor DERIVA a complexidade, e declará-la junto é 400 — não "ignorado".
    expect(screen.queryByTestId('complexidade-LEVE')).toBeNull();
  });

  it('AJUDA pede complexidade, e NÃO oferece peso nem volume', async () => {
    await render(<CriarMissao />);
    await fireEvent.press(screen.getByTestId('categoria-AJUDA'));

    expect(screen.getByTestId('complexidade-LEVE')).toBeTruthy();
    expect(screen.queryByTestId('campo-peso')).toBeNull();
  });

  it('ENTREGA envia peso e volume, sem complexidade', async () => {
    let corpo: Record<string, unknown> | null = null;
    servidor.use(
      http.post(`${BASE}/missoes`, async ({ request }) => {
        corpo = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(missao({ status: 'RASCUNHO' }));
      }),
    );

    await render(<CriarMissao />);
    await fireEvent.press(screen.getByTestId('categoria-ENTREGA'));
    await fireEvent.changeText(screen.getByTestId('campo-titulo'), 'Entregar caixa na Rua A');
    await fireEvent.changeText(
      screen.getByTestId('campo-descricao'),
      'A entrega falhou ontem e o pacote está no ponto de custódia.',
    );
    await fireEvent.changeText(screen.getByTestId('campo-cep'), '01001000');
    await fireEvent.changeText(screen.getByTestId('campo-logradouro'), 'Praça da Sé');
    await fireEvent.changeText(screen.getByTestId('campo-bairro'), 'Sé');
    await fireEvent.changeText(screen.getByTestId('campo-cidade'), 'São Paulo');
    await fireEvent.changeText(screen.getByTestId('campo-uf'), 'SP');
    await fireEvent.changeText(screen.getByTestId('campo-peso'), '3.5');
    await fireEvent.changeText(screen.getByTestId('campo-volume'), '20');
    await fireEvent.press(screen.getByTestId('botao-criar'));

    await waitFor(() => expect(corpo).not.toBeNull());
    const enviado = corpo as unknown as Record<string, unknown>;
    expect(enviado.pesoKg).toBe(3.5);
    expect(enviado.volumeL).toBe(20);
    expect(enviado.complexidade).toBeUndefined();
  });

  it('sem complexidade em AJUDA, o formulário recusa antes de ir à rede', async () => {
    let chamou = false;
    servidor.use(
      http.post(`${BASE}/missoes`, () => {
        chamou = true;
        return HttpResponse.json(missao());
      }),
    );

    await render(<CriarMissao />);
    await fireEvent.changeText(screen.getByTestId('campo-titulo'), 'Ajudar com a feira');
    await fireEvent.changeText(screen.getByTestId('campo-descricao'), 'Descrição suficiente.');
    await fireEvent.changeText(screen.getByTestId('campo-cep'), '01001000');
    await fireEvent.changeText(screen.getByTestId('campo-logradouro'), 'Praça da Sé');
    await fireEvent.changeText(screen.getByTestId('campo-bairro'), 'Sé');
    await fireEvent.changeText(screen.getByTestId('campo-cidade'), 'São Paulo');
    await fireEvent.changeText(screen.getByTestId('campo-uf'), 'SP');
    await fireEvent.press(screen.getByTestId('botao-criar'));

    expect(await screen.findByText(/informe a complexidade/i)).toBeTruthy();
    expect(chamou).toBe(false);
  });

  it('título curto demais é recusado no formulário', async () => {
    await render(<CriarMissao />);
    await fireEvent.changeText(screen.getByTestId('campo-titulo'), 'oi');
    await fireEvent.press(screen.getByTestId('botao-criar'));

    expect(await screen.findByText(/ao menos 5 caracteres/i)).toBeTruthy();
  });

  // ─── CEP ──────────────────────────────────────────────────────────────────────────────────

  it('CEP completo preenche o endereço automaticamente', async () => {
    await render(<CriarMissao />);
    await fireEvent.changeText(screen.getByTestId('campo-cep'), '01001000');

    await waitFor(() =>
      expect(screen.getByTestId('campo-logradouro').props.value).toBe('Praça da Sé'),
    );
    expect(screen.getByTestId('campo-cidade').props.value).toBe('São Paulo');
    expect(screen.getByTestId('campo-uf').props.value).toBe('SP');
  });

  it('CEP inexistente avisa sem apagar o que já foi digitado', async () => {
    servidor.use(
      http.get(`${BASE}/enderecos/:cep`, () =>
        HttpResponse.json(problema('nao-encontrado', 404, 'CEP não encontrado.'), { status: 404 }),
      ),
    );

    await render(<CriarMissao />);
    await fireEvent.changeText(screen.getByTestId('campo-bairro'), 'Digitado à mão');
    await fireEvent.changeText(screen.getByTestId('campo-cep'), '99999999');

    expect(await screen.findByText(/CEP não encontrado/i)).toBeTruthy();
    expect(screen.getByTestId('campo-bairro').props.value).toBe('Digitado à mão');
  });

  it('CEP incompleto não vai à rede', async () => {
    let chamou = false;
    servidor.use(
      http.get(`${BASE}/enderecos/:cep`, () => {
        chamou = true;
        return HttpResponse.json({ cep: '', logradouro: '', bairro: '', cidade: '', uf: '' });
      }),
    );

    await render(<CriarMissao />);
    await fireEvent.changeText(screen.getByTestId('campo-cep'), '0100');
    // Sem o guard de 8 dígitos, cada tecla intermediária viraria um 400 no servidor.
    expect(chamou).toBe(false);
  });

  // ─── Erro do servidor ─────────────────────────────────────────────────────────────────────

  it('recusa do servidor é exibida sem perder o formulário', async () => {
    servidor.use(
      http.post(`${BASE}/missoes`, () =>
        HttpResponse.json(problema('requisicao-invalida', 400, 'Janela inválida.'), {
          status: 400,
        }),
      ),
    );

    await render(<CriarMissao />);
    await preencherAjudaValida();
    await fireEvent.press(screen.getByTestId('botao-criar'));

    expect(await screen.findByTestId('erro-criar')).toHaveTextContent(/janela inválida/i);
    expect(screen.getByTestId('campo-titulo').props.value).toBe('Ajudar com a feira');
    expect(mockSubstituir).not.toHaveBeenCalled();
  });

  it('sucesso leva ao detalhe da missão recém-criada', async () => {
    await render(<CriarMissao />);
    await preencherAjudaValida();
    await fireEvent.press(screen.getByTestId('botao-criar'));

    await waitFor(() =>
      expect(mockSubstituir).toHaveBeenCalledWith('/missao/dddddddd-0000-0000-0000-000000000003'),
    );
  });
});
