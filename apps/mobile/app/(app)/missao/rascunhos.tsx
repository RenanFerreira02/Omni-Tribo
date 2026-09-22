import { useRouter } from 'expo-router';
import { useMemo, useState } from 'react';
import { FlatList, RefreshControl, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { Aviso } from '@/components/Aviso';
import { Botao } from '@/components/Botao';
import { Card } from '@/components/Card';
import { DialogoConfirmacao } from '@/components/DialogoConfirmacao';
import { EstadoVazio } from '@/components/EstadoVazio';
import { EsqueletoMissaoCard } from '@/components/Esqueleto';
import { MissaoCard } from '@/components/MissaoCard';
import { TituloTela } from '@/components/TituloTela';
import { mensagemDe } from '@/api/erros';
import type { MissaoResponse } from '@/api/tipos';
import { useAcaoMissao, useRascunhos } from '@/features/missoes/hooks';
import { cores, espaco, textoAcessivel, tipografia } from '@/theme';

/**
 * Os rascunhos de quem está logado.
 *
 * Existe porque a missão criada caía num buraco: `criar.tsx` navega para o detalhe e pronto — quem
 * saísse da tela perdia o caminho de volta e redigitava tudo. O dado sempre esteve no servidor
 * (`GET /missoes?status=RASCUNHO&minhas=CRIADAS`, com o filtro de rascunho alheio dentro da própria
 * consulta); o que faltava era leitor.
 *
 * Cada linha diz **por que** aquele rascunho ainda não está no ar, e a razão vem de `fontePote` —
 * `poteTokens: 0` significa coisas opostas em `COMUNIDADE` e em `SEM_TOKEN`, e sem o campo a tela
 * teria de adivinhar.
 */
export default function Rascunhos() {
  const router = useRouter();
  const rascunhos = useRascunhos();

  const itens = useMemo(
    () => (rascunhos.data?.pages ?? []).flatMap((pagina) => pagina.conteudo),
    [rascunhos.data],
  );

  return (
    <SafeAreaView style={estilos.raiz} testID="tela-rascunhos">
      <View style={estilos.cabecalho}>
        <TituloTela>Rascunhos</TituloTela>
        <Text style={estilos.ajuda}>
          Missões que você criou e ainda não publicou. Elas ficam aqui até você publicar ou
          descartar.
        </Text>
      </View>

      {rascunhos.isError ? (
        <View style={estilos.cabecalho}>
          <Aviso tom="erro" mensagem={mensagemDe(rascunhos.error)} testID="erro-rascunhos" />
        </View>
      ) : null}

      <FlatList
        data={itens}
        keyExtractor={(item) => item.id}
        contentContainerStyle={estilos.lista}
        refreshControl={
          <RefreshControl refreshing={rascunhos.isRefetching} onRefresh={rascunhos.refetch} />
        }
        onEndReached={() => {
          if (rascunhos.hasNextPage && !rascunhos.isFetchingNextPage) rascunhos.fetchNextPage();
        }}
        onEndReachedThreshold={0.5}
        ListEmptyComponent={
          rascunhos.isLoading ? (
            <View style={estilos.lista}>
              <EsqueletoMissaoCard />
              <EsqueletoMissaoCard />
            </View>
          ) : rascunhos.isError ? null : (
            <EstadoVazio
              titulo="Nenhum rascunho por aqui"
              descricao="Toda missão nasce como rascunho. Quando você criar uma e não publicar na hora, ela aparece nesta lista."
              acao={{ rotulo: 'Criar missão', onPress: () => router.push('/missao/criar') }}
              testID="rascunhos-vazio"
            />
          )
        }
        renderItem={({ item }) => (
          <LinhaRascunho
            missao={item}
            aoAbrir={() => router.push(`/missao/${item.id}`)}
            aoEditar={() => router.push(`/missao/editar/${item.id}`)}
          />
        )}
      />
    </SafeAreaView>
  );
}

/**
 * Uma linha: o card da missão, a razão de ela não estar publicada, e as três ações.
 *
 * A mutation é POR LINHA, e não uma só para a lista inteira: uma instância compartilhada com
 * `disabled={isPending}` travaria todos os rascunhos enquanto um publica — o mesmo defeito que os
 * `Switch` de consentimento tiveram.
 */
function LinhaRascunho({
  missao,
  aoAbrir,
  aoEditar,
}: {
  missao: MissaoResponse;
  aoAbrir: () => void;
  aoEditar: () => void;
}) {
  const acao = useAcaoMissao(missao.id);
  const situacao = situacaoDoRascunho(missao);
  // O diálogo vive AQUI, e não na lista, porque quem cancela é esta mutation. Um diálogo lá em cima
  // precisaria de uma segunda instância de `useAcaoMissao` para o item escolhido, e aí duas
  // mutations disputariam o mesmo id.
  const [confirmando, setConfirmando] = useState(false);

  return (
    <Card estilo={estilos.linha}>
      <MissaoCard missao={missao} onPress={aoAbrir} testID={`rascunho-${missao.id}`} />

      <Text
        style={situacao.publicavel ? estilos.pronta : estilos.pendente}
        testID={`situacao-${missao.id}`}
      >
        {situacao.texto}
      </Text>

      {acao.error ? (
        <Aviso tom="erro" mensagem={mensagemDe(acao.error)} testID={`erro-acao-${missao.id}`} />
      ) : null}

      <View style={estilos.acoes}>
        <Botao
          titulo="Publicar"
          carregando={acao.isPending && acao.variables?.acao === 'publicar'}
          onPress={() => acao.mutate({ acao: 'publicar' })}
          testID={`publicar-${missao.id}`}
        />
        <Botao
          titulo="Editar"
          variante="secundario"
          onPress={aoEditar}
          testID={`editar-${missao.id}`}
        />
        <Botao
          titulo="Descartar"
          variante="texto"
          onPress={() => setConfirmando(true)}
          testID={`descartar-${missao.id}`}
        />
      </View>

      {/* Cancelar rascunho é a transição RASCUNHO --CANCELAR--> CANCELADA, e ela é TERMINAL: não
          há volta, e é por isso que confirma antes. Quando o rascunho tem pote, é esta transição
          que estorna aos financiadores — ver StatusMissao no backend. */}
      <DialogoConfirmacao
        visivel={confirmando}
        titulo="Descartar este rascunho?"
        mensagem={`"${missao.titulo}" será cancelado e não poderá ser recuperado.`}
        rotuloConfirmar="Descartar"
        destrutivo
        carregando={acao.isPending && acao.variables?.acao === 'cancelar'}
        aoConfirmar={() => {
          setConfirmando(false);
          acao.mutate({ acao: 'cancelar' });
        }}
        aoCancelar={() => setConfirmando(false)}
        testID={`dialogo-descartar-${missao.id}`}
      />
    </Card>
  );
}

/**
 * Por que este rascunho ainda não está no ar — derivado de `fontePote`, nunca do pote sozinho.
 *
 * As quatro fontes dão três frases diferentes, e a diferença importa: `COMUNIDADE` sem pote é uma
 * espera por outra pessoa; `SEM_TOKEN` não espera nada; `CUNHAGEM` e `PATROCINADOR` publicam
 * direto. Ler só `poteTokens === 0` juntaria a primeira com a segunda, que são opostas.
 */
export function situacaoDoRascunho(missao: MissaoResponse): {
  texto: string;
  publicavel: boolean;
} {
  if (missao.fontePote === 'SEM_TOKEN') {
    return { texto: 'Pronta para publicar — vale só XP.', publicavel: true };
  }
  const faltam = missao.tokensRecompensa - missao.poteTokens;
  if (missao.fontePote === 'COMUNIDADE' && faltam > 0) {
    return {
      texto: `Faltam ${faltam} tokens de financiamento da tribo para publicar. Você pode editar e deixá-la valendo só XP.`,
      publicavel: false,
    };
  }
  return { texto: 'Pronta para publicar.', publicavel: true };
}

const estilos = StyleSheet.create({
  raiz: { flex: 1, backgroundColor: cores.papel },
  cabecalho: { paddingHorizontal: espaco.lg, paddingTop: espaco.md, gap: espaco.xs },
  lista: { padding: espaco.lg, gap: espaco.md },
  linha: { gap: espaco.sm },
  ajuda: { ...tipografia.legenda, color: textoAcessivel.suave },
  pendente: { ...tipografia.legenda, color: textoAcessivel.ambar },
  pronta: { ...tipografia.legenda, color: cores.verdeEscuro },
  acoes: { flexDirection: 'row', flexWrap: 'wrap', gap: espaco.sm, alignItems: 'center' },
});
