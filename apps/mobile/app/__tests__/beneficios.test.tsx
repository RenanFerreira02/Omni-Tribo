import { useEffect as mockUseEffect } from 'react';
import { AccessibilityInfo } from 'react-native';
import { fireEvent, screen, waitFor } from '@testing-library/react-native';
import { HttpResponse, http } from 'msw';

import TelaBeneficios from '../(app)/beneficios';
import {
  BENEFICIO_ALCANCAVEL,
  BENEFICIO_CARO,
  CARTEIRA,
  RESGATE,
  problema,
} from '@/testes/fixtures';
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
    usuario: { id: CARTEIRA.usuarioId, email: 'alice@omnitribo.dev', papel: 'USUARIO' },
  });
});

/**
 * Resgate de benefício — o que o TOKEN compra, agora contra o backend de verdade.
 *
 * As consultas são por PAPEL e RÓTULO, não por `testID`. A diferença não é estilo: um teste que
 * acha o botão por `testID` continua verde com o `accessibilityLabel` errado ou ausente, e é
 * exatamente isso que a LACUNA L4 da auditoria mobile registrou — anotação concentrada nos
 * componentes, ausente nas telas. Consultar como um leitor de tela consulta faz o teste falhar
 * quando a acessibilidade regride.
 *
 * A fixture `CARTEIRA` tem **41 tokens**; o café custa 15 (alcança) e a revisão custa 60 (faltam
 * 19). As duas metades da regra saem do mesmo saldo, sem mock por teste.
 */
describe('benefícios', () => {
  it('lista o catálogo vindo da API, com custo e parceiro no rótulo', async () => {
    await render(<TelaBeneficios />);

    expect(
      await screen.findByRole('button', {
        name: `${BENEFICIO_ALCANCAVEL.titulo}, ${BENEFICIO_ALCANCAVEL.parceiroNome}, 15 tokens`,
      }),
    ).toBeTruthy();

    expect(
      screen.getByRole('button', {
        name: `${BENEFICIO_CARO.titulo}, ${BENEFICIO_CARO.parceiroNome}, 60 tokens`,
      }),
    ).toBeTruthy();
  });

  it('catálogo vazio ENSINA o que fazer, em vez de só dizer que não há nada', async () => {
    servidor.use(
      http.get(`${BASE}/beneficios`, () =>
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

    await render(<TelaBeneficios />);

    expect(await screen.findByText('Nenhum benefício disponível no seu bairro ainda')).toBeTruthy();
    // O que FAZER a respeito: sem isto o vazio parece defeito do app.
    expect(screen.getByText(/fale com a sua tribo/i)).toBeTruthy();
  });

  it('resgatar exige CONFIRMAÇÃO antes de debitar', async () => {
    await render(<TelaBeneficios />);

    await fireEvent.press(await abrirCafe());

    // O primeiro toque NÃO resgata: abre a confirmação.
    await fireEvent.press(screen.getByRole('button', { name: 'Resgatar por 15 tokens' }));

    expect(screen.getByText(/eles saem de circulação e não voltam/i)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Confirmar resgate de 15 tokens' })).toBeTruthy();
    // Enquanto não confirmou, o saldo continua o da carteira.
    expect(screen.getByLabelText(`${CARTEIRA.saldoTokens} tokens`)).toBeTruthy();
  });

  it('resgate confirmado mostra o código, anuncia o resultado e atualiza o saldo', async () => {
    const anunciar = jest.spyOn(AccessibilityInfo, 'announceForAccessibility');

    await render(<TelaBeneficios />);
    await fireEvent.press(await abrirCafe());
    await fireEvent.press(screen.getByRole('button', { name: 'Resgatar por 15 tokens' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Confirmar resgate de 15 tokens' }));

    expect(await screen.findByText('CVYU5UCH')).toBeTruthy();

    // O código é lido CARACTERE A CARACTERE: sem isto o leitor pronuncia "CVYU5UCH" como palavra e
    // quem está no balcão não consegue repetir o que ouviu.
    expect(screen.getByLabelText('Código de retirada: C, V, Y, U, 5, U, C, H')).toBeTruthy();

    // O desfecho é ANUNCIADO, não só desenhado.
    await waitFor(() =>
      expect(anunciar).toHaveBeenCalledWith(expect.stringContaining('Resgate concluído')),
    );
    expect(anunciar).toHaveBeenCalledWith(expect.stringContaining('C, V, Y, U, 5, U, C, H'));

    anunciar.mockRestore();
  });

  it('o saldo NÃO muda enquanto o servidor não confirma', async () => {
    // Requisição pendurada de propósito — o caso da rede lenta, não o do erro. A promessa é
    // LIBERADA no fim do teste: um `new Promise(() => {})` que nunca resolve deixa o handle aberto
    // e o jest não encerra o worker.
    let liberar: () => void = () => {};
    const pendente = new Promise<void>((resolver) => {
      liberar = resolver;
    });
    servidor.use(
      http.post(`${BASE}/resgates`, async () => {
        await pendente;
        return HttpResponse.json(RESGATE, { status: 201 });
      }),
    );

    await render(<TelaBeneficios />);
    await fireEvent.press(await abrirCafe());
    await fireEvent.press(screen.getByRole('button', { name: 'Resgatar por 15 tokens' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Confirmar resgate de 15 tokens' }));

    expect(await screen.findByRole('button', { name: 'Resgatando…' })).toBeTruthy();
    // Nenhum débito otimista: o saldo é o do servidor até que ele responda.
    expect(screen.getByLabelText(`${CARTEIRA.saldoTokens} tokens`)).toBeTruthy();
    expect(screen.queryByText('CVYU5UCH')).toBeNull();

    liberar();
    await waitFor(() => expect(screen.getByText('CVYU5UCH')).toBeTruthy());
  });

  it('saldo insuficiente diz QUANTO falta, sem parsear o detail do servidor', async () => {
    await render(<TelaBeneficios />);

    // 41 de saldo, 60 de custo: a tela calcula os 19 sozinha, com números que já tem.
    await fireEvent.press(
      await screen.findByRole('button', {
        name: `${BENEFICIO_CARO.titulo}, ${BENEFICIO_CARO.parceiroNome}, 60 tokens`,
      }),
    );

    expect(screen.getByText(/faltam 19 tokens para este benefício/i)).toBeTruthy();
    // Não oferece um botão que só levaria a um 422.
    expect(screen.queryByRole('button', { name: /resgatar por/i })).toBeNull();
  });

  it('erro do servidor no resgate é anunciado e explicado', async () => {
    const anunciar = jest.spyOn(AccessibilityInfo, 'announceForAccessibility');
    servidor.use(
      http.post(`${BASE}/resgates`, () =>
        HttpResponse.json(
          problema('regra-negocio-violada', 422, 'Saldo de 41 tokens é insuficiente.'),
          { status: 422 },
        ),
      ),
    );

    await render(<TelaBeneficios />);
    await fireEvent.press(await abrirCafe());
    await fireEvent.press(screen.getByRole('button', { name: 'Resgatar por 15 tokens' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Confirmar resgate de 15 tokens' }));

    expect(await screen.findByRole('alert')).toBeTruthy();
    await waitFor(() =>
      expect(anunciar).toHaveBeenCalledWith(expect.stringContaining('Não foi possível resgatar')),
    );

    anunciar.mockRestore();
  });

  it('falha ao carregar o catálogo oferece tentar de novo', async () => {
    servidor.use(
      http.get(`${BASE}/beneficios`, () =>
        HttpResponse.json(problema('erro-interno', 500, 'Falha.'), { status: 500 }),
      ),
    );

    await render(<TelaBeneficios />);

    expect(await screen.findByText('Não deu para carregar o catálogo')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Tentar de novo' })).toBeTruthy();
  });

  it('não fala em dinheiro em lugar nenhum', async () => {
    await render(<TelaBeneficios />);
    await screen.findByRole('button', { name: /café coado/i });

    // ADR 0009 §6: nenhum "R$" na tela. A regra é garantida no servidor em duas camadas; aqui é a
    // checagem de que a UI não a reintroduz por copy própria.
    expect(screen.queryByText(/R\$|\breais\b/i)).toBeNull();
  });
});

/** O cartão do café, achado como um leitor de tela o acharia. */
async function abrirCafe() {
  return screen.findByRole('button', {
    name: `${BENEFICIO_ALCANCAVEL.titulo}, ${BENEFICIO_ALCANCAVEL.parceiroNome}, 15 tokens`,
  });
}
