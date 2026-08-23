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
      // O PAPEL acompanha o tom, em vez de ser `alert` sempre.
      //
      // Metade dos avisos do app é informativa — "nenhum vizinho com esse @", "mostrando o mapa na
      // sua tribo" — e anunciá-los com a urgência de um erro gasta a única sinalização de urgência
      // que existe. Quando tudo é alerta, nada é.
      accessibilityRole={tom === 'informacao' ? 'text' : 'alert'}
      // A SEVERIDADE dita em palavra, porque na tela ela é só o matiz do fundo.
      //
      // Seis dos doze usos do app não passam `titulo`: neles, "erro" e "informação" chegavam ao
      // olho pela cor e ao ouvido por nada. O prefixo só entra quando não há título — quando há, ele
      // já diz do que se trata ("Sem acesso à localização", "Premissa, não medição").
      accessibilityLabel={`${titulo ? '' : PREFIXO_FALADO[tom]}${titulo ? `${titulo}. ` : ''}${mensagem}`}
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
/** Só para a fala. Na tela a severidade continua vindo da cor e do título. */
const PREFIXO_FALADO: Record<TomAviso, string> = {
  informacao: '',
  atencao: 'Atenção. ',
  erro: 'Erro. ',
};

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
