import { fireEvent, screen } from '@testing-library/react-native';
import { HttpResponse, http } from 'msw';

import TelaCarteira from '../(tabs)/carteira';
import TelaMapa from '../(tabs)/mapa';
import TelaNotificacoes from '../(tabs)/notificacoes';
import TelaPerfil from '../(tabs)/perfil';
import Onboarding from '../onboarding';
import { sacar } from '@/api/carteira';
import { paraErroApi } from '@/api/erros';
import { PERFIL, problema } from '@/testes/fixtures';
import { render } from '@/testes/render';
import { servidor } from '@/testes/servidor';
import { useSessao } from '@/stores/sessao';

const BASE = 'http://api.teste/api/v1';

const mockEmpurrar = jest.fn();
const mockSubstituir = jest.fn();

jest.mock('expo-router', () => ({
  useRouter: () => ({ push: mockEmpurrar, replace: mockSubstituir, back: jest.fn() }),
  useLocalSearchParams: () => ({}),
}));

/** Uma tela por bloco: caminho feliz e caminho de erro, como o requisito 9 pede. */
beforeEach(() => {
  mockEmpurrar.mockClear();
  mockSubstituir.mockClear();
  useSessao.setState({
    accessToken: 'access-1',
    refreshToken: 'refresh-1',
    usuario: { id: PERFIL.id, email: PERFIL.email, papel: 'USUARIO' },
  });
});

// ─── Onboarding ───────────────────────────────────────────────────────────────────────────────

describe('onboarding', () => {
  it('mostra os três slides e o indicador de páginas', async () => {
    await render(<Onboarding />);

    expect(screen.getByTestId('indicador-paginas')).toBeTruthy();
    expect(screen.getByText(/Missões no seu bairro/)).toBeTruthy();
    expect(screen.getByText(/Check-in no local/)).toBeTruthy();
    // O terceiro slide é o que o produto mais precisa deixar claro desde o início.
    expect(screen.getByText(/XP e tokens, não dinheiro/)).toBeTruthy();
  });

  it('pular leva ao login e marca como visto', async () => {
    await render(<Onboarding />);
    await fireEvent.press(screen.getByTestId('botao-pular'));

    expect(mockSubstituir).toHaveBeenCalledWith('/(auth)/login');
    const secureStore = jest.requireMock('expo-secure-store');
    expect(secureStore.setItemAsync).toHaveBeenCalledWith('omnitribo.onboarding.visto', '1');
  });

  it('não fala em dinheiro em lugar nenhum', async () => {
    await render(<Onboarding />);
    expect(screen.queryByText(/R\$/)).toBeNull();
  });
});

// ─── Mapa ─────────────────────────────────────────────────────────────────────────────────────

describe('mapa', () => {
  /**
   * O diálogo nativo de permissão é de uma via só: negado uma vez, o Android não o mostra de novo.
   * Pedir sem explicar desperdiça a única chance que existe — por isso a justificativa vem ANTES.
   */
  it('mostra a justificativa antes de pedir a permissão do sistema', async () => {
    const location = jest.requireMock('expo-location');
    location.requestForegroundPermissionsAsync.mockClear();

    await render(<TelaMapa />);

    expect(screen.getByTestId('botao-permitir')).toBeTruthy();
    expect(screen.getByText(/nunca é compartilhada com outros usuários/i)).toBeTruthy();
    // Nada foi pedido ao sistema ainda.
    expect(location.requestForegroundPermissionsAsync).not.toHaveBeenCalled();
  });

  it('permissão concedida: desenha o mapa e o card de clima', async () => {
    await render(<TelaMapa />);
    await fireEvent.press(screen.getByTestId('botao-permitir'));

    expect(await screen.findByTestId('mapa')).toBeTruthy();
    expect(await screen.findByTestId('card-clima')).toBeTruthy();
    expect(screen.getByText(/Parcialmente nublado/)).toBeTruthy();
  });

  it('provedor de clima fora do ar: o card SOME, sem mensagem de erro', async () => {
    servidor.use(
      http.get(`${BASE}/clima`, () =>
        HttpResponse.json(problema('servico-externo-indisponivel', 503, 'fora do ar'), {
          status: 503,
        }),
      ),
    );

    await render(<TelaMapa />);
    await fireEvent.press(screen.getByTestId('botao-permitir'));
    await screen.findByTestId('mapa');

    // Degradação prevista não pode virar "erro inesperado" na cara do usuário. Ver ADR 0011.
    expect(screen.queryByTestId('card-clima')).toBeNull();
    expect(screen.queryByText(/indispon/i)).toBeNull();
  });

  it('permissão negada: cai no bairro da tribo, sem posição do usuário', async () => {
    const location = jest.requireMock('expo-location');
    location.requestForegroundPermissionsAsync.mockResolvedValueOnce({ granted: false });

    await render(<TelaMapa />);
    await fireEvent.press(screen.getByTestId('botao-permitir'));

    const aviso = await screen.findByTestId('aviso-localizacao');
    expect(aviso).toHaveTextContent(/Pinheiros/);
    expect(screen.getByTestId('botao-configuracoes')).toBeTruthy();
    // O mapa continua lá: degradar não é sumir.
    expect(screen.getByTestId('mapa')).toBeTruthy();
  });
});

// ─── Carteira ─────────────────────────────────────────────────────────────────────────────────

describe('carteira', () => {
  it('mostra o saldo em token e nunca formata como moeda', async () => {
    await render(<TelaCarteira />);

    expect(await screen.findByTestId('saldo-tokens')).toBeTruthy();
    expect(screen.queryByText(/R\$/)).toBeNull();
    expect(screen.queryByText(/BRL/)).toBeNull();
    expect(screen.queryByText(/0,00/)).toBeNull();
  });

  /**
   * A carteira leva ao RESGATE, e não oferece saque.
   *
   * Substitui o caso que afirmava o botão "Sacar em reais" desabilitado com o motivo ao lado. A
   * justificativa antiga — "um botão ausente não ensina nada" — valia enquanto não havia para onde
   * mandar a pessoa. Agora há: o app promete resgate em benefício de parceiro no card acima, em
   * `SaldoToken` e no onboarding, e esta é a porta. Um catálogo mostra o que a moeda É; o aviso de
   * saque só dizia o que ela não é.
   */
  it('leva ao resgate em benefícios e não oferece saque em reais', async () => {
    await render(<TelaCarteira />);

    await fireEvent.press(await screen.findByTestId('botao-abrir-beneficios'));
    expect(mockEmpurrar).toHaveBeenCalledWith('/beneficios');

    // A ausência é parte do contrato: reintroduzir o botão de saque tem de reprovar aqui.
    expect(screen.queryByTestId('botao-saque')).toBeNull();
    expect(screen.queryByTestId('explicacao-saque')).toBeNull();
    expect(screen.queryByText(/sacar/i)).toBeNull();
  });

  /**
   * E o caminho do 422 continua coberto pela camada de API: se um dia o botão for habilitado, o
   * erro tipado já está tratado. Sem este caso, ligar o botão seria um erro cru na tela.
   */
  it('o endpoint de saque responde 422 saque-desabilitado, e o app o reconhece', async () => {
    servidor.use(
      http.post(`${BASE}/carteira/saques`, () =>
        HttpResponse.json(problema('saque-desabilitado', 422, 'Saque indisponível nesta versão.'), {
          status: 422,
        }),
      ),
    );

    await expect(sacar(10, 'chave-de-teste-1')).rejects.toMatchObject({
      tipo: 'saqueDesabilitado',
      status: 422,
    });

    // E o `type` é o que decide — não o status, que 422 compartilha com regra-negocio-violada.
    const erro = paraErroApi(
      Object.assign(new Error('x'), {
        isAxiosError: true,
        response: { status: 422, data: problema('saque-desabilitado', 422, 'x') },
      }),
    );
    expect(erro.tipo).toBe('saqueDesabilitado');
  });

  it('transferência: envia Idempotency-Key e fecha a folha no sucesso', async () => {
    let chave: string | null = null;
    servidor.use(
      http.post(`${BASE}/carteira/transferencias`, ({ request }) => {
        chave = request.headers.get('Idempotency-Key');
        return HttpResponse.json(
          {
            lancamentoSaidaId: 'eeeeeeee-0000-0000-0000-000000000002',
            lancamentoEntradaId: 'eeeeeeee-0000-0000-0000-000000000003',
            saldoTokensRemetente: 31,
            replay: false,
          },
          { status: 201 },
        );
      }),
    );

    await render(<TelaCarteira />);
    await fireEvent.press(await screen.findByTestId('botao-abrir-transferencia'));

    await fireEvent.changeText(
      screen.getByTestId('campo-destinatario'),
      'bbbbbbbb-0000-0000-0000-000000000003',
    );
    await fireEvent.changeText(screen.getByTestId('campo-tokens'), '10');
    await fireEvent.press(screen.getByTestId('botao-confirmar-transferencia'));

    await screen.findByTestId('saldo-tokens');
    expect(chave).toBeTruthy();
    expect((chave as unknown as string).length).toBeGreaterThanOrEqual(8);
  });

  it('transferência recusada: mostra o motivo do servidor', async () => {
    servidor.use(
      http.post(`${BASE}/carteira/transferencias`, () =>
        HttpResponse.json(
          problema(
            'regra-negocio-violada',
            422,
            'Tokens só podem ser transferidos entre membros da mesma tribo.',
          ),
          { status: 422 },
        ),
      ),
    );

    await render(<TelaCarteira />);
    await fireEvent.press(await screen.findByTestId('botao-abrir-transferencia'));
    await fireEvent.changeText(
      screen.getByTestId('campo-destinatario'),
      'bbbbbbbb-0000-0000-0000-000000000003',
    );
    await fireEvent.changeText(screen.getByTestId('campo-tokens'), '10');
    await fireEvent.press(screen.getByTestId('botao-confirmar-transferencia'));

    expect(await screen.findByTestId('erro-transferencia')).toHaveTextContent(/mesma tribo/i);
  });
});

// ─── Perfil ───────────────────────────────────────────────────────────────────────────────────

describe('perfil', () => {
  it('mostra nome, tribo, nível derivado e conquistas', async () => {
    await render(<TelaPerfil />);

    expect(await screen.findByText('Alice Ferreira')).toBeTruthy();
    expect(screen.getByTestId('tribo-nome')).toHaveTextContent('Tribo Pinheiros');
    // 320 XP → nível 2 pela fórmula. O seed grava 3 na coluna cache; vale a fórmula.
    expect(screen.getByTestId('nivel')).toHaveTextContent('Nível 2');
    expect(screen.getByTestId('barra-xp')).toBeTruthy();
    expect(screen.getByText(/Primeiro passo/)).toBeTruthy();
    // O catálogo vem inteiro: o que falta orienta o próximo objetivo.
    expect(screen.getByTestId('progresso-VIZINHO_PRESENTE')).toBeTruthy();
  });

  it('erro ao carregar o perfil oferece tentar de novo', async () => {
    servidor.use(
      http.get(`${BASE}/usuarios/me`, () =>
        HttpResponse.json(problema('erro-interno', 500, 'Falha inesperada.'), { status: 500 }),
      ),
    );

    await render(<TelaPerfil />);
    expect(await screen.findByTestId('perfil-erro')).toBeTruthy();
  });

  it('LGPD: consentimentos com o estado mais recente e ações de dados', async () => {
    await render(<TelaPerfil />);
    await fireEvent.press(await screen.findByTestId('botao-privacidade'));

    // O seed concede NOTIFICACAO e depois revoga — vale a linha mais recente.
    const notificacao = await screen.findByTestId('consentimento-NOTIFICACAO');
    expect(notificacao.props.value).toBe(false);
    expect(screen.getByTestId('consentimento-TERMOS').props.value).toBe(true);
    expect(screen.getByTestId('botao-exportar')).toBeTruthy();
    expect(screen.getByTestId('botao-excluir-conta')).toBeTruthy();
  });

  it('exclusão de conta exige DUPLA confirmação e a senha', async () => {
    await render(<TelaPerfil />);
    await fireEvent.press(await screen.findByTestId('botao-privacidade'));
    await fireEvent.press(await screen.findByTestId('botao-excluir-conta'));

    // Primeira barreira: o aviso do que se perde.
    const dialogo = await screen.findByTestId('dialogo-excluir');
    expect(dialogo).toHaveTextContent(/não tem volta/i);
    await fireEvent.press(screen.getByTestId('dialogo-excluir-confirmar'));

    // Segunda barreira: a senha. O access token vive 15 min e sobrevive ao aparelho trocar de mãos.
    const botao = await screen.findByTestId('botao-confirmar-exclusao');
    expect(botao.props.accessibilityState.disabled).toBe(true);

    await fireEvent.changeText(screen.getByTestId('campo-senha-exclusao'), 'Senha@123');
    await fireEvent.press(screen.getByTestId('botao-confirmar-exclusao'));

    expect(mockSubstituir).toHaveBeenCalledWith('/(auth)/login');
  });

  it('senha errada na exclusão: 403 exibido, sessão preservada', async () => {
    servidor.use(
      http.delete(`${BASE}/usuarios/me`, () =>
        HttpResponse.json(problema('acesso-negado', 403, 'Senha incorreta.'), { status: 403 }),
      ),
    );

    await render(<TelaPerfil />);
    await fireEvent.press(await screen.findByTestId('botao-privacidade'));
    await fireEvent.press(await screen.findByTestId('botao-excluir-conta'));
    await fireEvent.press(screen.getByTestId('dialogo-excluir-confirmar'));
    await fireEvent.changeText(screen.getByTestId('campo-senha-exclusao'), 'errada');
    await fireEvent.press(screen.getByTestId('botao-confirmar-exclusao'));

    expect(await screen.findByTestId('erro-senha-exclusao')).toHaveTextContent(/senha incorreta/i);
    expect(mockSubstituir).not.toHaveBeenCalled();
  });

  it('não exibe valor em reais', async () => {
    await render(<TelaPerfil />);
    await screen.findByText('Alice Ferreira');
    expect(screen.queryByText(/R\$/)).toBeNull();
  });
});

// ─── Notificações ─────────────────────────────────────────────────────────────────────────────

describe('notificações', () => {
  it('lista os avisos e destaca os não lidos', async () => {
    await render(<TelaNotificacoes />);

    // **Espere pelo CONTEÚDO, nunca pelo container.** `lista-alertas` é a FlatList, que monta já na
    // primeira renderização — de propósito, para o cabeçalho e os filtros ficarem visíveis enquanto
    // os avisos carregam. Um `findByTestId` nela resolve de imediato e não espera coisa nenhuma, e o
    // `getAllByText` que vinha logo depois corria contra uma lista ainda vazia. Passava quase
    // sempre nesta máquina e falhava no CI, que é mais lento: era esta a intermitência do build.
    expect((await screen.findAllByText(/Recompensa creditada/)).length).toBeGreaterThan(0);
    expect(screen.getByTestId('lista-alertas')).toBeTruthy();
    // Ponto, e não só negrito: peso de fonte é sinal fraco em tela pequena.
    expect(screen.getAllByTestId('marca-nao-lido').length).toBe(2);
  });

  it('abrir um aviso o marca como lido', async () => {
    let marcado: string | null = null;
    servidor.use(
      http.patch(`${BASE}/alertas/:id/lido`, ({ params }) => {
        marcado = params.id as string;
        return HttpResponse.json({ id: params.id, lido: true });
      }),
    );

    await render(<TelaNotificacoes />);
    const primeiro = await screen.findByTestId('alerta-dddddddd-0000-0000-0000-000000000003');
    await fireEvent.press(primeiro);

    expect(marcado).toBe('dddddddd-0000-0000-0000-000000000003');
  });

  it('erro ao carregar oferece tentar de novo', async () => {
    servidor.use(
      http.get(`${BASE}/alertas`, () =>
        HttpResponse.json(problema('erro-interno', 500, 'Falhou.'), { status: 500 }),
      ),
    );

    await render(<TelaNotificacoes />);
    expect(await screen.findByTestId('alertas-erro')).toBeTruthy();
  });

  it('caixa vazia explica o que fazer para receber avisos', async () => {
    servidor.use(
      http.get(`${BASE}/alertas`, () =>
        HttpResponse.json({
          conteudo: [],
          pagina: 0,
          tamanho: 20,
          totalElementos: 0,
          totalPaginas: 0,
          primeira: true,
          ultima: true,
        }),
      ),
    );

    await render(<TelaNotificacoes />);
    expect(await screen.findByTestId('alertas-vazio')).toBeTruthy();
  });
});
