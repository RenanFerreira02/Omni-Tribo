import { StyleSheet, Text, View } from 'react-native';

import { cores, espaco, raio, tipografia } from '@/theme';

export type TomAviso = 'informacao' | 'atencao' | 'erro';

interface Props {
  titulo?: string;
  mensagem: string;
  tom?: TomAviso;
  testID?: string;
}

/**
 * Faixa de aviso in-place: permissão negada, prévia de recompensa indisponível, orientação de
 * check-in recusado.
 *
 * `accessibilityLiveRegion="polite"` é o ponto deste componente. Antes, um erro que aparecia depois
 * de um toque — o caso mais comum do app — surgia em SILÊNCIO para quem usa leitor de tela: o foco
 * continuava no botão e nada anunciava que a ação havia falhado. "Polite" e não "assertive" porque
 * o aviso não deve cortar a leitura em curso; ele entra na próxima pausa.
 */
export function Aviso({ titulo, mensagem, tom = 'informacao', testID }: Props) {
  const paleta = PALETA[tom];

  return (
    <View
      testID={testID}
      accessible
      accessibilityLiveRegion="polite"
      accessibilityRole="alert"
      style={[estilos.caixa, { backgroundColor: paleta.fundo, borderColor: paleta.borda }]}
    >
      {titulo ? <Text style={[estilos.titulo, { color: paleta.texto }]}>{titulo}</Text> : null}
      <Text style={[estilos.mensagem, { color: paleta.texto }]}>{mensagem}</Text>
    </View>
  );
}

const PALETA: Record<TomAviso, { fundo: string; borda: string; texto: string }> = {
  informacao: { fundo: cores.verdeClaro, borda: cores.verdePrimario, texto: cores.verdeEscuro },
  atencao: { fundo: cores.ambarClaro, borda: cores.ambar, texto: cores.ambar },
  erro: { fundo: cores.coralClaro, borda: cores.coral, texto: cores.coral },
};

const estilos = StyleSheet.create({
  caixa: {
    padding: espaco.md,
    borderRadius: raio.md,
    borderWidth: 1,
    gap: espaco.xs,
  },
  titulo: { ...tipografia.rotulo },
  mensagem: { ...tipografia.corpo },
});
