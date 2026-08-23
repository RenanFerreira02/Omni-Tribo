import { Pressable, StyleSheet, Text, View } from 'react-native';

import { Card } from './Card';
import type { PontoCustodiaResponse } from '@/api/tipos';
import { formatarDistancia } from '@/lib/formatar';
import { cores, espaco, textoAcessivel, tipografia } from '@/theme';

interface Props {
  ponto: PontoCustodiaResponse;
  onPress: () => void;
  testID?: string;
}

const ROTULO_TIPO: Record<PontoCustodiaResponse['tipo'], string> = {
  LOJA: 'loja',
  LOCKER: 'armário',
  PORTARIA: 'portaria',
  VIZINHO: 'vizinho',
};

/**
 * Um ponto de custódia na lista do radar.
 *
 * <b>Este componente existe porque o ponto de custódia era inalcançável.</b> Ele só aparecia como um
 * quadrado dentro da WebView do Leaflet, e a única forma de abri-lo era tocar nesse quadrado — o que
 * exclui quem usa leitor de tela e quem não consegue mirar um alvo pequeno. Era o achado A2 do
 * inventário de acessibilidade, classificado como BLOQUEIA, e nenhuma outra tela do app expõe esses
 * dados.
 *
 * <b>Não reusa `MissaoCard`, e a assimetria é a decisão.</b> Ponto de custódia não tem recompensa
 * nem prazo; encaixá-lo na forma da missão exigiria campos vazios, e um rótulo que diz "0 XP e 0
 * tokens, encerrada" para um armário do bairro é pior que dois componentes.
 *
 * A OCUPAÇÃO entra no rótulo porque é o que decide se vale ir: um ponto lotado recusa a encomenda —
 * é literalmente um dos três desfechos do webhook de entrega falida (ADR 0021).
 */
export function ItemPontoCustodia({ ponto, onPress, testID }: Props) {
  const vagas = Math.max(ponto.capacidade - ponto.ocupacao, 0);
  const distancia = ponto.distanciaM !== null ? formatarDistancia(ponto.distanciaM) : null;

  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      // Agrupa os filhos num nó só, pelo mesmo motivo do `MissaoCard`: lidos soltos, virariam cinco
      // paradas para entender um item de lista.
      accessible
      accessibilityLabel={rotuloAcessivel(ponto, vagas, distancia)}
      accessibilityHint="Abre os detalhes do ponto."
      testID={testID}
    >
      <Card estilo={estilos.cartao}>
        <View style={estilos.topo}>
          <Text style={estilos.apelido} numberOfLines={2}>
            {ponto.apelido}
          </Text>
          {distancia ? <Text style={estilos.distancia}>{distancia}</Text> : null}
        </View>
        <Text style={estilos.legenda}>
          {ROTULO_TIPO[ponto.tipo]} · código {ponto.codigo}
        </Text>
        <Text style={vagas === 0 ? estilos.lotado : estilos.legenda}>
          {vagas === 0 ? 'Sem vaga no momento' : `${vagas} de ${ponto.capacidade} vagas livres`}
        </Text>
      </Card>
    </Pressable>
  );
}

/** Mesma disciplina do `MissaoCard`: o que decide vem primeiro, o nome depois. */
function rotuloAcessivel(
  ponto: PontoCustodiaResponse,
  vagas: number,
  distancia: string | null,
): string {
  const partes = [`Ponto de custódia, ${ROTULO_TIPO[ponto.tipo]}`];
  if (distancia) partes.push(`a ${distancia}`);
  partes.push(vagas === 0 ? 'sem vaga no momento' : `${vagas} de ${ponto.capacidade} vagas livres`);
  partes.push(ponto.apelido);
  return partes.join(', ') + '.';
}

const estilos = StyleSheet.create({
  cartao: { gap: espaco.xs },
  topo: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  apelido: { ...tipografia.subtitulo, color: cores.tinta, flexShrink: 1 },
  distancia: { ...tipografia.rotulo, color: textoAcessivel.suave },
  legenda: { ...tipografia.legenda, color: textoAcessivel.suave },
  // Sem vaga é informação de DECISÃO, não decoração: o coral já é o token de texto de alerta do app.
  lotado: { ...tipografia.legenda, color: textoAcessivel.coral },
});
