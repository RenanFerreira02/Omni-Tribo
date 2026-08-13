import { useRouter } from 'expo-router';
import { useCallback, useMemo, useState } from 'react';
import { Linking, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { mensagemDe } from '@/api/erros';
import type { MissaoProximaResponse, PontoCustodiaResponse } from '@/api/tipos';
import { Aviso } from '@/components/Aviso';
import { Botao } from '@/components/Botao';
import { Card } from '@/components/Card';
import { FolhaInferior } from '@/components/FolhaInferior';
import { JustificativaLocalizacao } from '@/components/JustificativaLocalizacao';
import { MapaLeaflet, type MarcadorMapa, type RegiaoMapa } from '@/components/MapaLeaflet';
import { SaldoToken } from '@/components/SaldoToken';
import { useClima, usePontosCustodiaProximos, useTribo } from '@/features/mapa/hooks';
import { useMissoesProximas } from '@/features/missoes/hooks';
import { useLocalizacao } from '@/features/missoes/useLocalizacao';
import { usePerfil } from '@/features/perfil/hooks';
import { useCallbackComDebounce } from '@/lib/debounce';
import { formatarDistancia, rotuloCategoria } from '@/lib/formatar';
import { cores, coresCategoria, espaco, textoAcessivel, tipografia } from '@/theme';

/** Fallback final: centro de São Paulo, quando não há nem GPS nem tribo com missões. */
const CENTRO_PADRAO = { lat: -23.5505, lon: -46.6333 };

export default function TelaMapa() {
  const router = useRouter();

  // `pedirAoMontar = false` é o ponto da tela: o diálogo do sistema NÃO abre sozinho. Quem o
  // dispara é o toque no botão do card de justificativa, depois de a pessoa ler para quê.
  const { coordenada, estado: estadoLocal, recarregar } = useLocalizacao(false);

  const perfil = usePerfil();
  const tribo = useTribo(perfil.data?.tribo?.id);

  const [regiao, setRegiao] = useState<RegiaoMapa | null>(null);
  const [selecionado, setSelecionado] = useState<MissaoProximaResponse | null>(null);
  const [pontoSelecionado, setPontoSelecionado] = useState<PontoCustodiaResponse | null>(null);

  /**
   * Onde o mapa aponta, em ordem de preferência: onde o usuário está → centro da tribo → São Paulo.
   *
   * A degradação graciosa exigida quando a permissão é negada mora aqui: sem GPS, o mapa mostra o
   * BAIRRO DA TRIBO, que é uma resposta útil, em vez de um mapa-múndi ou uma tela vazia.
   */
  const centro = useMemo(() => {
    if (coordenada) return { lat: coordenada.lat, lon: coordenada.lon };
    if (tribo.data?.centroLat != null && tribo.data.centroLon != null) {
      return { lat: tribo.data.centroLat, lon: tribo.data.centroLon };
    }
    return CENTRO_PADRAO;
  }, [coordenada, tribo.data]);

  // As consultas seguem a REGIÃO visível, não a posição do usuário: quem arrasta o mapa quer ver o
  // que há lá, não o que há em volta de si.
  const foco = regiao ? { lat: regiao.lat, lon: regiao.lon } : centro;
  const raio = Math.min(regiao?.raioM ?? 2000, 20000);

  const missoes = useMissoesProximas(foco);
  const pontos = usePontosCustodiaProximos(foco, raio);
  const clima = useClima(foco);

  // 500 ms: `moveend` do Leaflet já dispara uma vez por gesto, mas arrastar o mapa é uma sequência
  // de gestos curtos. Sem o debounce, atravessar o bairro custaria uma dúzia de consultas
  // geoespaciais.
  const aoMudarRegiao = useCallbackComDebounce((nova: RegiaoMapa) => setRegiao(nova), 500);

  const marcadores: MarcadorMapa[] = useMemo(() => {
    // `flatMap` e não `map`: missão sem coordenada é DESCARTADA, não plantada em (0, 0).
    //
    // O `?? 0` anterior punha o marcador no Golfo da Guiné — um pino a milhares de quilômetros, com
    // o rótulo de uma missão do bairro. Some da tela é o desfecho honesto: o radar já ordena por
    // distância, e uma missão sem origem não tem lugar num mapa.
    //
    // A coordenada aqui vem ARREDONDADA pelo servidor (3 casas, ~110 m) porque o radar só devolve
    // missão de terceiro. É por isso que o pino não coincide exatamente com o endereço, enquanto o
    // rótulo de distância ao lado é medido com precisão plena pelo PostGIS: a divergência é
    // deliberada, e some quando a pessoa aceita a missão.
    const deMissoes = (missoes.data ?? []).flatMap<MarcadorMapa>((item) => {
      const { origemLat, origemLon } = item.missao;
      if (origemLat === null || origemLon === null) return [];
      return [
        {
          id: `missao:${item.missao.id}`,
          lat: origemLat,
          lon: origemLon,
          cor: coresCategoria[item.missao.categoria].texto,
          forma: 'pino',
          rotulo: item.missao.titulo,
        },
      ];
    });

    const dePontos = (pontos.data ?? []).map<MarcadorMapa>((ponto) => ({
      id: `ponto:${ponto.id}`,
      lat: ponto.lat,
      lon: ponto.lon,
      // Forma distinta, e não só cor: no mapa não há texto ao lado para desempatar, e distinguir
      // só por cor deixa os dois tipos indistinguíveis para daltonismo.
      cor: cores.tinta70,
      forma: 'quadrado',
      rotulo: ponto.apelido,
    }));

    return [...deMissoes, ...dePontos];
  }, [missoes.data, pontos.data]);

  const aoTocarMarcador = useCallback(
    (id: string) => {
      const [tipo, alvo] = id.split(':');
      if (tipo === 'missao') {
        setPontoSelecionado(null);
        setSelecionado((missoes.data ?? []).find((m) => m.missao.id === alvo) ?? null);
      } else {
        setSelecionado(null);
        setPontoSelecionado((pontos.data ?? []).find((p) => p.id === alvo) ?? null);
      }
    },
    [missoes.data, pontos.data],
  );

  // ─── Justificativa ANTES do prompt do sistema ───────────────────────────────────────────────
  //
  // O diálogo nativo é de uma via só: negado uma vez, o Android não o mostra de novo, e a pessoa
  // precisa ir às configurações. Pedir sem explicar desperdiça a única chance que existe.
  if (estadoLocal === 'inicial') {
    return (
      <SafeAreaView style={estilos.raiz} testID="tela-mapa">
        <JustificativaLocalizacao
          proposito="O mapa usa sua localização para centralizar no seu bairro e medir a distância até cada missão."
          semPermissao="Sem permissão o mapa continua funcionando, centrado na sua tribo."
          aoPermitir={recarregar}
          testID="justificativa-localizacao"
        />
      </SafeAreaView>
    );
  }

  const permissaoNegada = estadoLocal === 'negada' || estadoLocal === 'erro';

  return (
    <SafeAreaView style={estilos.raiz} testID="tela-mapa">
      {permissaoNegada ? (
        <View style={estilos.faixa}>
          <Aviso
            tom="atencao"
            titulo="Sem acesso à localização"
            mensagem={
              tribo.data
                ? `Mostrando o mapa em ${tribo.data.bairro}, o bairro da sua tribo. Sua posição não aparece.`
                : 'Mostrando uma região padrão. Sua posição não aparece.'
            }
            testID="aviso-localizacao"
          />
          <Botao
            titulo="Abrir configurações"
            variante="texto"
            onPress={() => void Linking.openSettings()}
            testID="botao-configuracoes"
          />
        </View>
      ) : null}

      {/* Card de clima: some por completo quando o provedor externo está fora do ar (503). Uma
          degradação prevista não pode virar mensagem de erro na cara do usuário. Ver ADR 0011. */}
      {clima.data ? (
        <View style={estilos.faixa} testID="card-clima">
          <Card estilo={estilos.clima}>
            <Text style={estilos.temperatura}>{Math.round(clima.data.temperaturaC)}°</Text>
            <View style={estilos.climaTexto}>
              <Text style={estilos.climaDescricao}>{clima.data.descricao}</Text>
              <Text style={estilos.climaSensacao}>
                sensação {Math.round(clima.data.sensacaoC)}°
              </Text>
            </View>
          </Card>
        </View>
      ) : null}

      <MapaLeaflet
        centro={centro}
        marcadores={marcadores}
        mostrarUsuario={coordenada !== null}
        aoTocarMarcador={aoTocarMarcador}
        aoMudarRegiao={aoMudarRegiao}
        testID="mapa"
      />

      {missoes.error ? (
        <View style={estilos.faixaFlutuante}>
          <Aviso tom="erro" mensagem={mensagemDe(missoes.error)} testID="erro-mapa" />
        </View>
      ) : null}

      <FolhaInferior
        visivel={selecionado !== null}
        aoFechar={() => setSelecionado(null)}
        titulo={selecionado?.missao.titulo}
        testID="folha-missao"
      >
        {selecionado ? (
          <View style={estilos.resumo}>
            <Text style={estilos.linhaResumo}>
              {rotuloCategoria(selecionado.missao.categoria)} · a{' '}
              {formatarDistancia(selecionado.distanciaM)}
            </Text>
            <Text style={estilos.linhaResumo}>
              {selecionado.missao.bairro}, {selecionado.missao.cidade}
            </Text>
            <View style={estilos.recompensa}>
              <Text style={estilos.xp}>{selecionado.missao.xpRecompensa} XP</Text>
              <SaldoToken tokens={selecionado.missao.tokensRecompensa} />
            </View>
            <Botao
              titulo="Ver missão"
              onPress={() => {
                const id = selecionado.missao.id;
                setSelecionado(null);
                router.push(`/missao/${id}`);
              }}
              testID="botao-ver-missao"
            />
          </View>
        ) : null}
      </FolhaInferior>

      <FolhaInferior
        visivel={pontoSelecionado !== null}
        aoFechar={() => setPontoSelecionado(null)}
        titulo={pontoSelecionado?.apelido}
        testID="folha-ponto"
      >
        {pontoSelecionado ? (
          <View style={estilos.resumo}>
            <Text style={estilos.linhaResumo}>
              Ponto de custódia · {pontoSelecionado.tipo.toLowerCase()}
            </Text>
            <Text style={estilos.linhaResumo}>Código {pontoSelecionado.codigo}</Text>
            {pontoSelecionado.distanciaM !== null ? (
              <Text style={estilos.linhaResumo}>
                a {formatarDistancia(pontoSelecionado.distanciaM)}
              </Text>
            ) : null}
            <Text style={estilos.linhaResumo}>
              Ocupação {pontoSelecionado.ocupacao} de {pontoSelecionado.capacidade}
            </Text>
          </View>
        ) : null}
      </FolhaInferior>
    </SafeAreaView>
  );
}

const estilos = StyleSheet.create({
  raiz: { flex: 1, backgroundColor: cores.papel },
  justificativa: { flex: 1, justifyContent: 'center', padding: espaco.lg },
  tituloJustificativa: { ...tipografia.subtitulo, color: cores.tinta },
  textoJustificativa: { ...tipografia.corpo, color: cores.tinta70 },
  faixa: { paddingHorizontal: espaco.lg, paddingBottom: espaco.sm, gap: espaco.xs },
  faixaFlutuante: { position: 'absolute', bottom: espaco.lg, left: espaco.lg, right: espaco.lg },
  clima: { flexDirection: 'row', alignItems: 'center', gap: espaco.md },
  temperatura: { ...tipografia.titulo, color: cores.verdeEscuro },
  climaTexto: { gap: 2 },
  climaDescricao: { ...tipografia.rotulo, color: cores.tinta },
  climaSensacao: { ...tipografia.legenda, color: textoAcessivel.suave },
  resumo: { gap: espaco.sm },
  linhaResumo: { ...tipografia.corpo, color: cores.tinta70 },
  recompensa: { flexDirection: 'row', alignItems: 'center', gap: espaco.lg },
  xp: { ...tipografia.rotulo, color: textoAcessivel.ambar },
});
