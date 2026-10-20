import type { MissaoProximaResponse, PontoCustodiaResponse } from '@/api/tipos';

/** Uma linha da lista do radar: as duas seções viram itens de UMA FlatList. */
export type LinhaRadar =
  | { tipo: 'titulo'; chave: string; texto: string; secao: boolean }
  | { tipo: 'missao'; chave: string; item: MissaoProximaResponse }
  | { tipo: 'ponto'; chave: string; ponto: PontoCustodiaResponse }
  | { tipo: 'vazio'; chave: string; titulo: string; descricao: string; testID: string };

/**
 * Monta as linhas da lista do radar, na ordem da tela — é aqui que mora a regra das seções.
 *
 * <b>NÃO reordena nada.</b> A ordem por distância vem do servidor (`ORDER BY distancia_m` sobre
 * `geography`); um segundo cálculo aqui daria uma ordem ocasionalmente diferente da do mapa.
 */
export function linhasDoRadar(
  missoes: MissaoProximaResponse[],
  pontos: PontoCustodiaResponse[],
  carregando: boolean,
): LinhaRadar[] {
  const linhas: LinhaRadar[] = [
    {
      tipo: 'titulo',
      chave: 'titulo-missoes',
      texto: missoes.length > 0 ? `Missões próximas (${missoes.length})` : 'Missões próximas',
      secao: false,
    },
  ];
  for (const item of missoes) linhas.push({ tipo: 'missao', chave: item.missao.id, item });
  if (missoes.length === 0 && !carregando) {
    linhas.push({
      tipo: 'vazio',
      chave: 'vazio-missoes',
      titulo: 'Nenhuma missão por aqui',
      descricao: 'Não há missões abertas nesta região. Arraste o mapa ou volte mais tarde.',
      testID: 'radar-sem-missoes',
    });
  }
  linhas.push({
    tipo: 'titulo',
    chave: 'titulo-pontos',
    texto: pontos.length > 0 ? `Pontos de custódia (${pontos.length})` : 'Pontos de custódia',
    secao: true,
  });
  for (const ponto of pontos) linhas.push({ tipo: 'ponto', chave: `ponto-${ponto.id}`, ponto });
  if (pontos.length === 0 && !carregando) {
    linhas.push({
      tipo: 'vazio',
      chave: 'vazio-pontos',
      titulo: 'Nenhum ponto por perto',
      descricao: 'Pontos de custódia guardam encomendas que falharam na entrega.',
      testID: 'radar-sem-pontos',
    });
  }
  return linhas;
}
