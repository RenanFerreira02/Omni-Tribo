import { StyleSheet, Text, View } from 'react-native';

import { Botao } from './Botao';
import { cores, espaco, tipografia } from '@/theme';

interface Props {
  titulo: string;
  descricao?: string;
  acao?: { rotulo: string; onPress: () => void };
  testID?: string;
}

export function EstadoVazio({ titulo, descricao, acao, testID }: Props) {
  return (
    <View style={estilos.caixa} testID={testID}>
      {/* Cabeçalho: é o que a navegação por títulos do TalkBack usa para pular direto ao estado
          vazio em vez de varrer a lista inteira para descobrir que ela está vazia. */}
      <Text style={estilos.titulo} accessibilityRole="header">
        {titulo}
      </Text>
      {descricao ? <Text style={estilos.descricao}>{descricao}</Text> : null}
      {acao ? (
        <Botao
          titulo={acao.rotulo}
          onPress={acao.onPress}
          variante="secundario"
          estilo={estilos.acao}
        />
      ) : null}
    </View>
  );
}

const estilos = StyleSheet.create({
  caixa: {
    alignItems: 'center',
    gap: espaco.sm,
    paddingVertical: espaco.xxl,
    paddingHorizontal: espaco.lg,
  },
  titulo: { ...tipografia.subtitulo, color: cores.tinta, textAlign: 'center' },
  descricao: { ...tipografia.corpo, color: cores.tinta70, textAlign: 'center' },
  acao: { marginTop: espaco.sm, minWidth: 180 },
});
