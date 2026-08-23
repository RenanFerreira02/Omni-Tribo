import { useRouter } from 'expo-router';
import { useRef, useState } from 'react';
import { FlatList, RefreshControl, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { mensagemDe } from '@/api/erros';
import type { LancamentoResponse, UsuarioBuscaResponse } from '@/api/tipos';
import { Aviso } from '@/components/Aviso';
import { Botao } from '@/components/Botao';
import { CampoTexto } from '@/components/CampoTexto';
import { Card } from '@/components/Card';
import { TituloTela } from '@/components/TituloTela';
import { Esqueleto } from '@/components/Esqueleto';
import { EstadoVazio } from '@/components/EstadoVazio';
import { FolhaInferior } from '@/components/FolhaInferior';
import { SaldoToken } from '@/components/SaldoToken';
import {
  useBuscarPorHandle,
  useCarteira,
  useLancamentos,
  useTransferirTokens,
} from '@/features/carteira/hooks';
import { formatarDataHora } from '@/lib/formatar';
import { errosDoZod } from '@/lib/formulario';
import { useAnuncio } from '@/lib/anunciar';
import { novaChaveIdempotencia } from '@/lib/ids';
import { transferenciaSchema } from '@/schemas';
import { cores, espaco, textoAcessivel, tipografia } from '@/theme';

/**
 * Rótulo por motivo do ledger. `Record` FECHADO de propósito: um motivo novo no backend quebra o
 * typecheck aqui, em vez de aparecer no extrato do usuário como o enum cru em maiúsculas.
 *
 * Os três últimos entraram depois da F13 — os dois de patrocinador na V23, o resgate na V26.
 */
const ROTULOS_MOTIVO: Record<LancamentoResponse['motivo'], string> = {
  RECOMPENSA_MISSAO: 'Recompensa de missão',
  TRANSFERENCIA_ENVIADA: 'Transferência enviada',
  TRANSFERENCIA_RECEBIDA: 'Transferência recebida',
  FINANCIAMENTO_TRIBO: 'Financiamento de missão',
  // Só aparecem no extrato de um PATROCINADOR, que não usa o app — ficam aqui porque o Record é
  // fechado e o tipo os inclui, não porque a tela vá exibi-los.
  FINANCIAMENTO_PATROCINADOR: 'Financiamento de entrega',
  APORTE_PATROCINADOR: 'Aporte de patrocinador',
  /** A queima: token que saiu de circulação virando algo no bairro (ADR 0027). */
  RESGATE: 'Resgate no bairro',
  SAQUE: 'Saque',
  BONUS: 'Bônus',
  ESTORNO: 'Estorno',
};

export default function TelaCarteira() {
  const router = useRouter();
  const carteira = useCarteira();
  const extrato = useLancamentos();
  const transferir = useTransferirTokens();

  const [transferirAberto, setTransferirAberto] = useState(false);
  /**
   * O `@` digitado e o vizinho ENCONTRADO são estados separados de propósito.
   *
   * Editar o texto invalida quem estava confirmado — senão a pessoa procuraria "marlene", conferiria
   * o nome, trocaria para "jonas" e transferiria para a Marlene. Num ledger append-only isso vira
   * estorno manual, e é exatamente o erro que a busca veio evitar.
   */
  const [handle, setHandle] = useState('');
  const [encontrado, setEncontrado] = useState<UsuarioBuscaResponse | null>(null);
  const [quantidade, setQuantidade] = useState('');
  const [mensagem, setMensagem] = useState('');
  const [errosForm, setErrosForm] = useState<Record<string, string>>({});
  /**
   * O desfecho falado da última operação da folha.
   *
   * A busca e a transferência mudavam a tela sem dizer nada: o cartão com o nome do vizinho aparecia
   * e a folha fechava. Quem usa leitor de tela ficava sem saber se acertou o `@` e sem saber se o
   * token saiu — num ledger append-only, onde não há desfazer.
   */
  const [anuncio, setAnuncio] = useState<string | null>(null);

  /**
   * A chave de idempotência nasce com a INTENÇÃO, não com o toque.
   *
   * É gerada quando a folha abre e só é rotacionada depois de uma transferência bem-sucedida. Assim
   * um retry de rede — o caso em que o servidor recebeu mas a resposta se perdeu — repete a mesma
   * chave e o backend devolve o replay, em vez de transferir tokens duas vezes.
   */
  const chave = useRef(novaChaveIdempotencia());
  const busca = useBuscarPorHandle();

  useAnuncio(anuncio);

  const lancamentos = (extrato.data?.pages ?? []).flatMap((pagina) => pagina.conteudo);

  /**
   * Valida pelo `transferenciaSchema`, e não à mão.
   *
   * A versão anterior fazia `if (!Number.isInteger(tokens) || tokens <= 0) return;` — um `return`
   * mudo. Quantidade vazia produzia um botão que não fazia nada e não dizia nada, destinatário
   * vazio ia para a rede, e 9999 tokens passavam por cima do teto de 500 que o schema já
   * declarava. O schema existia desde sempre e nunca havia sido importado.
   */
  function procurarVizinho() {
    setEncontrado(null);
    setAnuncio(null);
    busca.mutate(handle.trim().replace(/^@/, ''), {
      onSuccess: (vizinho) => {
        setEncontrado(vizinho);
        // O NOME é o ponto da confirmação — é o que distingue um erro de digitação de um estorno
        // manual. Dizê-lo em voz alta é o equivalente de mostrá-lo no cartão.
        setAnuncio(
          `Vizinho encontrado: ${vizinho.nome}, arroba ${vizinho.handle}${vizinho.tribo ? `, ${vizinho.tribo}` : ''}. Confira antes de transferir.`,
        );
      },
      onError: () =>
        setAnuncio('Nenhum vizinho com esse arroba na sua tribo. Confira a escrita com a pessoa.'),
    });
  }

  function trocarHandle(texto: string) {
    setHandle(texto);
    // Qualquer edição derruba a confirmação: o nome na tela precisa corresponder ao @ que está no
    // campo, sempre.
    if (encontrado) setEncontrado(null);
    if (busca.isError) busca.reset();
  }

  function enviarTransferencia() {
    if (!encontrado) return;

    const analise = transferenciaSchema.safeParse({
      destinatarioId: encontrado.id,
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
        onSuccess: (resultado) => {
          // A quantidade vem do que foi VALIDADO aqui: a resposta traz saldo e ids, não o valor.
          // E `replay` é dito em voz alta — num retry de rede, "concluída" faria a pessoa acreditar
          // que transferiu duas vezes.
          setAnuncio(
            resultado.replay
              ? `Esta transferência já havia sido feita. Nada foi enviado de novo. Seu saldo é ${resultado.saldoTokensRemetente} tokens.`
              : `Transferência concluída. ${analise.data.tokens} tokens enviados para ${encontrado.nome}. Seu saldo agora é ${resultado.saldoTokensRemetente} tokens.`,
          );
          chave.current = novaChaveIdempotencia();
          setTransferirAberto(false);
          setHandle('');
          setEncontrado(null);
          busca.reset();
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
            <TituloTela>Carteira</TituloTela>

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
                AQUI HAVIA "Sacar em reais": visível, desabilitado, com o motivo ao lado.

                A justificativa antiga era boa — "um botão ausente não ensina nada", e desabilitado
                com motivo responde à pergunta antes de ela ser feita — e ainda assim envelheceu. O
                app promete resgate em benefício de parceiro em TRÊS lugares (o card acima,
                `SaldoToken` e o onboarding) e não oferecia porta nenhuma; o que oferecia era um
                botão que só sabia dizer não. Um aviso ensina o que a moeda NÃO é. Um catálogo mostra
                o que ela É — que é a tese do produto e o sumidouro do TOKEN (ADR 0009 §3).

                O gate do servidor continua existindo e continua coberto: `POST /carteira/saques`
                responde 422 `saque-desabilitado`, `sacar()` segue na camada de API e o teste do 422
                segue no lugar. O que saiu é a UI, não a integração.
              */}
              <Botao
                titulo="Resgatar benefícios"
                variante="secundario"
                onPress={() => router.push('/beneficios')}
                estilo={estilos.acaoLarga}
                testID="botao-abrir-beneficios"
              />
            </View>

            <TituloTela nivel="secao">Extrato</TituloTela>
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
          rotulo="@ do vizinho"
          value={handle}
          onChangeText={trocarHandle}
          autoCapitalize="none"
          autoCorrect={false}
          erro={errosForm.destinatarioId}
          testID="campo-destinatario"
        />

        {/*
          Ação EXPLÍCITA, não busca enquanto digita: uma requisição por tecla consumiria em segundos
          o teto próprio do endpoint, e seria busca por prefixo na prática — que é o que o ADR 0028
          recusa, porque prefixo é listagem com outro nome.
        */}
        <Botao
          titulo="Buscar vizinho"
          variante="secundario"
          carregando={busca.isPending}
          onPress={procurarVizinho}
          testID="botao-buscar-handle"
        />

        {busca.error ? (
          // Inexistente, de outra tribo e conta inativa chegam como o MESMO `naoEncontrado`.
          // Distingui-los aqui recriaria no cliente o oráculo de enumeração que o servidor fechou.
          <Aviso
            tom="informacao"
            mensagem="Nenhum vizinho com esse @ na sua tribo. Confira a escrita com a pessoa."
            testID="erro-busca-handle"
          />
        ) : null}

        {encontrado ? (
          // A CONFIRMAÇÃO pelo nome é o ponto da tarefa: `lancamento` é append-only e a
          // transferência não tem volta. Ver um UUID não permite conferir nada; ver "Marlene Souza,
          // Tribo Cidade Líder" permite.
          <Card estilo={estilos.destinatario}>
            <Text
              style={estilos.nomeDestinatario}
              accessibilityRole="text"
              accessibilityLabel={`Destinatário confirmado: ${encontrado.nome}, @${encontrado.handle}${
                encontrado.tribo ? `, ${encontrado.tribo}` : ''
              }`}
            >
              {encontrado.nome}
            </Text>
            <Text style={estilos.handleDestinatario}>
              @{encontrado.handle}
              {encontrado.tribo ? ` · ${encontrado.tribo}` : ''}
            </Text>
          </Card>
        ) : null}
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
          titulo={encontrado ? `Transferir para ${encontrado.nome}` : 'Transferir'}
          hint="A transferência é imediata e não pode ser desfeita."
          carregando={transferir.isPending}
          // Sem destinatário confirmado não há o que transferir. O botão desabilitado é a segunda
          // metade da confirmação: ele só acorda depois que a pessoa viu o nome.
          disabled={!encontrado}
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
        {/* TEXTO usa `textoAcessivel`: `cores.coral` como cor de texto dá 3,20:1, abaixo do mínimo
            de 4,5:1 — e aqui é o sinal que distingue entrada de saída no extrato, informação que
            não pode depender de enxergar bem. `verdeEscuro` é a variante de texto do verde, que já
            atendia. Ver a regra em `src/theme/tokens.ts`. */}
        <Text
          style={[estilos.sinal, { color: entrada ? cores.verdeEscuro : textoAcessivel.coral }]}
          // O sinal vive no rótulo do SaldoToken ao lado; aqui ele é só a marca visual.
          accessibilityElementsHidden
          importantForAccessibility="no"
        >
          {entrada ? '+' : '−'}
        </Text>
        <SaldoToken
          tokens={lancamento.valorTokens}
          // O "+"/"−" ao lado é uma <Text> com um único caractere de pontuação, e motor de TTS
          // costuma não pronunciá-lo — crédito e débito ficavam indistinguíveis por voz, no extrato,
          // onde a direção do lançamento é a informação principal.
          prefixoAcessivel={entrada ? 'mais ' : 'menos '}
          cor={entrada ? cores.verdeEscuro : textoAcessivel.coral}
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
  destinatario: { gap: 2 },
  nomeDestinatario: { ...tipografia.subtitulo, color: cores.tinta },
  handleDestinatario: { ...tipografia.legenda, color: cores.tinta70 },
  linha: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  linhaTexto: { flex: 1, gap: espaco.xs },
  motivo: { ...tipografia.rotulo, color: cores.tinta },
  data: { ...tipografia.legenda, color: textoAcessivel.suave },
  mensagem: { ...tipografia.legenda, color: cores.tinta70 },
  valor: { flexDirection: 'row', alignItems: 'center', gap: espaco.xs },
  sinal: { ...tipografia.subtitulo },
});
