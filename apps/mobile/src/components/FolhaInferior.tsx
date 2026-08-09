import { useEffect, useState, type ReactNode } from 'react';
import { Animated, Modal, Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

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
  // `useState` com inicializador preguiçoso, e não `useRef(new Animated.Value(0)).current`: ler
  // `.current` durante a renderização é justamente o que a regra `react-hooks/refs` proíbe, e o
  // React Compiler não consegue raciocinar sobre isso. O valor continua sendo criado uma única vez.
  const [progresso] = useState(() => new Animated.Value(0));

  useEffect(() => {
    Animated.timing(progresso, {
      toValue: visivel ? 1 : 0,
      duration: 220,
      useNativeDriver: true,
    }).start();
  }, [visivel, progresso]);

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
              hitSlop={12}
              testID={testID ? `${testID}-fechar` : undefined}
            >
              <Text style={estilos.fechar}>Fechar</Text>
            </Pressable>
          </View>
          {children}
        </Animated.View>
      </View>
    </Modal>
  );
}

const estilos = StyleSheet.create({
  raiz: { flex: 1, justifyContent: 'flex-end' },
  fundo: { ...StyleSheet.absoluteFill, backgroundColor: cores.tinta, opacity: 0.35 },
  folha: {
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
  titulo: { ...tipografia.subtitulo, color: cores.tinta, flexShrink: 1 },
  fechar: { ...tipografia.rotulo, color: cores.verdePrimario },
});
