import {
  ActivityIndicator,
  Pressable,
  StyleSheet,
  Text,
  View,
  type StyleProp,
  type ViewStyle,
} from 'react-native';

import { cores, espaco, raio, tipografia } from '@/theme';

export type VarianteBotao = 'primario' | 'secundario' | 'texto';

interface Props {
  titulo: string;
  onPress: () => void;
  variante?: VarianteBotao;
  carregando?: boolean;
  disabled?: boolean;
  estilo?: StyleProp<ViewStyle>;
  testID?: string;
}

/**
 * `carregando` implica desabilitado: um botão que mostra spinner e continua clicável é o caminho
 * mais curto para duas requisições de valor com a mesma intenção — dois aceites, dois check-ins.
 * A chave de idempotência protege o servidor, mas o app não deve nem chegar lá.
 */
export function Botao({
  titulo,
  onPress,
  variante = 'primario',
  carregando = false,
  disabled = false,
  estilo,
  testID,
}: Props) {
  const inativo = disabled || carregando;
  const visual = VISUAIS[variante];

  return (
    <Pressable
      testID={testID}
      onPress={onPress}
      disabled={inativo}
      accessibilityRole="button"
      accessibilityState={{ disabled: inativo, busy: carregando }}
      accessibilityLabel={titulo}
      style={({ pressed }) => [
        estilos.base,
        visual.container,
        pressed && !inativo && estilos.pressionado,
        inativo && estilos.inativo,
        estilo,
      ]}
    >
      <View style={estilos.conteudo}>
        {carregando ? (
          <ActivityIndicator size="small" color={visual.texto.color} testID="botao-carregando" />
        ) : (
          <Text style={[estilos.rotulo, visual.texto]}>{titulo}</Text>
        )}
      </View>
    </Pressable>
  );
}

const estilos = StyleSheet.create({
  base: {
    minHeight: 48,
    borderRadius: raio.md,
    paddingHorizontal: espaco.lg,
    justifyContent: 'center',
  },
  conteudo: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: espaco.sm,
  },
  rotulo: { ...tipografia.subtitulo },
  pressionado: { opacity: 0.85 },
  inativo: { opacity: 0.45 },
});

const VISUAIS: Record<VarianteBotao, { container: ViewStyle; texto: { color: string } }> = {
  primario: {
    container: { backgroundColor: cores.verdeEscuro },
    texto: { color: cores.branco },
  },
  secundario: {
    container: {
      backgroundColor: cores.branco,
      borderWidth: 1,
      borderColor: cores.verdeEscuro,
    },
    texto: { color: cores.verdeEscuro },
  },
  texto: {
    // 44, e não 40. É o mínimo recomendado para alvo de toque, e esta variante é usada em ações
    // reais — "Desistir" no detalhe da missão —, não em decoração. Um alvo pequeno numa ação
    // irreversível é a pior combinação possível: erra-se o toque, ou acerta-se sem querer.
    container: { backgroundColor: cores.transparente, minHeight: 44 },
    texto: { color: cores.verdeEscuro },
  },
};
