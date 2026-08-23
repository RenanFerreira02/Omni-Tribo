import { Pressable, StyleSheet, Text, View } from 'react-native';

import { Card } from './Card';
import { Chip } from './Chip';
import { SaldoToken } from './SaldoToken';
import type { MissaoResponse } from '@/api/tipos';
import { formatarDistancia, formatarPrazo, rotuloCategoria } from '@/lib/formatar';
import { cores, coresCategoria, espaco, glifoCategoria, textoAcessivel, tipografia } from '@/theme';

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
    <Pressable
      onPress={onPress}
      disabled={!onPress}
      testID={testID}
      accessibilityRole="button"
      // `accessible` agrupa os filhos num nó só, e o label é a frase que substitui a leitura deles.
      // Sem isto o leitor de tela anunciava fragmentos soltos — "Entrega", "413 m", o título,
      // "Pinheiros", "69", "XP", "23 tokens" — sete paradas para entender um card.
      accessible
      accessibilityLabel={rotuloAcessivel(missao, distanciaM)}
      // Sem isto o card decorativo (sem `onPress`) continuava anunciado como "botão" — o leitor de
      // tela oferecia uma ação que não existe. O `disabled` do Pressable não chega sozinho à árvore
      // de acessibilidade.
      accessibilityState={{ disabled: !onPress }}
    >
      <Card>
        <View style={estilos.topo}>
          <Chip
            rotulo={rotuloCategoria(missao.categoria)}
            glifo={glifoCategoria[missao.categoria]}
            corFundo={paleta.fundo}
            corTexto={paleta.texto}
          />
          {distanciaM !== undefined ? (
            <Text style={estilos.distancia}>{formatarDistancia(distanciaM)}</Text>
          ) : null}
        </View>

        {/* 3 e 2 linhas, e não 2 e 1: com a fonte do sistema no máximo, dois terços dos títulos
            truncavam no meio de uma palavra. O card cresce — é o que deve acontecer. */}
        <Text style={estilos.titulo} numberOfLines={3}>
          {missao.titulo}
        </Text>

        <Text style={estilos.local} numberOfLines={2}>
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

/**
 * Uma frase, na ordem em que a decisão é tomada: <b>categoria, recompensa, distância, prazo</b> —
 * e só depois o título e o local.
 *
 * <b>A ordem é o conteúdo.</b> Quem navega por voz percorre a lista item a item e decide se para no
 * primeiro terço da frase: o que é, quanto paga, quão longe, quanto tempo resta. O título é o que
 * menos separa uma missão da outra ("Levar tinta" x "Buscar encomenda" pesa menos que "a 180 m" x
 * "a 3 km"), então ele vem por último em vez de ocupar a segunda posição.
 *
 * O prazo entrou com a lista do radar: sem ele, a pessoa só descobria que a janela ia fechar depois
 * de abrir o detalhe — uma navegação inteira para uma informação que decide a escolha.
 */
function rotuloAcessivel(missao: MissaoResponse, distanciaM?: number): string {
  const partes = [
    rotuloCategoria(missao.categoria),
    `${missao.xpRecompensa} XP e ${missao.tokensRecompensa} tokens`,
  ];
  if (distanciaM !== undefined) partes.push(`a ${formatarDistancia(distanciaM)}`);
  partes.push(formatarPrazo(missao.janelaFim));
  partes.push(missao.titulo);
  partes.push(`em ${missao.bairro}, ${missao.cidade}`);
  return partes.join(', ') + '.';
}

const estilos = StyleSheet.create({
  topo: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  distancia: { ...tipografia.rotulo, color: textoAcessivel.suave },
  titulo: { ...tipografia.subtitulo, color: cores.tinta },
  local: { ...tipografia.legenda, color: textoAcessivel.suave },
  recompensa: { flexDirection: 'row', alignItems: 'center', gap: espaco.lg },
  xp: { flexDirection: 'row', alignItems: 'baseline', gap: espaco.xs },
  xpValor: { ...tipografia.rotulo, color: textoAcessivel.ambar },
  xpRotulo: { ...tipografia.legenda, color: textoAcessivel.ambar },
});
