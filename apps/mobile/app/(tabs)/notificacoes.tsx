import { useRouter } from 'expo-router';
import { useState } from 'react';
import { FlatList, Pressable, RefreshControl, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { mensagemDe } from '@/api/erros';
import type { AlertaResponse } from '@/api/tipos';
import { Card } from '@/components/Card';
import { Chip } from '@/components/Chip';
import { Esqueleto } from '@/components/Esqueleto';
import { EstadoVazio } from '@/components/EstadoVazio';
import { useAlertasInfinitos, useContagemNaoLidos, useMarcarLido } from '@/features/alertas/hooks';
import { formatarDataHora } from '@/lib/formatar';
import { cores, espaco, tipografia } from '@/theme';

export default function TelaNotificacoes() {
  const router = useRouter();
  const [apenasNaoLidos, setApenasNaoLidos] = useState(false);

  const alertas = useAlertasInfinitos(apenasNaoLidos);
  const contagem = useContagemNaoLidos();
  const marcarLido = useMarcarLido();

  const itens = (alertas.data?.pages ?? []).flatMap((pagina) => pagina.conteudo);

  function abrir(alerta: AlertaResponse) {
    // Marcar ao ABRIR, e não num botão separado: é o gesto que significa "eu vi isto". A operação
    // é idempotente no servidor, então tocar duas vezes não é problema.
    if (!alerta.lido) marcarLido.mutate({ id: alerta.id });
    if (alerta.missaoId) router.push(`/missao/${alerta.missaoId}`);
  }

  return (
    <SafeAreaView style={estilos.raiz} edges={['top']} testID="tela-notificacoes">
      <FlatList
        testID="lista-alertas"
        data={itens}
        keyExtractor={(item) => item.id}
        contentContainerStyle={estilos.corpo}
        refreshControl={
          <RefreshControl
            refreshing={alertas.isRefetching}
            onRefresh={() => {
              void alertas.refetch();
              void contagem.refetch();
            }}
            tintColor={cores.verdePrimario}
          />
        }
        onEndReachedThreshold={0.5}
        onEndReached={() => {
          if (alertas.hasNextPage && !alertas.isFetchingNextPage) void alertas.fetchNextPage();
        }}
        ListHeaderComponent={
          <View style={estilos.cabecalho}>
            <Text style={estilos.titulo} accessibilityRole="header">
              Avisos
            </Text>
            <View style={estilos.filtros}>
              <Chip
                rotulo="Todos"
                selecionado={!apenasNaoLidos}
                onPress={() => setApenasNaoLidos(false)}
                testID="filtro-todos"
              />
              <Chip
                rotulo={
                  contagem.data && contagem.data > 0 ? `Não lidos (${contagem.data})` : 'Não lidos'
                }
                selecionado={apenasNaoLidos}
                onPress={() => setApenasNaoLidos(true)}
                testID="filtro-nao-lidos"
              />
            </View>
          </View>
        }
        ListEmptyComponent={
          alertas.isLoading ? (
            <View style={estilos.esqueletos} testID="alertas-carregando">
              <Esqueleto altura={72} />
              <Esqueleto altura={72} />
            </View>
          ) : alertas.error ? (
            <EstadoVazio
              testID="alertas-erro"
              titulo="Não deu para carregar seus avisos"
              descricao={mensagemDe(alertas.error)}
              acao={{ rotulo: 'Tentar de novo', onPress: () => void alertas.refetch() }}
            />
          ) : (
            <EstadoVazio
              testID="alertas-vazio"
              titulo={apenasNaoLidos ? 'Nada pendente' : 'Nenhum aviso ainda'}
              descricao={
                apenasNaoLidos
                  ? 'Você já leu tudo.'
                  : 'Quando uma missão sua for concluída, o aviso aparece aqui.'
              }
            />
          )
        }
        renderItem={({ item }) => (
          <Pressable
            onPress={() => abrir(item)}
            accessibilityRole="button"
            accessible
            accessibilityLabel={`${item.lido ? 'Lido' : 'Não lido'}. ${item.titulo}. ${item.corpo}`}
            testID={`alerta-${item.id}`}
          >
            <Card estilo={item.lido ? undefined : estilos.naoLido}>
              <View style={estilos.linhaTopo}>
                <Text style={item.lido ? estilos.tituloLido : estilos.tituloNaoLido}>
                  {item.titulo}
                </Text>
                {/* Ponto, e não só negrito: peso de fonte é um sinal fraco em tela pequena e
                    invisível para quem usa fonte grande do sistema. */}
                {!item.lido ? <View style={estilos.ponto} testID="marca-nao-lido" /> : null}
              </View>
              <Text style={estilos.corpoTexto}>{item.corpo}</Text>
              <Text style={estilos.data}>{formatarDataHora(item.criadoEm)}</Text>
            </Card>
          </Pressable>
        )}
      />
    </SafeAreaView>
  );
}

const estilos = StyleSheet.create({
  raiz: { flex: 1, backgroundColor: cores.papel },
  corpo: { padding: espaco.lg, gap: espaco.sm, flexGrow: 1 },
  cabecalho: { gap: espaco.md, paddingBottom: espaco.sm },
  titulo: { ...tipografia.titulo, color: cores.tinta },
  filtros: { flexDirection: 'row', gap: espaco.sm },
  esqueletos: { gap: espaco.sm },
  naoLido: { borderLeftWidth: 3, borderLeftColor: cores.verdePrimario },
  linhaTopo: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  tituloNaoLido: { ...tipografia.subtitulo, color: cores.tinta, flexShrink: 1 },
  tituloLido: { ...tipografia.corpo, color: cores.tinta70, flexShrink: 1 },
  corpoTexto: { ...tipografia.corpo, color: cores.tinta70 },
  data: { ...tipografia.legenda, color: cores.tinta50 },
  ponto: { width: 10, height: 10, borderRadius: 5, backgroundColor: cores.verdePrimario },
});
