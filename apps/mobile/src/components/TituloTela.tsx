import { useFocusEffect } from 'expo-router';
import { useCallback, useRef } from 'react';
import { AccessibilityInfo, StyleSheet, Text, findNodeHandle, type TextProps } from 'react-native';

import { cores, tipografia } from '@/theme';

interface Props extends TextProps {
  children: string;
  /** `titulo` (padrão) para a tela; `secao` para os subtítulos dentro dela. */
  nivel?: 'titulo' | 'secao';
  /**
   * Move o foco do leitor de tela para cá quando a rota ganha foco.
   *
   * Ligado só no título da TELA. Duas seções competindo pelo foco na mesma navegação produziriam
   * uma leitura imprevisível, decidida pela ordem de montagem.
   */
  focarAoEntrar?: boolean;
}

/**
 * O cabeçalho de uma tela ou de uma seção — e o ponto para onde o foco vai ao navegar.
 *
 * **Duas coisas que estavam faltando, e são a mesma peça.**
 *
 * A primeira: sem gestão de foco, cada navegação do Expo Router joga o leitor de tela no topo da
 * árvore, e a pessoa reatravessa a barra de abas e o cabeçalho a cada toque. `setAccessibilityFocus`
 * põe o foco no título, que é o que responde "onde eu estou agora".
 *
 * A segunda: sete títulos de seção do app eram `<Text>` sem `accessibilityRole="header"`, então a
 * navegação por cabeçalhos do TalkBack — o gesto que permite pular de seção em seção em vez de
 * varrer elemento por elemento — enxergava só o topo. Concentrar o papel aqui é o que impede o
 * próximo título de nascer sem ele.
 *
 * `useFocusEffect` e não `useEffect`: numa pilha, a tela anterior continua MONTADA embaixo. Com
 * `useEffect` o foco iria para o título certo só na primeira entrada e nunca no retorno.
 *
 * `findNodeHandle` é o caminho suportado para obter o `reactTag` que `setAccessibilityFocus` exige;
 * ele devolve `null` quando o nó ainda não foi anexado, e por isso a chamada é condicional — em
 * teste, e na web, esse é o caso normal.
 */
export function TituloTela({
  children,
  nivel = 'titulo',
  focarAoEntrar = nivel === 'titulo',
  style,
  ...resto
}: Props) {
  const referencia = useRef<Text>(null);

  useFocusEffect(
    useCallback(() => {
      if (!focarAoEntrar) return;
      const no = findNodeHandle(referencia.current);
      if (no !== null) {
        AccessibilityInfo.setAccessibilityFocus(no);
      }
    }, [focarAoEntrar]),
  );

  return (
    <Text
      ref={referencia}
      accessibilityRole="header"
      style={[nivel === 'titulo' ? estilos.titulo : estilos.secao, style]}
      {...resto}
    >
      {children}
    </Text>
  );
}

const estilos = StyleSheet.create({
  titulo: { ...tipografia.titulo, color: cores.tinta },
  secao: { ...tipografia.subtitulo, color: cores.tinta },
});
