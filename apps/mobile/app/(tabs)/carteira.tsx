import { FlatList, RefreshControl, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { mensagemDe } from '@/api/erros';
import type { LancamentoResponse } from '@/api/tipos';
import { Card } from '@/components/Card';
import { Esqueleto } from '@/components/Esqueleto';
import { EstadoVazio } from '@/components/EstadoVazio';
import { SaldoToken } from '@/components/SaldoToken';
import { useCarteira, useLancamentos } from '@/features/carteira/hooks';
import { formatarDataHora } from '@/lib/formatar';
import { cores, espaco, tipografia } from '@/theme';

const ROTULOS_MOTIVO: Record<LancamentoResponse['motivo'], string> = {
  RECOMPENSA_MISSAO: 'Recompensa de missão',
  TRANSFERENCIA_ENVIADA: 'Transferência enviada',
  TRANSFERENCIA_RECEBIDA: 'Transferência recebida',
  FINANCIAMENTO_TRIBO: 'Financiamento de missão',
  SAQUE: 'Saque',
  BONUS: 'Bônus',
  ESTORNO: 'Estorno',
};

export default function TelaCarteira() {
  const carteira = useCarteira();
  const extrato = useLancamentos();

  const lancamentos = (extrato.data?.pages ?? []).flatMap((pagina) => pagina.conteudo);

  return (
    <SafeAreaView style={estilos.tela} edges={['top']}>
      <FlatList
        testID="extrato"
        data={lancamentos}
        keyExtractor={(item) => item.id}
        contentContainerStyle={estilos.corpo}
        refreshControl={
          <RefreshControl
            refreshing={extrato.isRefetching}
            onRefresh={() => {
              void carteira.refetch();
              void extrato.refetch();
            }}
            tintColor={cores.verdePrimario}
          />
        }
        onEndReachedThreshold={0.5}
        onEndReached={() => {
          if (extrato.hasNextPage && !extrato.isFetchingNextPage) void extrato.fetchNextPage();
        }}
        ListHeaderComponent={
          <View style={estilos.cabecalho}>
            <Text style={estilos.titulo}>Carteira</Text>

            <Card estilo={estilos.saldo}>
              <Text style={estilos.rotuloSaldo}>Seus tokens</Text>
              {carteira.isLoading ? (
                <Esqueleto largura={140} altura={40} />
              ) : carteira.error ? (
                <Text style={estilos.erro}>{mensagemDe(carteira.error)}</Text>
              ) : (
                <SaldoToken
                  tokens={carteira.data?.saldoTokens ?? 0}
                  tamanho="grande"
                  testID="saldo-tokens"
                />
              )}
              {/*
                `saldoBrl` NÃO aparece aqui, e a ausência é a decisão. A coluna existe e vale sempre
                0,00 (ADR 0009): nenhuma missão remunera em BRL, e o saque está desligado. Mostrar
                "R$ 0,00" sugeriria que um dia haverá outro número — exatamente a expectativa que o
                produto não quer criar.
              */}
              <Text style={estilos.explicacao}>
                Tokens são resgatáveis em benefícios de parceiros do bairro.
              </Text>
            </Card>

            <Text style={estilos.subtitulo}>Extrato</Text>
          </View>
        }
        ListEmptyComponent={
          extrato.isLoading ? (
            <View style={estilos.esqueletos} testID="extrato-carregando">
              <Esqueleto altura={48} />
              <Esqueleto altura={48} />
              <Esqueleto altura={48} />
            </View>
          ) : extrato.error ? (
            <EstadoVazio
              testID="extrato-erro"
              titulo="Não deu para carregar o extrato"
              descricao={mensagemDe(extrato.error)}
              acao={{ rotulo: 'Tentar de novo', onPress: () => void extrato.refetch() }}
            />
          ) : (
            <EstadoVazio
              testID="extrato-vazio"
              titulo="Sem movimentações ainda"
              descricao="Conclua uma missão para receber seus primeiros tokens."
            />
          )
        }
        renderItem={({ item }) => <LinhaLancamento lancamento={item} />}
      />
    </SafeAreaView>
  );
}

function LinhaLancamento({ lancamento }: { lancamento: LancamentoResponse }) {
  const entrada = lancamento.sinal === 'CREDITO';
  return (
    <Card estilo={estilos.linha}>
      <View style={estilos.linhaTexto}>
        <Text style={estilos.motivo}>{ROTULOS_MOTIVO[lancamento.motivo]}</Text>
        <Text style={estilos.data}>{formatarDataHora(lancamento.criadoEm)}</Text>
        {lancamento.mensagem ? <Text style={estilos.mensagem}>{lancamento.mensagem}</Text> : null}
      </View>
      <View style={estilos.valor}>
        <Text style={[estilos.sinal, { color: entrada ? cores.verdePrimario : cores.coral }]}>
          {entrada ? '+' : '−'}
        </Text>
        <SaldoToken
          tokens={lancamento.valorTokens}
          cor={entrada ? cores.verdePrimario : cores.coral}
        />
      </View>
    </Card>
  );
}

const estilos = StyleSheet.create({
  tela: { flex: 1, backgroundColor: cores.papel },
  corpo: { padding: espaco.lg, gap: espaco.md },
  cabecalho: { gap: espaco.lg, marginBottom: espaco.sm },
  titulo: { ...tipografia.titulo, color: cores.tinta },
  subtitulo: { ...tipografia.subtitulo, color: cores.tinta },
  saldo: { alignItems: 'flex-start', gap: espaco.sm },
  rotuloSaldo: { ...tipografia.rotulo, color: cores.tinta50 },
  explicacao: { ...tipografia.legenda, color: cores.tinta70 },
  erro: { ...tipografia.corpo, color: cores.coral },
  esqueletos: { gap: espaco.md },
  linha: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  linhaTexto: { flex: 1, gap: espaco.xs },
  motivo: { ...tipografia.rotulo, color: cores.tinta },
  data: { ...tipografia.legenda, color: cores.tinta50 },
  mensagem: { ...tipografia.legenda, color: cores.tinta70 },
  valor: { flexDirection: 'row', alignItems: 'center', gap: espaco.xs },
  sinal: { ...tipografia.subtitulo },
});
