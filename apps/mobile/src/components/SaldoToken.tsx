import { StyleSheet, Text, View } from 'react-native';
import Svg, { Circle, Path } from 'react-native-svg';

import { cores, espaco, tipografia } from '@/theme';

interface Props {
  tokens: number;
  tamanho?: 'normal' | 'grande';
  cor?: string;
  testID?: string;
}

/**
 * TOKEN não é dinheiro, e a UI precisa dizer isso sem legenda.
 *
 * Por isso: **ícone próprio e número puro**. Nada de "R$", nada de `Intl.NumberFormat` com
 * `style: 'currency'`, nada de duas casas decimais fixas. Token é `bigint` no banco — inteiro, sem
 * centavos — e formatá-lo como moeda sugeriria conversibilidade que o produto não oferece: o
 * resgate é em benefício de parceiro do bairro, não em reais (ADR 0009). O separador de milhar
 * entra porque 1.240 é mais legível que 1240, e isso não é formatação monetária.
 */
export function SaldoToken({ tokens, tamanho = 'normal', cor = cores.verdeEscuro, testID }: Props) {
  const dimensao = tamanho === 'grande' ? 28 : 16;
  const estiloTexto = tamanho === 'grande' ? estilos.grande : estilos.normal;

  return (
    <View style={estilos.linha} testID={testID}>
      <IconeToken tamanho={dimensao} cor={cor} />
      <Text style={[estiloTexto, { color: cor }]} accessibilityLabel={`${tokens} tokens`}>
        {tokens.toLocaleString('pt-BR')}
      </Text>
    </View>
  );
}

export function IconeToken({
  tamanho = 16,
  cor = cores.verdeEscuro,
}: {
  tamanho?: number;
  cor?: string;
}) {
  return (
    <Svg width={tamanho} height={tamanho} viewBox="0 0 24 24" accessibilityRole="image">
      <Circle cx={12} cy={12} r={10} stroke={cor} strokeWidth={2} fill="none" />
      {/* Três nós ligados: a moeda comunitária é uma rede de vizinhos, não um cifrão. */}
      <Path
        d="M12 7.5 8 14.5h8L12 7.5Z"
        stroke={cor}
        strokeWidth={2}
        strokeLinejoin="round"
        fill="none"
      />
    </Svg>
  );
}

const estilos = StyleSheet.create({
  linha: { flexDirection: 'row', alignItems: 'center', gap: espaco.xs },
  normal: { ...tipografia.rotulo },
  grande: { fontSize: 34, lineHeight: 40, fontWeight: '700' },
});
