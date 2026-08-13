import { fireEvent, screen } from '@testing-library/react-native';
import { HttpResponse, http } from 'msw';

import TelaBeneficios from '../(app)/beneficios';
import { BENEFICIOS } from '@/features/beneficios/catalogo';
import { CARTEIRA, problema } from '@/testes/fixtures';
import { render } from '@/testes/render';
import { servidor } from '@/testes/servidor';
import { useSessao } from '@/stores/sessao';

const BASE = 'http://api.teste/api/v1';

jest.mock('expo-router', () => ({
  useRouter: () => ({ push: jest.fn(), replace: jest.fn(), back: jest.fn() }),
  useLocalSearchParams: () => ({}),
}));

beforeEach(() => {
  useSessao.setState({
    accessToken: 'access-1',
    refreshToken: 'refresh-1',
    usuario: { id: CARTEIRA.usuarioId, email: 'alice@omnitribo.dev', papel: 'USUARIO' },
  });
});

/**
 * A vitrine de resgate — o que o TOKEN compra.
 *
 * A fixture `CARTEIRA` tem **41 tokens**, e os custos do catálogo foram calibrados nela de
 * propósito: o café da manhã custa 40 (alcança) e a cesta custa 45 (faltam 4). Assim as duas
 * metades da regra são exercitadas com o mesmo saldo, sem mock por teste.
 */
describe('benefícios', () => {
  it('lista os benefícios do catálogo com o custo em token', async () => {
    await render(<TelaBeneficios />);

    expect(await screen.findByTestId('saldo-beneficios')).toBeTruthy();
    for (const beneficio of BENEFICIOS) {
      expect(screen.getByTestId(`beneficio-${beneficio.id}`)).toBeTruthy();
    }
  });

  it('marca o que o saldo alcança e diz quanto falta para o resto', async () => {
    await render(<TelaBeneficios />);

    // Espera o SALDO chegar, não o container: com 0 token na primeira renderização todo benefício
    // apareceria como inalcançável, e a assertion abaixo passaria a medir o estado de carregamento.
    await screen.findByTestId('saldo-beneficios');

    expect(screen.getByTestId('estado-padaria-cafe-dois')).toHaveTextContent(/já alcança/i);
    expect(screen.getByTestId('estado-hortifruti-cesta-semana')).toHaveTextContent(
      /faltam 4 tokens/i,
    );
  });

  it('o filtro "já alcanço" reduz a lista ao que o saldo cobre', async () => {
    await render(<TelaBeneficios />);
    await screen.findByTestId('saldo-beneficios');

    await fireEvent.press(screen.getByTestId('filtro-alcancaveis'));

    expect(screen.getByTestId('beneficio-padaria-cafe-dois')).toBeTruthy();
    expect(screen.queryByTestId('beneficio-salao-corte')).toBeNull();
  });

  it('abrir um benefício explica o resgate e NÃO debita nada', async () => {
    await render(<TelaBeneficios />);
    await screen.findByTestId('saldo-beneficios');

    await fireEvent.press(screen.getByTestId('beneficio-mercado-dez-porcento'));

    expect(await screen.findByTestId('aviso-resgate')).toHaveTextContent(
      /nada é descontado agora/i,
    );

    // Nenhuma requisição de escrita acontece — e isto é verificado de graça: `jest.setup.ts` liga o
    // MSW com `onUnhandledRequest: 'error'`, então um POST para uma rota sem manipulador derrubaria
    // este teste alto. O saldo continua o mesmo depois de abrir e fechar a folha.
    await fireEvent.press(screen.getByTestId('botao-fechar-beneficio'));
    expect(screen.getByTestId('saldo-beneficios')).toHaveTextContent(String(CARTEIRA.saldoTokens));
  });

  it('erro ao carregar o saldo oferece tentar de novo', async () => {
    servidor.use(
      http.get(`${BASE}/carteira`, () =>
        HttpResponse.json(problema('erro-interno', 500, 'Falha inesperada no servidor.'), {
          status: 500,
        }),
      ),
    );

    await render(<TelaBeneficios />);

    expect(await screen.findByTestId('beneficios-erro')).toBeTruthy();
    expect(screen.getByText('Tentar de novo')).toBeTruthy();
  });

  /**
   * O mesmo guarda-corpo que a carteira já tinha, espelhado aqui.
   *
   * A tela nova é justamente onde a tentação de escrever "R$ 20 de desconto" aparece — e o ADR 0009
   * §6 recusa isso: cotação token→real transformaria o token em dinheiro, com KYC e enquadramento
   * regulatório junto. Benefício é BEM ou PORCENTAGEM.
   */
  it('não fala em dinheiro em lugar nenhum', async () => {
    await render(<TelaBeneficios />);
    await screen.findByTestId('saldo-beneficios');

    expect(screen.queryByText(/R\$/)).toBeNull();
    expect(screen.queryByText(/BRL/)).toBeNull();
    expect(screen.queryByText(/0,00/)).toBeNull();
  });
});
