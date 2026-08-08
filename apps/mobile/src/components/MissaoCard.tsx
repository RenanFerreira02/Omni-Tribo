import { Pressable, StyleSheet, Text, View } from 'react-native';

import { Card } from './Card';
import { Chip } from './Chip';
import { SaldoToken } from './SaldoToken';
import type { MissaoResponse } from '@/api/tipos';
import { formatarDistancia, rotuloCategoria } from '@/lib/formatar';
import { cores, coresCategoria, espaco, tipografia } from '@/theme';

interface Props {
  missao: MissaoResponse;
  /** Metros medidos pelo PostGIS. Só existe no radar — `GET /missoes` não traz distância. */
  distanciaM?: number;
  onPress?: () => void;
  testID?: string;
}

/**
 * RECOMPENSA: XP e TOKEN, nunca reais.
 *
 * `missao.valorBrl` existe no DTO e chega sempre 0 — `ck_missao_economia` (V15) o exige. Ele NÃO é
 * lido aqui de propósito: este é um projeto de economia do cuidado, e não circula dinheiro entre
 * vizinhos (ADR 0009). Exibir um "R$ 0,00" seria pior que não exibir nada, porque sugeriria que um
 * dia haverá outro número ali.
 */
export function MissaoCard({ missao, distanciaM, onPress, testID }: Props) {
  const paleta = coresCategoria[missao.categoria];

  return (
    <Pressable onPress={onPress} disabled={!onPress} testID={testID} accessibilityRole="button">
      <Card>
        <View style={estilos.topo}>
          <Chip
            rotulo={rotuloCategoria(missao.categoria)}
            corFundo={paleta.fundo}
            corTexto={paleta.texto}
          />
          {distanciaM !== undefined ? (
            <Text style={estilos.distancia}>{formatarDistancia(distanciaM)}</Text>
          ) : null}
        </View>

        <Text style={estilos.titulo} numberOfLines={2}>
          {missao.titulo}
        </Text>

        <Text style={estilos.local} numberOfLines={1}>
          {missao.bairro}, {missao.cidade}
        </Text>

        <View style={estilos.recompensa}>
          <View style={estilos.xp}>
            <Text style={estilos.xpValor}>{missao.xpRecompensa}</Text>
            <Text style={estilos.xpRotulo}>XP</Text>
          </View>
          <SaldoToken tokens={missao.tokensRecompensa} testID="recompensa-tokens" />
        </View>
      </Card>
    </Pressable>
  );
}

const estilos = StyleSheet.create({
  topo: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  distancia: { ...tipografia.rotulo, color: cores.tinta50 },
  titulo: { ...tipografia.subtitulo, color: cores.tinta },
  local: { ...tipografia.legenda, color: cores.tinta50 },
  recompensa: { flexDirection: 'row', alignItems: 'center', gap: espaco.lg },
  xp: { flexDirection: 'row', alignItems: 'baseline', gap: espaco.xs },
  xpValor: { ...tipografia.rotulo, color: cores.ambar },
  xpRotulo: { ...tipografia.legenda, color: cores.ambar },
});
