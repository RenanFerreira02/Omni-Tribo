import { useLocalSearchParams } from 'expo-router';
import { useRef, useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';

import { mensagemDe } from '@/api/erros';
import type { MissaoResponse } from '@/api/tipos';
import { Botao } from '@/components/Botao';
import { Card } from '@/components/Card';
import { Chip } from '@/components/Chip';
import { Esqueleto } from '@/components/Esqueleto';
import { EstadoVazio } from '@/components/EstadoVazio';
import { SaldoToken } from '@/components/SaldoToken';
import { useAcaoMissao, useCheckin, useMissao } from '@/features/missoes/hooks';
import { orientacaoDe, type OrientacaoCheckin } from '@/features/missoes/mensagensCheckin';
import { useLocalizacao } from '@/features/missoes/useLocalizacao';
import { formatarDataHora, rotuloCategoria, rotuloStatus } from '@/lib/formatar';
import { novaChaveIdempotencia } from '@/lib/ids';
import { useSessao } from '@/stores/sessao';
import { cores, coresCategoria, espaco, tipografia } from '@/theme';

export default function DetalheMissao() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const usuario = useSessao((estado) => estado.usuario);
  const consulta = useMissao(id);
  const acao = useAcaoMissao(id);
  const checkin = useCheckin(id);
  const { recarregar: obterLocalizacao } = useLocalizacao(false);

  const [orientacao, setOrientacao] = useState<OrientacaoCheckin | null>(null);

  /**
   * A chave de idempotência nasce com a INTENÇÃO e sobrevive aos retries dela.
   *
   * Guardar num ref, e não gerar dentro do `mutate`, é o que faz o retry de rede repetir a MESMA
   * chave: o servidor devolve o resultado anterior em vez de gravar um segundo check-in. Ela só é
   * trocada depois de um desfecho definitivo — senão o usuário ficaria preso no replay eterno do
   * primeiro resultado, inclusive no de uma rejeição.
   */
  const chaveCheckin = useRef<string>(novaChaveIdempotencia());

  if (consulta.isLoading) {
    return (
      <View style={estilos.corpo}>
        <Esqueleto largura="50%" altura={20} />
        <Esqueleto altura={14} />
        <Esqueleto altura={14} />
      </View>
    );
  }

  if (consulta.error || !consulta.data) {
    return (
      <EstadoVazio
        titulo="Missão indisponível"
        descricao={consulta.error ? mensagemDe(consulta.error) : undefined}
        acao={{ rotulo: 'Tentar de novo', onPress: () => void consulta.refetch() }}
      />
    );
  }

  const missao = consulta.data;
  const souCriador = usuario?.id === missao.criadorId;
  const souExecutor = usuario?.id === missao.executorId;
  const paleta = coresCategoria[missao.categoria];

  async function fazerCheckin() {
    setOrientacao(null);
    const posicao = await obterLocalizacao();
    if (!posicao) {
      setOrientacao({
        titulo: 'Sem acesso à localização',
        instrucao: 'Autorize o acesso à localização para fazer o check-in.',
        vaiAdiantarTentarDeNovo: false,
      });
      return;
    }

    checkin.mutate(
      {
        // Enviamos o que o aparelho leu. A distância é medida no servidor pelo PostGIS; o que o app
        // manda é a alegação, não a prova.
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
        onError: (erro) => {
          const guia = orientacaoDe(erro);
          setOrientacao(guia);
          // Rejeição já gravada no servidor: a chave está consumida e reusá-la só devolveria o
          // replay da mesma recusa. Uma tentativa nova precisa de chave nova — exceto quando nada
          // foi decidido lá (rede caiu), caso em que repetir a chave é justamente o certo.
          if (!guia.vaiAdiantarTentarDeNovo || erro.tipo !== 'semRede') {
            chaveCheckin.current = novaChaveIdempotencia();
          }
        },
      },
    );
  }

  const erroAcao = acao.error;

  return (
    <ScrollView contentContainerStyle={estilos.corpo}>
      <View style={estilos.topo}>
        <Chip
          rotulo={rotuloCategoria(missao.categoria)}
          corFundo={paleta.fundo}
          corTexto={paleta.texto}
        />
        <Chip rotulo={rotuloStatus(missao.status)} />
      </View>

      <Text style={estilos.titulo}>{missao.titulo}</Text>
      <Text style={estilos.descricao}>{missao.descricao}</Text>

      <Card>
        <Text style={estilos.rotulo}>Recompensa</Text>
        <View style={estilos.recompensa}>
          <View style={estilos.xp}>
            <Text style={estilos.xpValor}>{missao.xpRecompensa}</Text>
            <Text style={estilos.xpRotulo}>XP</Text>
          </View>
          <SaldoToken tokens={missao.tokensRecompensa} tamanho="grande" />
        </View>
        <Text style={estilos.nota}>
          Calculada pelo servidor na criação e congelada — não muda depois que você aceita.
        </Text>
      </Card>

      <Card>
        <Linha rotulo="Endereço" valor={`${missao.logradouro}, ${missao.bairro}`} />
        <Linha rotulo="Cidade" valor={`${missao.cidade}/${missao.uf}`} />
        <Linha rotulo="Raio de check-in" valor={`${missao.raioCheckinM} m`} />
        <Linha
          rotulo="Janela"
          valor={`${formatarDataHora(missao.janelaInicio)} → ${formatarDataHora(missao.janelaFim)}`}
        />
      </Card>

      {orientacao ? (
        <Card estilo={estilos.alerta} testID="orientacao-checkin">
          <Text style={estilos.alertaTitulo}>{orientacao.titulo}</Text>
          <Text style={estilos.alertaTexto}>{orientacao.instrucao}</Text>
        </Card>
      ) : null}

      {erroAcao ? (
        <Text style={estilos.erro} testID="erro-acao">
          {mensagemDe(erroAcao)}
        </Text>
      ) : null}

      <View style={estilos.acoes}>
        <Acoes
          missao={missao}
          souCriador={souCriador}
          souExecutor={souExecutor}
          pendente={acao.isPending}
          aoAgir={(qual) => acao.mutate({ acao: qual })}
          checkinPendente={checkin.isPending}
          aoFazerCheckin={fazerCheckin}
        />
      </View>
    </ScrollView>
  );
}

interface AcoesProps {
  missao: MissaoResponse;
  souCriador: boolean;
  souExecutor: boolean;
  pendente: boolean;
  aoAgir: (
    acao: 'publicar' | 'aceitar' | 'iniciar' | 'desistir' | 'cancelar' | 'confirmar',
  ) => void;
  checkinPendente: boolean;
  aoFazerCheckin: () => void;
}

/** As ações espelham a máquina de estados do servidor — que continua sendo a autoridade. */
function Acoes({
  missao,
  souCriador,
  souExecutor,
  pendente,
  aoAgir,
  checkinPendente,
  aoFazerCheckin,
}: AcoesProps) {
  if (missao.status === 'RASCUNHO' && souCriador) {
    return <Botao titulo="Publicar" onPress={() => aoAgir('publicar')} carregando={pendente} />;
  }
  if (missao.status === 'ABERTA') {
    return souCriador ? (
      <Botao
        titulo="Cancelar missão"
        variante="secundario"
        onPress={() => aoAgir('cancelar')}
        carregando={pendente}
      />
    ) : (
      <Botao
        titulo="Aceitar missão"
        onPress={() => aoAgir('aceitar')}
        carregando={pendente}
        testID="botao-aceitar"
      />
    );
  }
  if (missao.status === 'ACEITA' && souExecutor) {
    return (
      <>
        <Botao titulo="Iniciar" onPress={() => aoAgir('iniciar')} carregando={pendente} />
        <Botao
          titulo="Desistir"
          variante="texto"
          onPress={() => aoAgir('desistir')}
          carregando={pendente}
        />
      </>
    );
  }
  if (missao.status === 'EM_ANDAMENTO' && souExecutor) {
    return (
      <Botao
        titulo="Fazer check-in"
        onPress={aoFazerCheckin}
        carregando={checkinPendente}
        testID="botao-checkin"
      />
    );
  }
  if (missao.status === 'AGUARDANDO_CONFIRMACAO' && souCriador) {
    return (
      <Botao
        titulo="Confirmar conclusão"
        onPress={() => aoAgir('confirmar')}
        carregando={pendente}
        testID="botao-confirmar"
      />
    );
  }
  return null;
}

function Linha({ rotulo, valor }: { rotulo: string; valor: string }) {
  return (
    <View style={estilos.linha}>
      <Text style={estilos.rotulo}>{rotulo}</Text>
      <Text style={estilos.valor}>{valor}</Text>
    </View>
  );
}

const estilos = StyleSheet.create({
  corpo: { padding: espaco.lg, gap: espaco.lg, backgroundColor: cores.papel, flexGrow: 1 },
  topo: { flexDirection: 'row', gap: espaco.sm },
  titulo: { ...tipografia.titulo, color: cores.tinta },
  descricao: { ...tipografia.corpo, color: cores.tinta70 },
  recompensa: { flexDirection: 'row', alignItems: 'center', gap: espaco.xl },
  xp: { flexDirection: 'row', alignItems: 'baseline', gap: espaco.xs },
  xpValor: { fontSize: 34, lineHeight: 40, fontWeight: '700', color: cores.ambar },
  xpRotulo: { ...tipografia.rotulo, color: cores.ambar },
  nota: { ...tipografia.legenda, color: cores.tinta50 },
  linha: { gap: espaco.xs },
  rotulo: { ...tipografia.legenda, color: cores.tinta50 },
  valor: { ...tipografia.corpo, color: cores.tinta },
  alerta: { backgroundColor: cores.coralClaro, borderColor: cores.coral },
  alertaTitulo: { ...tipografia.subtitulo, color: cores.coral },
  alertaTexto: { ...tipografia.corpo, color: cores.tinta },
  erro: { ...tipografia.corpo, color: cores.coral },
  acoes: { gap: espaco.sm, marginTop: 'auto' },
});
