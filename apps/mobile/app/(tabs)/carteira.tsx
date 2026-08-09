import { useRef, useState } from 'react';
import { FlatList, RefreshControl, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { mensagemDe } from '@/api/erros';
import type { LancamentoResponse } from '@/api/tipos';
import { Aviso } from '@/components/Aviso';
import { Botao } from '@/components/Botao';
import { CampoTexto } from '@/components/CampoTexto';
import { Card } from '@/components/Card';
import { Esqueleto } from '@/components/Esqueleto';
import { EstadoVazio } from '@/components/EstadoVazio';
import { FolhaInferior } from '@/components/FolhaInferior';
import { SaldoToken } from '@/components/SaldoToken';
import { useCarteira, useLancamentos, useTransferirTokens } from '@/features/carteira/hooks';
import { formatarDataHora } from '@/lib/formatar';
import { errosDoZod } from '@/lib/formulario';
import { novaChaveIdempotencia } from '@/lib/ids';
import { transferenciaSchema } from '@/schemas';
import { cores, espaco, textoAcessivel, tipografia } from '@/theme';

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
  const transferir = useTransferirTokens();

  const [transferirAberto, setTransferirAberto] = useState(false);
  const [destinatario, setDestinatario] = useState('');
  const [quantidade, setQuantidade] = useState('');
  const [mensagem, setMensagem] = useState('');
  const [errosForm, setErrosForm] = useState<Record<string, string>>({});

  /**
   * A chave de idempotência nasce com a INTENÇÃO, não com o toque.
   *
   * É gerada quando a folha abre e só é rotacionada depois de uma transferência bem-sucedida. Assim
   * um retry de rede — o caso em que o servidor recebeu mas a resposta se perdeu — repete a mesma
   * chave e o backend devolve o replay, em vez de transferir tokens duas vezes.
   */
  const chave = useRef(novaChaveIdempotencia());

  const lancamentos = (extrato.data?.pages ?? []).flatMap((pagina) => pagina.conteudo);

  /**
   * Valida pelo `transferenciaSchema`, e não à mão.
   *
   * A versão anterior fazia `if (!Number.isInteger(tokens) || tokens <= 0) return;` — um `return`
   * mudo. Quantidade vazia produzia um botão que não fazia nada e não dizia nada, destinatário
   * vazio ia para a rede, e 9999 tokens passavam por cima do teto de 500 que o schema já
   * declarava. O schema existia desde sempre e nunca havia sido importado.
   */
  function enviarTransferencia() {
    const analise = transferenciaSchema.safeParse({
      destinatarioId: destinatario.trim(),
      // String vazia vira `undefined`, e não `NaN`: "não informado" e "valor inválido" produzem
      // mensagens diferentes, e `Number('')` é 0, que passaria por um teste de tipo.
      tokens: quantidade.trim() === '' ? undefined : Number(quantidade),
      mensagem: mensagem.trim() || undefined,
    });

    if (!analise.success) {
      setErrosForm(errosDoZod(analise.error));
      return;
    }
    setErrosForm({});

    transferir.mutate(
      {
        ...analise.data,
        chaveIdempotencia: chave.current,
      },
      {
        onSuccess: () => {
          chave.current = novaChaveIdempotencia();
          setTransferirAberto(false);
          setDestinatario('');
          setQuantidade('');
          setMensagem('');
        },
      },
    );
  }

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

            <View style={estilos.acoesCarteira}>
              <Botao
                titulo="Transferir tokens"
                onPress={() => setTransferirAberto(true)}
                estilo={estilos.acaoLarga}
                testID="botao-abrir-transferencia"
              />

              {/*
                SAQUE: visível e DESABILITADO, com a explicação ao lado.

                A alternativa era escondê-lo. Um botão ausente não ensina nada — a pessoa procura
                "sacar", não encontra, e conclui que o app está quebrado ou que o dinheiro sumiu.
                Desabilitado com motivo responde a pergunta antes de ela ser feita. E o usuário
                nunca toca para receber um erro cru: o gate do servidor (422 saque-desabilitado) é
                a verdade, e a UI apenas a reflete. Ver ADR 0009 e o teste do 422.
              */}
              <Botao
                titulo="Sacar em reais"
                variante="secundario"
                disabled
                onPress={() => undefined}
                estilo={estilos.acaoLarga}
                testID="botao-saque"
              />
              <Text style={estilos.explicacao} testID="explicacao-saque">
                O saque em reais está indisponível nesta versão: a recompensa das missões é em XP e
                tokens, resgatáveis em benefícios de parceiros.
              </Text>
            </View>

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

      <FolhaInferior
        visivel={transferirAberto}
        aoFechar={() => setTransferirAberto(false)}
        titulo="Transferir tokens"
        testID="folha-transferencia"
      >
        <Text style={estilos.explicacao}>
          Só para membros da sua tribo. O destinatário recebe na hora, e a operação entra no extrato
          dos dois.
        </Text>

        <CampoTexto
          rotulo="Identificador do destinatário"
          value={destinatario}
          onChangeText={setDestinatario}
          autoCapitalize="none"
          erro={errosForm.destinatarioId}
          testID="campo-destinatario"
        />
        <CampoTexto
          rotulo="Quantidade de tokens"
          keyboardType="number-pad"
          value={quantidade}
          onChangeText={(texto) => setQuantidade(texto.replace(/\D/g, ''))}
          erro={errosForm.tokens}
          testID="campo-tokens"
        />
        <CampoTexto
          rotulo="Mensagem (opcional)"
          value={mensagem}
          onChangeText={setMensagem}
          maxLength={200}
          erro={errosForm.mensagem}
          testID="campo-mensagem"
        />

        {transferir.error ? (
          // Tribo diferente e saldo insuficiente respondem os DOIS 422 `regra-negocio-violada`, sem
          // URI própria — aqui o `detail` do servidor é a única fonte que distingue as duas causas.
          <Aviso tom="erro" mensagem={mensagemDe(transferir.error)} testID="erro-transferencia" />
        ) : null}

        <Botao
          titulo="Transferir"
          carregando={transferir.isPending}
          onPress={enviarTransferencia}
          testID="botao-confirmar-transferencia"
        />
      </FolhaInferior>
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
  acoesCarteira: { gap: espaco.sm },
  acaoLarga: { width: '100%' },
  tela: { flex: 1, backgroundColor: cores.papel },
  corpo: { padding: espaco.lg, gap: espaco.md },
  cabecalho: { gap: espaco.lg, marginBottom: espaco.sm },
  titulo: { ...tipografia.titulo, color: cores.tinta },
  subtitulo: { ...tipografia.subtitulo, color: cores.tinta },
  saldo: { alignItems: 'flex-start', gap: espaco.sm },
  rotuloSaldo: { ...tipografia.rotulo, color: textoAcessivel.suave },
  explicacao: { ...tipografia.legenda, color: cores.tinta70 },
  erro: { ...tipografia.corpo, color: textoAcessivel.coral },
  esqueletos: { gap: espaco.md },
  linha: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  linhaTexto: { flex: 1, gap: espaco.xs },
  motivo: { ...tipografia.rotulo, color: cores.tinta },
  data: { ...tipografia.legenda, color: textoAcessivel.suave },
  mensagem: { ...tipografia.legenda, color: cores.tinta70 },
  valor: { flexDirection: 'row', alignItems: 'center', gap: espaco.xs },
  sinal: { ...tipografia.subtitulo },
});
