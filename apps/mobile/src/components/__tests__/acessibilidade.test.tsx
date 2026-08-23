import { AccessibilityInfo, Text } from 'react-native';
import { screen, waitFor } from '@testing-library/react-native';

import { render } from '@/testes/render';

import { Aviso } from '../Aviso';
import { Chip } from '../Chip';
import { Esqueleto } from '../Esqueleto';
import { FolhaInferior } from '../FolhaInferior';
import { SaldoToken } from '../SaldoToken';
import { TituloTela } from '../TituloTela';

jest.mock('expo-router', () => ({
  // Ver o comentário idêntico nos testes de tela: numa árvore de teste não há pilha de navegação, e
  // o que interessa — disparar uma vez na montagem — é o mesmo de um `useEffect`.
  useFocusEffect: (efeito: () => void) => mockUseEffect(efeito, [efeito]),
}));

import { useEffect as mockUseEffect } from 'react';

/**
 * `findNodeHandle` devolve `null` sob o renderizador de teste: não existe nó nativo para o qual
 * apontar. Dublá-lo com um número é o que permite verificar que o COMPONENTE pede o foco — o resto
 * do caminho (RN entregando o handle ao TalkBack) é da plataforma, não deste código.
 */
jest.mock('react-native/Libraries/ReactNative/RendererProxy', () => ({
  ...jest.requireActual('react-native/Libraries/ReactNative/RendererProxy'),
  findNodeHandle: jest.fn(() => 1),
}));

const espiao = AccessibilityInfo as unknown as {
  setAccessibilityFocus: jest.Mock;
  isReduceMotionEnabled: jest.Mock;
};

beforeEach(() => {
  jest.clearAllMocks();
  espiao.isReduceMotionEnabled.mockResolvedValue(false);
});

/**
 * A semântica que o lint NÃO cobra.
 *
 * `has-valid-accessibility-descriptors` garante que todo `Pressable` tenha ALGUMA anotação — e é só
 * isso que uma regra estática consegue afirmar. Que o rótulo diga a coisa certa, que o glifo não
 * entre na fala e que o foco vá para o título são comportamentos, e comportamento se prova com
 * teste. É a mesma divisão do backend: o ArchUnit trava a direção da dependência, o teste de
 * integração prova o que ela faz.
 */
describe('semântica dos componentes', () => {
  describe('TituloTela', () => {
    it('é cabeçalho, para a navegação por títulos do leitor de tela', async () => {
      await render(<TituloTela>Carteira</TituloTela>);

      expect(screen.getByRole('header', { name: 'Carteira' })).toBeTruthy();
    });

    it('puxa o foco ao entrar na rota', async () => {
      // Sem isto, cada navegação joga a pessoa no topo da árvore e ela reatravessa a barra de abas
      // e o cabeçalho para descobrir onde está.
      await render(<TituloTela>Missões</TituloTela>);

      expect(espiao.setAccessibilityFocus).toHaveBeenCalled();
    });

    it('título de SEÇÃO não disputa o foco com o da tela', async () => {
      // Dois títulos puxando foco na mesma navegação dariam uma leitura decidida pela ordem de
      // montagem — ou seja, imprevisível.
      await render(<TituloTela nivel="secao">Extrato</TituloTela>);

      expect(screen.getByRole('header', { name: 'Extrato' })).toBeTruthy();
      expect(espiao.setAccessibilityFocus).not.toHaveBeenCalled();
    });
  });

  describe('Chip', () => {
    it('o glifo é decorativo e NÃO entra no rótulo falado', async () => {
      // O glifo existe para os olhos. Sem `accessibilityLabel` explícito, o rótulo seria derivado
      // dos filhos e o leitor de tela anunciaria "losango preto Entrega".
      await render(<Chip rotulo="Entrega" glifo="◆" onPress={() => {}} />);

      expect(screen.getByRole('button', { name: 'Entrega' })).toBeTruthy();
      expect(screen.queryByLabelText(/◆/)).toBeNull();
    });

    it('anuncia seleção como ESTADO, não como cor', async () => {
      await render(<Chip rotulo="Perto de mim" selecionado onPress={() => {}} />);

      expect(screen.getByRole('button', { name: 'Perto de mim' }).props.accessibilityState).toEqual(
        expect.objectContaining({ selected: true }),
      );
    });

    it('chip decorativo não se apresenta como botão', async () => {
      await render(<Chip rotulo="Aberta" />);

      expect(screen.queryByRole('button')).toBeNull();
      // `getAllBy`: o Pressable e a `<Text>` interna casam os dois com papel `text` — o que importa
      // é que nenhum deles seja botão.
      expect(screen.getAllByRole('text', { name: 'Aberta' }).length).toBeGreaterThan(0);
    });
  });

  describe('Aviso', () => {
    it('diz a severidade em palavra quando não há título', async () => {
      // Seis dos doze usos do app não passam título: neles a severidade chegava ao olho pela cor e
      // ao ouvido por nada.
      await render(<Aviso tom="erro" mensagem="Saldo insuficiente." />);

      expect(screen.getByLabelText('Erro. Saldo insuficiente.')).toBeTruthy();
    });

    it('informativo NÃO se anuncia como alerta', async () => {
      // Quando tudo é alerta, nada é: metade dos avisos do app é informativa.
      await render(<Aviso tom="informacao" mensagem="Nenhum vizinho com esse @." />);

      expect(screen.queryByRole('alert')).toBeNull();
    });

    it('com título, o título fala por si — sem prefixo redundante', async () => {
      await render(<Aviso tom="atencao" titulo="Sem acesso à localização" mensagem="Detalhe." />);

      expect(screen.getByLabelText('Sem acesso à localização. Detalhe.')).toBeTruthy();
    });
  });

  describe('SaldoToken', () => {
    it('leva o sinal para dentro do rótulo, porque "−" sozinho não é pronunciado', async () => {
      await render(<SaldoToken tokens={23} prefixoAcessivel="menos " />);

      expect(screen.getByLabelText('menos 23 tokens')).toBeTruthy();
    });
  });

  describe('Esqueleto', () => {
    it('não pulsa quando o sistema pede movimento reduzido', async () => {
      // O pulso roda em LAÇO enquanto qualquer tela carrega — é movimento persistente, não uma
      // transição que passa, e era a única animação do app que ninguém podia interromper.
      espiao.isReduceMotionEnabled.mockResolvedValue(true);

      await render(<Esqueleto />);

      // `waitFor` porque a preferência é lida de forma ASSÍNCRONA: o componente monta com o valor
      // inicial e assume o valor parado quando a promessa resolve. Na prática isso é um quadro; no
      // teste é a diferença entre 0.4 e 0.7.
      await waitFor(() => {
        // O estilo chega achatado, com a opacidade já resolvida do Animated.Value. 0.7 é o valor
        // PARADO que o componente assume sem movimento — intermediário de propósito, para o
        // esqueleto continuar parecendo espaço reservado, e não conteúdo já carregado.
        const estilo = screen.getByTestId('esqueleto').props.style as { opacity: unknown };
        const opacidade =
          typeof estilo.opacity === 'number'
            ? estilo.opacity
            : (estilo.opacity as { __getValue: () => number }).__getValue();

        expect(opacidade).toBe(0.7);
      });
    });
  });

  describe('FolhaInferior', () => {
    it('o conteúdo ROLA — senão o botão final some da tela com a fonte no máximo', async () => {
      // A folha de transferência tem oito elementos empilhados. Sem rolagem, a 200% de fonte o
      // botão "Transferir" fica abaixo da borda sem gesto que o alcance, e a operação deixa de ser
      // executável por causa de uma preferência de acessibilidade.
      await render(
        <FolhaInferior visivel aoFechar={() => {}} titulo="Transferir" testID="folha">
          <Text>conteúdo</Text>
        </FolhaInferior>,
      );

      expect(screen.getByTestId('folha-rolagem')).toBeTruthy();
    });

    it('o fundo continua fora da árvore de acessibilidade', async () => {
      // Ele é um alvo de toque do tamanho da tela: anunciado, viraria uma região sem nome que a
      // pessoa encontra antes do conteúdo. A saída pelo leitor de tela é o botão "Fechar".
      await render(
        <FolhaInferior visivel aoFechar={() => {}} titulo="Transferir" testID="folha">
          <Text>conteúdo</Text>
        </FolhaInferior>,
      );

      // A RNTL esconde por padrão o que está fora da árvore de acessibilidade — então NÃO achar o
      // fundo é a prova de que ele está escondido. Achá-lo com `includeHiddenElements` mostra que a
      // ausência é intencional, e não um testID errado.
      expect(screen.queryByTestId('folha-fundo')).toBeNull();
      expect(
        screen.getByTestId('folha-fundo', { includeHiddenElements: true }).props
          .importantForAccessibility,
      ).toBe('no');

      // E a saída pelo leitor de tela continua existindo, que é o que justifica esconder o fundo.
      expect(screen.getByRole('button', { name: 'Fechar' })).toBeTruthy();
    });
  });
});
