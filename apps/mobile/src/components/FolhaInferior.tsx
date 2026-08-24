import { useEffect, useState, type ReactNode } from 'react';
import { Animated, Modal, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { useMovimentoReduzido } from '@/lib/movimento';
import { cores, espaco, raio, tipografia } from '@/theme';

interface Props {
  visivel: boolean;
  aoFechar: () => void;
  titulo?: string;
  children: ReactNode;
  testID?: string;
}

/**
 * Painel que sobe do rodapé: resumo do marcador no mapa, formulário de transferência.
 *
 * **Por que não `@gorhom/bottom-sheet`.** Ele depende de Reanimated, e Reanimated está banido dos
 * nossos componentes — `Esqueleto.tsx` registra o motivo: sob o jest-expo ele quebra a suíte com
 * `loadUnpackers`, derrubando o teste de QUALQUER tela que importe o componente, mesmo sem animar
 * nada. Uma dependência que custa a suíte inteira não se paga por uma animação de 250 ms.
 *
 * `Animated` do core do React Native faz o mesmo aqui: um `translateY` e um fade, com
 * `useNativeDriver`. Não há gesto de arrastar para fechar — fecha-se pelo fundo ou pelo botão —, e
 * essa é a simplificação assumida.
 */
export function FolhaInferior({ visivel, aoFechar, titulo, children, testID }: Props) {
  const insets = useSafeAreaInsets();
  const movimentoReduzido = useMovimentoReduzido();
  // `useState` com inicializador preguiçoso, e não `useRef(new Animated.Value(0)).current`: ler
  // `.current` durante a renderização é justamente o que a regra `react-hooks/refs` proíbe, e o
  // React Compiler não consegue raciocinar sobre isso. O valor continua sendo criado uma única vez.
  const [progresso] = useState(() => new Animated.Value(0));

  useEffect(() => {
    Animated.timing(progresso, {
      toValue: visivel ? 1 : 0,
      // Duração ZERO em vez de pular o Animated: o valor precisa chegar ao destino de qualquer
      // forma, senão a folha nunca sai do deslocamento inicial e fica invisível.
      duration: movimentoReduzido ? 0 : 220,
      useNativeDriver: true,
    }).start();
  }, [visivel, progresso, movimentoReduzido]);

  const deslocamento = progresso.interpolate({ inputRange: [0, 1], outputRange: [420, 0] });

  return (
    <Modal
      visible={visivel}
      transparent
      animationType="none"
      // Botão físico de voltar no Android. Sem isto, a folha fica presa e o gesto de voltar sai da
      // tela inteira em vez de fechar o painel.
      onRequestClose={aoFechar}
      testID={testID}
    >
      <View style={estilos.raiz}>
        {/* O fundo é um alvo de toque enorme e não deve ser anunciado como botão: para o leitor de
            tela, a saída é o cabeçalho da folha, não uma região sem nome que ocupa a tela toda. */}
        <Pressable
          style={estilos.fundo}
          onPress={aoFechar}
          // `role="none"` e NÃO um `eslint-disable`: o gate de acessibilidade pede papel, rótulo ou
          // ação em todo pressável, e a resposta honesta aqui é declarar que este não tem papel
          // nenhum. As duas props abaixo já o escondiam da árvore; o que faltava era dizer isso
          // numa prop que o lint entende. Silenciar a regra deixaria o próximo `Pressable` sem
          // rótulo passar junto.
          accessibilityRole="none"
          accessibilityElementsHidden
          importantForAccessibility="no"
          testID={testID ? `${testID}-fundo` : undefined}
        />
        <Animated.View
          style={[
            estilos.folha,
            { paddingBottom: insets.bottom + espaco.lg, transform: [{ translateY: deslocamento }] },
          ]}
        >
          <View style={estilos.puxador} />
          <View style={estilos.cabecalho}>
            {titulo ? (
              <Text style={estilos.titulo} accessibilityRole="header">
                {titulo}
              </Text>
            ) : (
              <View />
            )}
            <Pressable
              onPress={aoFechar}
              accessibilityRole="button"
              accessibilityLabel="Fechar"
              // 13, e não 12. O texto tem 18 de lineHeight: com 12 o alvo dava 42 pt, abaixo dos 44
              // que a WCAG 2.5.5 pede — era o achado L4 da auditoria mobile, aberto desde então.
              hitSlop={13}
              testID={testID ? `${testID}-fechar` : undefined}
            >
              <Text style={estilos.fechar}>Fechar</Text>
            </Pressable>
          </View>
          {/*
            ROLAGEM, e é o conserto mais importante deste arquivo.

            O conteúdo era injetado direto, sem altura máxima e sem rolagem. Com a fonte do sistema
            no máximo (200%), a folha de transferência — texto, campo de @, botão de busca, cartão de
            confirmação, quantidade, mensagem, aviso e botão final — passa da altura da tela, e o
            botão "Transferir" fica abaixo da borda SEM GESTO QUE O ALCANCE. A operação deixava de
            ser executável por causa de uma preferência de acessibilidade.

            `keyboardShouldPersistTaps="handled"` porque toda folha com campo de texto tem um botão
            logo abaixo dele: sem isto, o primeiro toque só fecha o teclado e o segundo é que aciona.
          */}
          <ScrollView
            contentContainerStyle={estilos.conteudo}
            keyboardShouldPersistTaps="handled"
            testID={testID ? `${testID}-rolagem` : undefined}
          >
            {children}
          </ScrollView>
        </Animated.View>
      </View>
    </Modal>
  );
}

const estilos = StyleSheet.create({
  raiz: { flex: 1, justifyContent: 'flex-end' },
  fundo: { ...StyleSheet.absoluteFill, backgroundColor: cores.tinta, opacity: 0.35 },
  folha: {
    // Sem o teto, a `Animated.View` cresce com o conteúdo e o ScrollView nunca rola — ele só rola
    // quando o pai tem altura menor que o conteúdo. 85% deixa o fundo visível, que é o alvo de
    // toque para fechar.
    maxHeight: '85%',
    backgroundColor: cores.branco,
    borderTopLeftRadius: raio.lg,
    borderTopRightRadius: raio.lg,
    paddingHorizontal: espaco.lg,
    paddingTop: espaco.md,
    gap: espaco.md,
  },
  puxador: {
    width: 40,
    height: 4,
    borderRadius: raio.pilula,
    backgroundColor: cores.linha,
    alignSelf: 'center',
  },
  cabecalho: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  // O `gap` saiu de `folha` e veio para cá junto com o conteúdo: aplicado no contêiner do
  // ScrollView, ele continua separando os filhos; deixado lá, separaria cabeçalho e rolagem.
  conteudo: { gap: espaco.md, paddingBottom: espaco.md },
  titulo: { ...tipografia.subtitulo, color: cores.tinta, flexShrink: 1 },
  fechar: { ...tipografia.rotulo, color: cores.verdeEscuro },
});
