import { useRouter } from 'expo-router';
import { useState } from 'react';
import {
  Dimensions,
  ScrollView,
  StyleSheet,
  Text,
  View,
  type NativeScrollEvent,
  type NativeSyntheticEvent,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { Botao } from '@/components/Botao';
import { IndicadorPaginas } from '@/components/IndicadorPaginas';
import { marcarOnboardingVisto } from '@/features/onboarding/visto';
import { alvo, cores, espaco, glifo, tipografia } from '@/theme';

interface Slide {
  chave: string;
  emoji: string;
  titulo: string;
  texto: string;
}

/**
 * Três slides, e o conteúdo é a tese do produto — não um tour de funcionalidades.
 *
 * O terceiro é o que mais importa e o que o app inteiro precisa deixar claro desde o primeiro
 * segundo: **aqui não circula dinheiro entre vizinhos**. Alguém que instala esperando ganhar em
 * reais vai abandonar o app na primeira missão, e pior, vai se sentir enganado. Ver ADR 0009.
 */
const SLIDES: Slide[] = [
  {
    chave: 'perto',
    emoji: '📍',
    titulo: 'Missões no seu bairro',
    texto:
      'Entregas que falharam, coletas de recicláveis, mutirões e pedidos de ajuda — tudo a poucos quarteirões de você.',
  },
  {
    chave: 'checkin',
    emoji: '✅',
    titulo: 'Check-in no local',
    texto:
      'Você aceita, vai até lá e confirma a presença pelo GPS. Quem criou a missão confirma a conclusão, e só então a recompensa é creditada.',
  },
  {
    chave: 'economia',
    emoji: '🤝',
    titulo: 'XP e tokens, não dinheiro',
    texto:
      'Quem cria a missão não paga. Você ganha experiência e tokens da comunidade, resgatáveis em benefícios de parceiros do bairro.',
  },
];

export default function Onboarding() {
  const router = useRouter();
  const [atual, setAtual] = useState(0);
  const largura = Dimensions.get('window').width;

  async function concluir() {
    await marcarOnboardingVisto();
    router.replace('/(auth)/login');
  }

  function aoRolar(evento: NativeSyntheticEvent<NativeScrollEvent>) {
    const indice = Math.round(evento.nativeEvent.contentOffset.x / largura);
    if (indice !== atual) setAtual(indice);
  }

  const ultimo = atual === SLIDES.length - 1;

  return (
    <SafeAreaView style={estilos.raiz} testID="tela-onboarding">
      <View style={estilos.topo}>
        {/* "Pular" some no último slide: ali o botão primário já faz a mesma coisa, e dois
            caminhos para a mesma ação lado a lado só criam dúvida. */}
        {!ultimo ? (
          <Botao titulo="Pular" variante="texto" onPress={concluir} testID="botao-pular" />
        ) : (
          <View />
        )}
      </View>

      <ScrollView
        horizontal
        pagingEnabled
        showsHorizontalScrollIndicator={false}
        onMomentumScrollEnd={aoRolar}
        testID="slides-onboarding"
      >
        {SLIDES.map((slide) => (
          <View key={slide.chave} style={[estilos.slide, { width: largura }]}>
            <Text style={estilos.emoji} accessibilityElementsHidden importantForAccessibility="no">
              {slide.emoji}
            </Text>
            <Text style={estilos.titulo} accessibilityRole="header">
              {slide.titulo}
            </Text>
            <Text style={estilos.texto}>{slide.texto}</Text>
          </View>
        ))}
      </ScrollView>

      <View style={estilos.rodape}>
        <IndicadorPaginas total={SLIDES.length} atual={atual} testID="indicador-paginas" />
        <Botao
          titulo={ultimo ? 'Começar' : 'Entendi'}
          onPress={ultimo ? concluir : () => setAtual((i) => Math.min(i + 1, SLIDES.length - 1))}
          testID="botao-avancar"
        />
      </View>
    </SafeAreaView>
  );
}

const estilos = StyleSheet.create({
  raiz: { flex: 1, backgroundColor: cores.branco },
  topo: { minHeight: alvo.confortavel, paddingHorizontal: espaco.lg, alignItems: 'flex-end' },
  slide: {
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: espaco.xl,
    gap: espaco.md,
  },
  emoji: { fontSize: glifo.ilustracao },
  titulo: { ...tipografia.titulo, color: cores.tinta, textAlign: 'center' },
  texto: { ...tipografia.corpo, color: cores.tinta70, textAlign: 'center' },
  rodape: { padding: espaco.lg, gap: espaco.lg },
});
