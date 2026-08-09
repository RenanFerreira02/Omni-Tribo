import { useEffect, useState } from 'react';
import { Animated, Easing, StyleSheet, View, type DimensionValue } from 'react-native';

import { Card } from './Card';
import { cores, espaco, raio } from '@/theme';

interface Props {
  largura?: DimensionValue;
  altura?: number;
}

/**
 * Usa o `Animated` do React Native, e não o Reanimated.
 *
 * Reanimated 4 roda sobre `react-native-worklets`, que precisa do TurboModule nativo já no import —
 * em jest isso estoura com `Cannot read properties of undefined (reading 'loadUnpackers')` e derruba
 * a suíte inteira de qualquer tela que carregue um esqueleto. Montar o mock do runtime de worklets
 * seria um preço alto por um pulso de opacidade: o `Animated` com `useNativeDriver` entrega o mesmo
 * efeito, na thread de UI, sem nada disso. Reanimated continua no projeto — o Expo Router o usa nas
 * transições de tela —, só não entra no caminho dos nossos componentes.
 */
export function Esqueleto({ largura = '100%', altura = 14 }: Props) {
  // `useState` com inicializador preguiçoso, e não `useRef(...).current`: o valor precisa ser criado
  // uma vez só, e ler `.current` durante a renderização é justamente o que a regra `react-hooks/refs`
  // proíbe. O setter nunca é usado — o que muda é o interior do Animated.Value, não a referência.
  const [opacidade] = useState(() => new Animated.Value(0.4));

  useEffect(() => {
    const laco = Animated.loop(
      Animated.sequence([
        Animated.timing(opacidade, {
          toValue: 1,
          duration: 800,
          easing: Easing.inOut(Easing.quad),
          useNativeDriver: true,
        }),
        Animated.timing(opacidade, {
          toValue: 0.4,
          duration: 800,
          easing: Easing.inOut(Easing.quad),
          useNativeDriver: true,
        }),
      ]),
    );
    laco.start();
    // Parar no unmount: laço infinito sobrevivente mantém o timer vivo depois de a tela sair.
    return () => laco.stop();
  }, [opacidade]);

  return (
    <Animated.View
      style={[estilos.barra, { width: largura, height: altura, opacity: opacidade }]}
    />
  );
}

/** Esqueleto com a forma de um MissaoCard — a tela não "pula" quando o dado chega. */
export function EsqueletoMissaoCard() {
  return (
    <Card>
      <Esqueleto largura="40%" altura={12} />
      <Esqueleto largura="85%" altura={18} />
      <Esqueleto largura="60%" altura={12} />
      <View style={estilos.rodape}>
        <Esqueleto largura={64} altura={14} />
        <Esqueleto largura={64} altura={14} />
      </View>
    </Card>
  );
}

const estilos = StyleSheet.create({
  barra: { backgroundColor: cores.linha, borderRadius: raio.sm },
  rodape: { flexDirection: 'row', gap: espaco.md },
});
