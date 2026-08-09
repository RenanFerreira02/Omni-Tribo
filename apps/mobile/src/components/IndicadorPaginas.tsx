import { StyleSheet, View } from 'react-native';

import { cores, espaco } from '@/theme';

interface Props {
  total: number;
  atual: number;
  testID?: string;
}

/**
 * Bolinhas do onboarding.
 *
 * O container leva o `accessibilityLabel` com a posição em texto e os pontos são marcados como
 * `none`: para quem usa leitor de tela, "página 2 de 3" é a informação — três elementos decorativos
 * anunciados um a um não são.
 */
export function IndicadorPaginas({ total, atual, testID }: Props) {
  return (
    <View
      testID={testID}
      style={estilos.linha}
      accessible
      accessibilityLabel={`Página ${atual + 1} de ${total}`}
    >
      {Array.from({ length: total }, (_, i) => (
        <View
          key={i}
          accessibilityElementsHidden
          importantForAccessibility="no"
          style={[estilos.ponto, i === atual ? estilos.ativo : estilos.inativo]}
        />
      ))}
    </View>
  );
}

const estilos = StyleSheet.create({
  linha: { flexDirection: 'row', gap: espaco.sm, justifyContent: 'center' },
  ponto: { height: 8, borderRadius: 4 },
  // O ponto ativo é mais LARGO, não só de outra cor: distinguir estado só por cor deixa a
  // informação inacessível para daltonismo e some sob luz forte.
  ativo: { width: 24, backgroundColor: cores.verdePrimario },
  inativo: { width: 8, backgroundColor: cores.linha },
});
