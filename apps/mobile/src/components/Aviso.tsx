import { StyleSheet, Text, View } from 'react-native';

import { cores, espaco, raio, textoAcessivel, tipografia } from '@/theme';

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

/**
 * PREENCHIMENTO usa `cores`; TEXTO usa `textoAcessivel`. É a regra do `CLAUDE.md`, e este componente
 * era a violação mais cara dela.
 *
 * `cores.ambar` dá 3,36:1 e `cores.coral` 3,20:1 sobre os fundos claros — abaixo dos 4,5:1 do WCAG
 * AA. E o `Aviso` é por onde passa TODO erro do app: check-in recusado, transferência negada,
 * exclusão de conta, criação de missão. A informação mais crítica da interface estava no pior
 * contraste dela.
 *
 * O lint só proíbe HEX literal, então `cores.coral` passava limpo — a regra que o `CLAUDE.md` diz
 * ser "aplicada por lint, não por disciplina" cobria metade do problema, e as duas violações que
 * existiam estavam justamente na metade descoberta. Ver a regra nova em `eslint.config.js`.
 *
 * `textoAcessivel` preserva o matiz e só escurece até o limiar: o aviso continua âmbar e o erro
 * continua coral, agora legíveis.
 */
const PALETA: Record<TomAviso, { fundo: string; borda: string; texto: string }> = {
  informacao: { fundo: cores.verdeClaro, borda: cores.verdePrimario, texto: cores.verdeEscuro },
  atencao: { fundo: cores.ambarClaro, borda: cores.ambar, texto: textoAcessivel.ambar },
  erro: { fundo: cores.coralClaro, borda: cores.coral, texto: textoAcessivel.coral },
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
