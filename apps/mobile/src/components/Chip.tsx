import { Pressable, StyleSheet, Text } from 'react-native';

import { cores, espaco, raio, tipografia } from '@/theme';

interface Props {
  rotulo: string;
  selecionado?: boolean;
  onPress?: () => void;
  /** Cores próprias — usado pelo chip de categoria, que herda o mapa de `coresCategoria`. */
  corFundo?: string;
  corTexto?: string;
  /**
   * Forma geométrica antes do texto, para a informação não depender de cor.
   *
   * DECORATIVA: é escondida da árvore de acessibilidade abaixo, e o `accessibilityLabel` do chip
   * passa a ser explícito por causa dela. Sem esse cuidado, o leitor de tela anunciaria "losango
   * Entrega" — o glifo é para os olhos, não para o ouvido.
   */
  glifo?: string;
  testID?: string;
}

export function Chip({
  rotulo,
  selecionado = false,
  onPress,
  corFundo,
  corTexto,
  glifo,
  testID,
}: Props) {
  const fundo = selecionado ? cores.verdePrimario : (corFundo ?? cores.branco);
  const texto = selecionado ? cores.branco : (corTexto ?? cores.tinta70);

  return (
    <Pressable
      testID={testID}
      onPress={onPress}
      disabled={onPress === undefined}
      accessibilityRole={onPress ? 'button' : 'text'}
      // EXPLÍCITO por causa do glifo. Sem ele, o rótulo seria derivado dos filhos e passaria a
      // incluir a forma geométrica.
      accessibilityLabel={rotulo}
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
      <Text
        style={[estilos.rotulo, { color: texto }]}
        // 1,6 e não sem teto: a barra de filtros é horizontal e tem cinco chips: acima disso ela
        // quebra em duas linhas e empurra a lista para fora da tela. É a exceção que o CLAUDE.md
        // permite — teto em CONTROLE compacto, nunca em corpo de texto, que é o que precisa crescer.
        maxFontSizeMultiplier={1.6}
      >
        {glifo ? (
          <Text accessibilityElementsHidden importantForAccessibility="no" style={estilos.glifo}>
            {glifo}{' '}
          </Text>
        ) : null}
        {rotulo}
      </Text>
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
  // Sem cor própria: herda a do rótulo, então a forma acompanha a categoria sem acrescentar par
  // texto/fundo nenhum à auditoria de contraste.
  glifo: { ...tipografia.rotulo },
});
