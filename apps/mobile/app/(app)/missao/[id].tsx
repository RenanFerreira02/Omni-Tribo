import { useLocalSearchParams, useRouter } from 'expo-router';
import { useRef, useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { mensagemDe, type ErroApi } from '@/api/erros';
import { Aviso } from '@/components/Aviso';
import { Botao } from '@/components/Botao';
import { Card } from '@/components/Card';
import { Chip } from '@/components/Chip';
import { DialogoConfirmacao } from '@/components/DialogoConfirmacao';
import { Esqueleto } from '@/components/Esqueleto';
import { EstadoVazio } from '@/components/EstadoVazio';
import { SaldoToken } from '@/components/SaldoToken';
import { acoesDisponiveis, papelNaMissao, type AcaoDisponivel } from '@/features/missoes/acoes';
import { useAcaoMissao, useCheckin, useMissao } from '@/features/missoes/hooks';
import { orientacaoDe, type OrientacaoCheckin } from '@/features/missoes/mensagensCheckin';
import { ROTULO_COMPLEXIDADE } from '@/features/missoes/rotulos';
import { useLocalizacao } from '@/features/missoes/useLocalizacao';
import { usePontoCustodia } from '@/features/mapa/hooks';
import { formatarDataHora, rotuloCategoria, rotuloStatus } from '@/lib/formatar';
import { novaChaveIdempotencia } from '@/lib/ids';
import { useSessao } from '@/stores/sessao';
import { cores, coresCategoria, coresStatus, espaco, textoAcessivel, tipografia } from '@/theme';

export default function DetalheMissao() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const router = useRouter();
  const usuario = useSessao((estado) => estado.usuario);

  const { data: missao, isLoading, error, refetch } = useMissao(id);
  const acao = useAcaoMissao(id);
  const checkin = useCheckin(id);
  const { recarregar: obterLocalizacao } = useLocalizacao(false);

  const [orientacao, setOrientacao] = useState<OrientacaoCheckin | null>(null);
  const [aConfirmar, setAConfirmar] = useState<AcaoDisponivel | null>(null);

  /**
   * A chave de idempotência nasce na MONTAGEM e sobrevive às tentativas.
   *
   * É o que faz um retry de rede repetir o mesmo check-in em vez de gravar um segundo. Ela só é
   * rotacionada quando a tentativa produziu um veredito do servidor — insistir com a mesma chave
   * depois de uma rejeição devolveria o replay daquela rejeição para sempre.
   */
  const chaveCheckin = useRef(novaChaveIdempotencia());

  if (isLoading) {
    return (
      <SafeAreaView style={estilos.raiz}>
        <View style={estilos.conteudo} testID="detalhe-carregando">
          <Esqueleto altura={28} />
          <Esqueleto altura={80} />
          <Esqueleto altura={120} />
        </View>
      </SafeAreaView>
    );
  }

  if (error || !missao) {
    return (
      <SafeAreaView style={estilos.raiz}>
        <EstadoVazio
          titulo="Não foi possível carregar a missão"
          descricao={error ? mensagemDe(error) : undefined}
          acao={{ rotulo: 'Tentar de novo', onPress: () => void refetch() }}
          testID="detalhe-erro"
        />
      </SafeAreaView>
    );
  }

  const papel = papelNaMissao(missao, usuario?.id);
  const estado = acoesDisponiveis(missao.status, papel);
  const paletaCategoria = coresCategoria[missao.categoria];
  const paletaStatus = coresStatus[missao.status];

  async function fazerCheckin() {
    setOrientacao(null);
    const posicao = await obterLocalizacao();
    if (!posicao) {
      setOrientacao({
        titulo: 'Sem acesso à localização',
        instrucao:
          'O check-in prova que você chegou ao local, então precisa da sua posição. Autorize o acesso nas configurações do aparelho.',
        vaiAdiantarTentarDeNovo: false,
      });
      return;
    }

    checkin.mutate(
      {
        // Enviados verbatim do sensor. O app NÃO julga nem filtra: a régua é do servidor, e
        // "melhorar" o número aqui seria fraude de nossa parte.
        corpo: {
          lat: posicao.lat,
          lon: posicao.lon,
          acuraciaM: posicao.acuraciaM,
          mocked: posicao.mocked,
        },
        chaveIdempotencia: chaveCheckin.current,
      },
      {
        onSuccess: () => {
          chaveCheckin.current = novaChaveIdempotencia();
          setOrientacao(null);
        },
        onError: (erro: ErroApi) => {
          const guia = orientacaoDe(erro);
          setOrientacao(guia);
          // Sem rede, a requisição pode nem ter chegado — manter a chave é o que permite ao retry
          // ser reconhecido como a MESMA tentativa. Com veredito do servidor, chave nova.
          if (erro.tipo !== 'semRede') {
            chaveCheckin.current = novaChaveIdempotencia();
          }
        },
      },
    );
  }

  function disparar(item: AcaoDisponivel) {
    if (item.acao === 'checkin') {
      void fazerCheckin();
      return;
    }
    // Sem ` as AcaoMissao`: o early return acima já estreita o tipo, e a asserção só silenciaria
    // um erro real se `AcaoMissao` mudasse.
    acao.mutate({ acao: item.acao });
  }

  function aoTocar(item: AcaoDisponivel) {
    // Confirmação só no que não tem volta. Aceitar não pede — quem aceitou pode desistir, e um
    // diálogo ali somaria um toque ao caminho mais comum do app.
    if (item.irreversivel) setAConfirmar(item);
    else disparar(item);
  }

  const erroAcao = acao.error;
  const ocupado = acao.isPending || checkin.isPending;

  return (
    <SafeAreaView style={estilos.raiz}>
      <ScrollView contentContainerStyle={estilos.conteudo}>
        <View style={estilos.chips}>
          <Chip
            rotulo={rotuloCategoria(missao.categoria)}
            corFundo={paletaCategoria.fundo}
            corTexto={paletaCategoria.texto}
            testID="chip-categoria"
          />
          <Chip
            rotulo={rotuloStatus(missao.status)}
            corFundo={paletaStatus.fundo}
            corTexto={paletaStatus.texto}
            testID="chip-status"
          />
        </View>

        <Text style={estilos.titulo} accessibilityRole="header">
          {missao.titulo}
        </Text>
        <Text style={estilos.descricao}>{missao.descricao}</Text>

        <Card>
          <Text style={estilos.rotulo}>Recompensa</Text>
          {/* XP e TOKEN. `valorBrl` existe no DTO, chega sempre 0 e NÃO é exibido: mostrar
              "R$ 0,00" sugeriria que um dia haverá outro número ali. Ver ADR 0009. */}
          <View style={estilos.recompensa}>
            <Text style={estilos.xp}>{missao.xpRecompensa} XP</Text>
            <SaldoToken tokens={missao.tokensRecompensa} testID="recompensa-tokens" />
          </View>
          {/* POR QUE vale isto. A recompensa é calculada pelo servidor e congelada na criação; sem
              a complexidade efetiva, o número aparecia sem explicação nenhuma e o usuário não tinha
              como relacioná-lo ao esforço. Em ENTREGA e COLETA ela é DERIVADA de peso e volume — é a
              resposta para "por que aquela missão paga mais que a minha?". */}
          <Text style={estilos.legenda} testID="complexidade">
            Complexidade: {ROTULO_COMPLEXIDADE[missao.complexidade]}
            {missao.pesoKg !== null && missao.volumeL !== null
              ? ` — calculada a partir de ${missao.pesoKg} kg e ${missao.volumeL} L`
              : ' — informada por quem criou'}
          </Text>
        </Card>

        <Card>
          <Text style={estilos.rotulo}>Onde</Text>
          {/* O servidor OCULTA logradouro e CEP de quem não participa da missão, e manda a
              coordenada arredondada — a listagem antes entregava o endereço exato de toda missão do
              bairro a qualquer autenticado. Renderizar `null` direto não quebra, mas deixa uma linha
              vazia que ninguém entende; dizer POR QUE está oculto transforma a ausência em
              informação, e explica o que a pessoa ganha ao aceitar. */}
          {missao.logradouro ? (
            <Text style={estilos.linha}>{missao.logradouro}</Text>
          ) : (
            <Text style={estilos.legenda} testID="endereco-oculto">
              O endereço completo aparece quando você aceitar a missão.
            </Text>
          )}
          <Text style={estilos.linha}>
            {missao.bairro}, {missao.cidade} — {missao.uf}
          </Text>
          <Text style={estilos.legenda}>Raio de check-in: {missao.raioCheckinM} m</Text>
          <PontoDeCustodia id={missao.pontoCustodiaId} />
        </Card>

        <Card>
          <Text style={estilos.rotulo}>Janela</Text>
          <Text style={estilos.linha}>
            {formatarDataHora(missao.janelaInicio)} até {formatarDataHora(missao.janelaFim)}
          </Text>
        </Card>

        {orientacao ? (
          <Aviso
            tom="atencao"
            titulo={orientacao.titulo}
            mensagem={orientacao.instrucao}
            testID="orientacao-checkin"
          />
        ) : null}

        {erroAcao ? (
          <Aviso
            tom={erroAcao.tipo === 'transicaoInvalida' ? 'atencao' : 'erro'}
            titulo={tituloDoErro(erroAcao)}
            mensagem={mensagemDoErro(erroAcao)}
            testID="erro-acao"
          />
        ) : null}

        <View style={estilos.acoes}>
          {estado.explicacao ? (
            <Text style={estilos.explicacao} testID="explicacao-sem-acao">
              {estado.explicacao}
            </Text>
          ) : null}

          {estado.acoes.map((item) => (
            <Botao
              key={item.acao}
              titulo={item.rotulo}
              variante={item.variante}
              carregando={ocupado && item.variante === 'primario'}
              disabled={ocupado}
              onPress={() => aoTocar(item)}
              testID={`acao-${item.acao}`}
            />
          ))}
        </View>
      </ScrollView>

      <DialogoConfirmacao
        visivel={aConfirmar !== null}
        titulo={aConfirmar?.confirmacao?.titulo ?? ''}
        mensagem={aConfirmar?.confirmacao?.mensagem ?? ''}
        rotuloConfirmar={aConfirmar?.rotulo ?? 'Confirmar'}
        rotuloCancelar="Voltar"
        destrutivo
        carregando={ocupado}
        aoConfirmar={() => {
          const item = aConfirmar;
          setAConfirmar(null);
          if (item) disparar(item);
        }}
        aoCancelar={() => setAConfirmar(null)}
        testID="dialogo-confirmacao"
      />

      <Botao
        titulo="Voltar"
        variante="texto"
        onPress={() => router.back()}
        estilo={estilos.voltar}
        testID="botao-voltar"
      />
    </SafeAreaView>
  );
}

/**
 * Resolve o `pontoCustodiaId` cru num nome legível.
 *
 * Era a Pendência #3: o app exibia um UUID onde deveria dizer "Leroy Merlin Pinheiros". Falha
 * silenciosamente — se o ponto foi desativado, o endpoint responde 404 e a linha simplesmente não
 * aparece, em vez de mostrar um erro por um detalhe complementar.
 */
function PontoDeCustodia({ id }: { id: string | null }) {
  const { data } = usePontoCustodia(id);
  if (!data) return null;

  return (
    <Text style={estilos.legenda} testID="ponto-custodia">
      Ponto de custódia: {data.apelido} ({data.codigo})
    </Text>
  );
}

/**
 * O 409 tem duas causas com reações OPOSTAS, e o `type` é o que as separa.
 *
 * `transicaoInvalida` é o caso de "outra pessoa aceitou primeiro": a tela já reverteu a atualização
 * otimista e recarregou; insistir não adianta. `conflitoConcorrencia` é colisão de versão, e aí
 * repetir tende a funcionar.
 */
function tituloDoErro(erro: ErroApi): string {
  if (erro.tipo === 'transicaoInvalida') return 'Esta missão mudou enquanto você olhava';
  if (erro.tipo === 'conflitoConcorrencia') return 'Alguém alterou a missão agora';
  return 'Não foi possível concluir';
}

function mensagemDoErro(erro: ErroApi): string {
  if (erro.tipo === 'transicaoInvalida') {
    return 'Outra pessoa aceitou primeiro, ou o estado mudou. A tela já está atualizada — veja o que dá para fazer agora.';
  }
  if (erro.tipo === 'conflitoConcorrencia') return 'Tente novamente.';
  return mensagemDe(erro);
}

const estilos = StyleSheet.create({
  raiz: { flex: 1, backgroundColor: cores.papel },
  conteudo: { padding: espaco.lg, gap: espaco.md, flexGrow: 1 },
  chips: { flexDirection: 'row', gap: espaco.sm },
  titulo: { ...tipografia.titulo, color: cores.tinta },
  descricao: { ...tipografia.corpo, color: cores.tinta70 },
  rotulo: { ...tipografia.rotulo, color: textoAcessivel.suave },
  linha: { ...tipografia.corpo, color: cores.tinta },
  legenda: { ...tipografia.legenda, color: textoAcessivel.suave },
  recompensa: { flexDirection: 'row', alignItems: 'center', gap: espaco.lg },
  xp: { ...tipografia.subtitulo, color: textoAcessivel.ambar },
  acoes: { marginTop: 'auto', gap: espaco.sm },
  explicacao: { ...tipografia.corpo, color: cores.tinta70, textAlign: 'center' },
  voltar: { marginHorizontal: espaco.lg, marginBottom: espaco.sm },
});
