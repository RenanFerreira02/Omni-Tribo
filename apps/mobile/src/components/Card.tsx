import { StyleSheet, View, type StyleProp, type ViewProps, type ViewStyle } from 'react-native';

import { cores, espaco, raio } from '@/theme';

interface Props extends ViewProps {
  estilo?: StyleProp<ViewStyle>;
}

export function Card({ children, estilo, ...resto }: Props) {
  return (
    <View style={[estilos.card, estilo]} {...resto}>
      {children}
    </View>
  );
}

const estilos = StyleSheet.create({
  card: {
    backgroundColor: cores.branco,
    borderRadius: raio.lg,
    borderWidth: 1,
    borderColor: cores.linha,
    padding: espaco.lg,
    gap: espaco.md,
  },
});
