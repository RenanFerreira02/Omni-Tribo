import { Pressable, StyleSheet, Text } from 'react-native';

import { cores, espaco, raio, tipografia } from '@/theme';

interface Props {
  rotulo: string;
  selecionado?: boolean;
  onPress?: () => void;
  /** Cores próprias — usado pelo chip de categoria, que herda o mapa de `coresCategoria`. */
  corFundo?: string;
  corTexto?: string;
  testID?: string;
}

export function Chip({ rotulo, selecionado = false, onPress, corFundo, corTexto, testID }: Props) {
  const fundo = selecionado ? cores.verdePrimario : (corFundo ?? cores.branco);
  const texto = selecionado ? cores.branco : (corTexto ?? cores.tinta70);

  return (
    <Pressable
      testID={testID}
      onPress={onPress}
      disabled={onPress === undefined}
      accessibilityRole={onPress ? 'button' : 'text'}
      accessibilityState={onPress ? { selected: selecionado } : undefined}
      // O chip mede ~34 pt de altura e é o controle MAIS tocado do app (filtros da lista de
      // missões). Aumentar o padding engordaria a barra de filtros inteira; o hitSlop amplia só a
      // área sensível, sem mexer no layout. Só quando é botão — um chip decorativo não precisa de
      // alvo.
      hitSlop={onPress ? { top: 6, bottom: 6, left: 4, right: 4 } : undefined}
      style={[
        estilos.chip,
        { backgroundColor: fundo, borderColor: selecionado ? cores.verdePrimario : cores.linha },
      ]}
    >
      <Text style={[estilos.rotulo, { color: texto }]}>{rotulo}</Text>
    </Pressable>
  );
}

const estilos = StyleSheet.create({
  chip: {
    paddingHorizontal: espaco.md,
    paddingVertical: espaco.sm,
    borderRadius: raio.pilula,
    borderWidth: 1,
  },
  rotulo: { ...tipografia.rotulo },
});
