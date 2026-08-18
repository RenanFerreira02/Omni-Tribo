import { fireEvent, screen, waitFor } from '@testing-library/react-native';
import { HttpResponse, http } from 'msw';

import Login from '../(auth)/login';
import { problema } from '@/testes/fixtures';
import { render } from '@/testes/render';
import { servidor } from '@/testes/servidor';
import { useSessao } from '@/stores/sessao';

const BASE = 'http://api.teste/api/v1';

const mockSubstituir = jest.fn();
jest.mock('expo-router', () => {
  // `require` dentro da fábrica: jest.mock é içado para antes dos imports, então nada do escopo do
  // módulo existe aqui ainda. E o Link precisa mesmo renderizar um <Text> — devolver `children`
  // cru deixaria a string solta dentro de uma <View>, o que o React Native trata como erro fatal.
  const ReactLocal = require('react');
  const { Text } = require('react-native');
  return {
    useRouter: () => ({ replace: mockSubstituir, push: jest.fn() }),
    Link: ({ children }: { children: unknown }) => ReactLocal.createElement(Text, null, children),
  };
});

beforeEach(async () => {
  mockSubstituir.mockClear();
  await useSessao.getState().encerrar();
});

describe('tela de login', () => {
  it('autentica, guarda a sessão e navega para as abas', async () => {
    await render(<Login />);

    await fireEvent.changeText(screen.getByTestId('campo-email'), 'alice@omnitribo.dev');
    await fireEvent.changeText(screen.getByTestId('campo-senha'), 'Senha@123');
    await fireEvent.press(screen.getByTestId('botao-entrar'));

    await waitFor(() => expect(mockSubstituir).toHaveBeenCalledWith('/(tabs)'));

    const sessao = useSessao.getState();
    expect(sessao.accessToken).toBe('access-1');
    expect(sessao.usuario?.email).toBe('alice@omnitribo.dev');
  });

  it('credencial inválida mostra a mensagem e NÃO navega', async () => {
    servidor.use(
      http.post(`${BASE}/auth/login`, () =>
        HttpResponse.json(problema('nao-autenticado', 401, 'Credenciais inválidas'), {
          status: 401,
        }),
      ),
    );

    await render(<Login />);

    await fireEvent.changeText(screen.getByTestId('campo-email'), 'alice@omnitribo.dev');
    await fireEvent.changeText(screen.getByTestId('campo-senha'), 'errada-mas-longa');
    await fireEvent.press(screen.getByTestId('botao-entrar'));

    expect(await screen.findByTestId('erro-login')).toHaveTextContent('Credenciais inválidas');
    expect(mockSubstituir).not.toHaveBeenCalled();
    expect(useSessao.getState().accessToken).toBeNull();
  });

  it('valida no cliente antes de ir à rede', async () => {
    // Sem manipulador para /auth/login neste caso: se a validação local vazar, o MSW derruba o
    // teste com "unhandled request" em vez de deixar passar silenciosamente.
    servidor.use(
      http.post(`${BASE}/auth/login`, () => {
        throw new Error('não deveria chamar a API com e-mail inválido');
      }),
    );

    await render(<Login />);

    await fireEvent.changeText(screen.getByTestId('campo-email'), 'nao-e-email');
    await fireEvent.changeText(screen.getByTestId('campo-senha'), 'Senha@123');
    await fireEvent.press(screen.getByTestId('botao-entrar'));

    expect(await screen.findByText('Informe um e-mail válido.')).toBeVisible();
    expect(mockSubstituir).not.toHaveBeenCalled();
  });

  it('erro de validação do servidor marca o campo correspondente', async () => {
    servidor.use(
      http.post(`${BASE}/auth/login`, () =>
        HttpResponse.json(
          problema('requisicao-invalida', 400, 'Um ou mais campos falharam na validação.', {
            errors: [{ campo: 'email', mensagem: 'E-mail não cadastrado' }],
          }),
          { status: 400 },
        ),
      ),
    );

    await render(<Login />);

    await fireEvent.changeText(screen.getByTestId('campo-email'), 'alice@omnitribo.dev');
    await fireEvent.changeText(screen.getByTestId('campo-senha'), 'Senha@123');
    await fireEvent.press(screen.getByTestId('botao-entrar'));

    expect(await screen.findByText('E-mail não cadastrado')).toBeVisible();
  });
});
