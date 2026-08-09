import { StyleSheet, View } from 'react-native';

import { cores, raio } from '@/theme';

interface Props {
  /** Quanto já se tem. Valores acima de `meta` são saturados — ver comentário abaixo. */
  valor: number;
  meta: number;
  cor?: string;
  altura?: number;
  /** Frase para o leitor de tela. Sem ela a barra é invisível para quem não vê a barra. */
  rotuloAcessivel: string;
  testID?: string;
}

/**
 * Barra de progresso para XP até o próximo nível e para conquistas.
 *
 * Satura em 100% mesmo se `valor > meta`. O servidor já satura o progresso das conquistas, mas o XP
 * do nível não: quem tem 8100 de XP no nível 10 passa de `xpProximoNivel` no instante em que sobe, e
 * uma largura sem teto renderizaria uma barra maior que o próprio contêiner.
 *
 * `meta` zero ou negativa vira barra cheia em vez de divisão por zero — "meta sem tamanho" só pode
 * significar já alcançada.
 */
export function BarraProgresso({
  valor,
  meta,
  cor = cores.verdePrimario,
  altura = 8,
  rotuloAcessivel,
  testID,
}: Props) {
  const fracao = meta <= 0 ? 1 : Math.min(Math.max(valor / meta, 0), 1);

  return (
    <View
      testID={testID}
      accessibilityRole="progressbar"
      accessibilityLabel={rotuloAcessivel}
      accessibilityValue={{ min: 0, max: meta, now: Math.min(valor, meta) }}
      style={[estilos.trilho, { height: altura, borderRadius: altura / 2 }]}
    >
      <View
        testID={testID ? `${testID}-preenchimento` : undefined}
        style={[
          estilos.preenchimento,
          { width: `${fracao * 100}%`, backgroundColor: cor, borderRadius: altura / 2 },
        ]}
      />
    </View>
  );
}

const estilos = StyleSheet.create({
  trilho: { width: '100%', backgroundColor: cores.linha, borderRadius: raio.pilula },
  preenchimento: { height: '100%' },
});
