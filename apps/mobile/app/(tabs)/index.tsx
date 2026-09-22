import { useRouter } from 'expo-router';
import { useMemo, useState } from 'react';
import { ActivityIndicator, FlatList, RefreshControl, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { mensagemDe } from '@/api/erros';
import type { CategoriaMissao, MissaoResponse } from '@/api/tipos';
import { Botao } from '@/components/Botao';
import { Chip } from '@/components/Chip';
import { TituloTela } from '@/components/TituloTela';
import { EsqueletoMissaoCard } from '@/components/Esqueleto';
import { EstadoVazio } from '@/components/EstadoVazio';
import { JustificativaLocalizacao } from '@/components/JustificativaLocalizacao';
import { MissaoCard } from '@/components/MissaoCard';
import { useMissoesInfinitas, useMissoesProximas } from '@/features/missoes/hooks';
import { useLocalizacao } from '@/features/missoes/useLocalizacao';
import { rotuloCategoria } from '@/lib/formatar';
import { cores, coresCategoria, glifoCategoria, espaco, textoAcessivel, tipografia } from '@/theme';

const CATEGORIAS: CategoriaMissao[] = ['ENTREGA', 'COLETA', 'TRIBO', 'AJUDA'];

type Modo = 'perto' | 'todas';

interface ItemLista {
  missao: MissaoResponse;
  distanciaM?: number;
}

export default function TelaMissoes() {
  const router = useRouter();
  const [modo, setModo] = useState<Modo>('perto');
  const [categoria, setCategoria] = useState<CategoriaMissao | undefined>();

  const { coordenada, estado: estadoLocal, recarregar: recarregarLocal } = useLocalizacao();
  const radar = useMissoesProximas(modo === 'perto' ? coordenada : null, categoria);
  const lista = useMissoesInfinitas(categoria);

  // Sem permissão de localização não há radar possível — o servidor exige lat/lon. Cair para
  // "Todas" mantém o app útil em vez de mostrar uma tela morta.
  const modoEfetivo: Modo = modo === 'perto' && estadoLocal === 'negada' ? 'todas' : modo;

  const itens: ItemLista[] = useMemo(() => {
    if (modoEfetivo === 'perto') {
      return (radar.data ?? []).map((item) => ({
        missao: item.missao,
        distanciaM: item.distanciaM,
      }));
    }
    return (lista.data?.pages ?? []).flatMap((pagina) =>
      pagina.conteudo.map((missao) => ({ missao })),
    );
  }, [modoEfetivo, radar.data, lista.data]);

  const carregando = modoEfetivo === 'perto' ? radar.isLoading : lista.isLoading;
  const erro = modoEfetivo === 'perto' ? radar.error : lista.error;
  const atualizando = modoEfetivo === 'perto' ? radar.isRefetching : lista.isRefetching;
  /**
   * A lista na tela é a ANTERIOR, e a desta chave ainda está vindo.
   *
   * Categoria e recorte entram na query key, então todo toque num chip criava uma chave sem cache:
   * `isLoading` virava true e a `FlatList` inteira era desmontada e trocada por três esqueletos, e
   * voltava. Com `keepPreviousData` nos hooks, `carregando` passou a ser só a PRIMEIRA carga —
   * daqui em diante o que sinaliza "tem coisa nova vindo" é isto, e a lista continua legível
   * enquanto isso. Esmaecer diz que o conteúdo está defasado sem tirá-lo do lugar.
   */
  const desatualizada = modoEfetivo === 'perto' ? radar.isPlaceholderData : lista.isPlaceholderData;

  function recarregar() {
    if (modoEfetivo === 'perto') {
      void recarregarLocal();
      void radar.refetch();
    } else {
      void lista.refetch();
    }
  }

  return (
    <SafeAreaView style={estilos.tela} edges={['top']}>
      <View style={estilos.cabecalho}>
        <View style={estilos.linhaTitulo}>
          <TituloTela>Missões</TituloTela>
          {/* Porta de entrada dos rascunhos. Sem ela a missão criada e não publicada ficava
              inalcançável: `criar.tsx` navega para o detalhe e a pessoa que saísse de lá redigitava
              tudo. Fica ao lado de "Criar" porque é o par dela — o que você começou e o que você
              ainda não terminou. */}
          <View style={estilos.acoesCabecalho}>
            <Botao
              titulo="Rascunhos"
              variante="secundario"
              onPress={() => router.push('/missao/rascunhos')}
              estilo={estilos.botaoCriar}
              testID="botao-rascunhos"
            />
            <Botao
              titulo="Criar"
              onPress={() => router.push('/missao/criar')}
              estilo={estilos.botaoCriar}
              testID="botao-criar-missao"
            />
          </View>
        </View>
        <View
          style={estilos.modos}
          accessibilityRole="radiogroup"
          accessibilityLabel="Recorte da lista"
        >
          <Chip
            rotulo="Perto de mim"
            selecionado={modoEfetivo === 'perto'}
            onPress={() => setModo('perto')}
            testID="modo-perto"
          />
          <Chip
            rotulo="Todas"
            selecionado={modoEfetivo === 'todas'}
            onPress={() => setModo('todas')}
            testID="modo-todas"
          />
        </View>
      </View>

      {/* Escolha única entre categorias. Sem o papel, são cinco botões sem relação aparente. */}
      <View
        style={estilos.filtros}
        accessibilityRole="radiogroup"
        accessibilityLabel="Filtrar por categoria"
      >
        <Chip rotulo="Todas" selecionado={!categoria} onPress={() => setCategoria(undefined)} />
        {CATEGORIAS.map((item) => (
          <Chip
            key={item}
            rotulo={rotuloCategoria(item)}
            glifo={glifoCategoria[item]}
            selecionado={categoria === item}
            onPress={() => setCategoria(categoria === item ? undefined : item)}
            corFundo={coresCategoria[item].fundo}
            corTexto={coresCategoria[item].texto}
            testID={`filtro-${item}`}
          />
        ))}
      </View>

      {estadoLocal === 'negada' && modo === 'perto' ? (
        <Text style={estilos.avisoLocal} testID="aviso-localizacao">
          Sem acesso à localização, mostrando todas as missões abertas.
        </Text>
      ) : null}

      {carregando ? (
        <View style={estilos.corpo} testID="lista-carregando">
          <EsqueletoMissaoCard />
          <EsqueletoMissaoCard />
          <EsqueletoMissaoCard />
        </View>
      ) : erro ? (
        <EstadoVazio
          testID="lista-erro"
          titulo="Não deu para carregar as missões"
          descricao={mensagemDe(erro)}
          acao={{ rotulo: 'Tentar de novo', onPress: recarregar }}
        />
      ) : (
        <FlatList
          testID="lista-missoes"
          data={itens}
          keyExtractor={(item) => item.missao.id}
          contentContainerStyle={estilos.corpo}
          style={desatualizada ? estilos.listaDesatualizada : undefined}
          refreshControl={
            <RefreshControl
              refreshing={atualizando}
              onRefresh={recarregar}
              tintColor={cores.verdePrimario}
            />
          }
          onEndReachedThreshold={0.5}
          onEndReached={() => {
            // Paginação infinita só existe em "Todas": o radar é um array limitado pelo servidor,
            // sem cursor — pedir "próxima página" dele não significaria nada.
            if (modoEfetivo === 'todas' && lista.hasNextPage && !lista.isFetchingNextPage) {
              void lista.fetchNextPage();
            }
          }}
          ListEmptyComponent={
            // O radar só existe com posição, e a posição só é pedida DEPOIS de a pessoa ler para
            // quê. Enquanto ela não decide, esta tela explica em vez de disparar o diálogo do
            // sistema na montagem — que era o defeito: a aba de missões gastava a única chance de
            // justificar, e o card do mapa chegava tarde.
            modo === 'perto' && estadoLocal === 'inicial' ? (
              <JustificativaLocalizacao
                proposito="Para mostrar as missões mais próximas e a distância até cada uma, o app precisa saber onde você está."
                semPermissao='Sem permissão, use "Todas" para ver as missões abertas do bairro — só não dá para ordenar por distância.'
                aoPermitir={() => void recarregarLocal()}
                testID="justificativa-localizacao"
              />
            ) : (
              <EstadoVazio
                testID="lista-vazia"
                titulo="Nenhuma missão por aqui"
                descricao={
                  modoEfetivo === 'perto'
                    ? 'Não há missões abertas num raio de 2 km. Tente "Todas" ou volte mais tarde.'
                    : 'Ainda não há missões abertas nesta categoria.'
                }
              />
            )
          }
          ListFooterComponent={
            lista.isFetchingNextPage ? (
              <ActivityIndicator style={estilos.rodape} color={cores.verdePrimario} />
            ) : null
          }
          renderItem={({ item }) => (
            <MissaoCard
              missao={item.missao}
              distanciaM={item.distanciaM}
              onPress={() => router.push(`/missao/${item.missao.id}`)}
              testID={`missao-${item.missao.id}`}
            />
          )}
        />
      )}
    </SafeAreaView>
  );
}

/**
 * Opacidade da lista enquanto o recorte novo ainda está vindo. Ver `desatualizada`.
 *
 * Não pertence a `espaco` nem a `tipografia` — não é respiro nem texto —, e por isso é nomeada aqui
 * em vez de aparecer como um `0.55` solto no meio do estilo.
 */
const OPACIDADE_LISTA_DESATUALIZADA = 0.55;

const estilos = StyleSheet.create({
  listaDesatualizada: { opacity: OPACIDADE_LISTA_DESATUALIZADA },
  tela: { flex: 1, backgroundColor: cores.papel },
  cabecalho: { paddingHorizontal: espaco.lg, paddingTop: espaco.md, gap: espaco.md },
  titulo: { ...tipografia.titulo, color: cores.tinta },
  linhaTitulo: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  acoesCabecalho: { flexDirection: 'row', gap: espaco.sm, alignItems: 'center' },
  botaoCriar: { paddingHorizontal: espaco.lg },
  modos: { flexDirection: 'row', gap: espaco.sm },
  filtros: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: espaco.sm,
    paddingHorizontal: espaco.lg,
    paddingVertical: espaco.md,
  },
  avisoLocal: {
    ...tipografia.legenda,
    color: textoAcessivel.ambar,
    paddingHorizontal: espaco.lg,
    paddingBottom: espaco.sm,
  },
  corpo: { padding: espaco.lg, paddingTop: 0, gap: espaco.md },
  rodape: { paddingVertical: espaco.lg },
});
