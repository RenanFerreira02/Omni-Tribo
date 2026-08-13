import { useCallback, useEffect, useMemo, useRef } from 'react';
import { StyleSheet, View, type StyleProp, type ViewStyle } from 'react-native';
import { WebView } from 'react-native-webview';

import { cores } from '@/theme';

/**
 * Coage a número finito antes de interpolar em JavaScript da WebView.
 *
 * A página é nossa e o HTML é estático, mas `centro` vem de resposta de servidor, e o validador de
 * schema do app é `validarEmDev` — que só AVISA, e nem roda em produção. Sem esta barreira, um valor
 * inesperado seria concatenado direto dentro de `<script>`. `JSON.stringify` protege os marcadores;
 * a coordenada não tinha proteção nenhuma.
 *
 * Zero como fallback é seguro aqui porque só afeta o CENTRO da câmera: o pior caso é o mapa abrir no
 * Golfo da Guiné, visivelmente errado, em vez de executar algo.
 */
function numero(valor: unknown): number {
  return typeof valor === 'number' && Number.isFinite(valor) ? valor : 0;
}

export interface MarcadorMapa {
  id: string;
  lat: number;
  lon: number;
  /** Cor do pino. Vem de `coresCategoria` — a regra de lint proíbe hex literal fora do tema. */
  cor: string;
  /** `pino` para missão, `quadrado` para ponto de custódia. Forma, e não só cor: ver comentário. */
  forma: 'pino' | 'quadrado';
  rotulo: string;
}

export interface RegiaoMapa {
  lat: number;
  lon: number;
  /** Raio aproximado em metros do centro até a borda mais curta da viewport. */
  raioM: number;
}

interface Props {
  centro: { lat: number; lon: number };
  marcadores: MarcadorMapa[];
  /** Mostra o ponto azul do usuário. Falso quando a permissão foi negada. */
  mostrarUsuario?: boolean;
  aoTocarMarcador?: (id: string) => void;
  /** Já vem com debounce aplicado por quem chama — ver `useCallbackComDebounce`. */
  aoMudarRegiao?: (regiao: RegiaoMapa) => void;
  /** Modo seletor: um toque no mapa devolve a coordenada, para escolher o ponto da missão. */
  aoTocarMapa?: (lat: number, lon: number) => void;
  estilo?: StyleProp<ViewStyle>;
  testID?: string;
}

/**
 * Mapa: Leaflet + tiles do OpenStreetMap dentro de uma WebView. Ver **ADR 0012**.
 *
 * `react-native-maps` não roda no Expo Go desde o SDK 53 e, no Android, exige chave do Google Maps
 * — sem ela o mapa renderiza cinza. Este componente mantém o fluxo Expo Go do projeto e não pede
 * chave nenhuma.
 *
 * **É o ÚNICO arquivo do app que sabe que existe Leaflet.** A interface de props é a porta de saída
 * descrita no ADR: trocar por um mapa nativo, no dia em que houver chave e development build, é
 * escrever outra implementação com estas mesmas props, sem tocar em nenhuma tela.
 *
 * Marcador distingue por FORMA além de cor (`pino` para missão, `quadrado` para ponto de custódia).
 * Só a cor deixaria os dois tipos indistinguíveis para daltonismo — e num mapa não há texto ao lado
 * para desempatar.
 */
export function MapaLeaflet({
  centro,
  marcadores,
  mostrarUsuario = false,
  aoTocarMarcador,
  aoMudarRegiao,
  aoTocarMapa,
  estilo,
  testID,
}: Props) {
  const webview = useRef<WebView>(null);

  // O HTML é montado UMA vez, e a lista de dependências VAZIA é deliberada: recriá-lo a cada
  // mudança recarregaria a página inteira e, com ela, a posição da câmera — o mapa saltaria de
  // volta ao centro a cada arraste do usuário. `centro` e `mostrarUsuario` entram só na semente
  // inicial; depois disso quem os aplica é `window.omniCentralizar`, no efeito abaixo.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const html = useMemo(() => paginaLeaflet(centro, mostrarUsuario), []);

  const enviar = useCallback((script: string) => {
    webview.current?.injectJavaScript(`${script};true;`);
  }, []);

  // Marcadores mudam com frequência (o radar recarrega ao mover); a câmera não é tocada aqui.
  useEffect(() => {
    enviar(`window.omniDefinirMarcadores(${JSON.stringify(marcadores)})`);
  }, [marcadores, enviar]);

  useEffect(() => {
    // `numero()` e não interpolação direta: `centro` pode vir de `TriboResponse`, e o validador de
    // schema do app SÓ AVISA em dev — em produção ele nem roda e devolve o dado como veio. Um valor
    // não numérico chegando aqui seria concatenado dentro de uma chamada de JS na WebView.
    enviar(
      `window.omniCentralizar(${numero(centro.lat)}, ${numero(centro.lon)}, ${mostrarUsuario === true})`,
    );
  }, [centro.lat, centro.lon, mostrarUsuario, enviar]);

  const aoReceberMensagem = useCallback(
    (evento: { nativeEvent: { data: string } }) => {
      let msg: Record<string, unknown>;
      try {
        msg = JSON.parse(evento.nativeEvent.data);
      } catch {
        // Mensagem malformada é descartada em silêncio: a página é NOSSA e não recebe entrada do
        // usuário, então isto só aconteceria com um bug de serialização — e derrubar a tela do
        // mapa por causa dele seria pior que ignorar um evento.
        return;
      }

      if (msg.tipo === 'marcador' && typeof msg.id === 'string') {
        aoTocarMarcador?.(msg.id);
      } else if (msg.tipo === 'regiao' && aoMudarRegiao) {
        aoMudarRegiao({
          lat: Number(msg.lat),
          lon: Number(msg.lon),
          raioM: Math.round(Number(msg.raioM)),
        });
      } else if (msg.tipo === 'toque' && aoTocarMapa) {
        aoTocarMapa(Number(msg.lat), Number(msg.lon));
      }
    },
    [aoTocarMarcador, aoMudarRegiao, aoTocarMapa],
  );

  return (
    <View style={[estilos.container, estilo]} testID={testID}>
      <WebView
        ref={webview}
        // A página é HTML estático nosso, carregado por `source={{ html }}` — origem `about:blank`.
        // `['*']` autorizava qualquer origem a ser carregada nesta WebView, o que só faria diferença
        // no dia em que algo conseguisse iniciar uma navegação aqui dentro. Restringir agora custa
        // nada e fecha a porta antes de alguém precisar dela.
        originWhitelist={['about:*']}
        // Navegação de nível superior é RECUSADA. O Leaflet precisa buscar tiles (que são
        // sub-recursos e não passam por aqui), mas a página nunca deve NAVEGAR para lugar nenhum —
        // um link no popup de um marcador, por exemplo, tiraria o usuário do app sem aviso.
        onShouldStartLoadWithRequest={(requisicao) => requisicao.url.startsWith('about:')}
        // Sem janelas novas: `window.open` a partir da página não abre nada.
        setSupportMultipleWindows={false}
        source={{ html }}
        onMessage={aoReceberMensagem}
        // O mapa é conteúdo visual sem equivalente textual útil para leitor de tela. A lista de
        // missões é a rota acessível para a mesma informação, e existe desde a F10.
        accessibilityLabel="Mapa das missões próximas"
        style={estilos.webview}
        // Sem indicador de scroll nem bounce: dentro da WebView eles competem com o gesto do mapa.
        showsHorizontalScrollIndicator={false}
        showsVerticalScrollIndicator={false}
        bounces={false}
        scrollEnabled={false}
      />
    </View>
  );
}

/**
 * A página. Leaflet vem do unpkg com SRI.
 *
 * A biblioteca poderia ser embutida como string (~150 KB no bundle), mas os TILES já exigem rede —
 * um mapa que funcionasse offline não existiria de qualquer forma. Sem internet, a tela cai para a
 * lista/radar, como o ADR 0012 registra.
 *
 * `integrity` + `crossorigin` não são decoração: sem eles, um CDN comprometido executaria script
 * arbitrário dentro do nosso contexto de WebView.
 */
function paginaLeaflet(centro: { lat: number; lon: number }, mostrarUsuario: boolean): string {
  return `<!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no" />
<link rel="stylesheet"
      href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"
      integrity="sha256-p4NxAoJBhIIN+hmNHrzRCf9tD/miZyoHS5obTRR9BMY="
      crossorigin="" />
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"
        integrity="sha256-20nQCchB9co0qIjJZRGuk2/Z9VM+kNiyxNV1lvTlZBo="
        crossorigin=""></script>
<style>
  html, body, #mapa { height: 100%; margin: 0; padding: 0; background: ${cores.papel}; }
  .omni-pino { border-radius: 50% 50% 50% 0; transform: rotate(-45deg); border: 2px solid ${cores.branco}; }
  .omni-quadrado { border-radius: 3px; border: 2px solid ${cores.branco}; }
  .omni-usuario { border-radius: 50%; border: 3px solid ${cores.branco}; background: ${cores.verdeEscuro}; }
</style>
</head>
<body>
<div id="mapa"></div>
<script>
  var mapa = L.map('mapa', { zoomControl: false, attributionControl: true })
              .setView([${numero(centro.lat)}, ${numero(centro.lon)}], 15);
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19, attribution: '© OpenStreetMap'
  }).addTo(mapa);

  var camadaMarcadores = L.layerGroup().addTo(mapa);
  var marcadorUsuario = null;

  function avisar(payload) {
    window.ReactNativeWebView.postMessage(JSON.stringify(payload));
  }

  function icone(m) {
    var classe = m.forma === 'quadrado' ? 'omni-quadrado' : 'omni-pino';
    return L.divIcon({
      className: '',
      html: '<div class="' + classe + '" style="width:18px;height:18px;background:' + m.cor + '"></div>',
      iconSize: [18, 18],
      iconAnchor: [9, 9]
    });
  }

  window.omniDefinirMarcadores = function (lista) {
    camadaMarcadores.clearLayers();
    lista.forEach(function (m) {
      L.marker([m.lat, m.lon], { icon: icone(m), title: m.rotulo, alt: m.rotulo })
       .on('click', function () { avisar({ tipo: 'marcador', id: m.id }); })
       .addTo(camadaMarcadores);
    });
  };

  window.omniCentralizar = function (lat, lon, comUsuario) {
    mapa.setView([lat, lon], mapa.getZoom());
    if (marcadorUsuario) { mapa.removeLayer(marcadorUsuario); marcadorUsuario = null; }
    if (comUsuario) {
      marcadorUsuario = L.marker([lat, lon], {
        icon: L.divIcon({
          className: '',
          html: '<div class="omni-usuario" style="width:14px;height:14px"></div>',
          iconSize: [14, 14], iconAnchor: [7, 7]
        }),
        // Fora da ordem normal de foco: é indicador de posição, não alvo de interação.
        keyboard: false, interactive: false
      }).addTo(mapa);
    }
  };

  // O debounce fica do lado React Native, onde é testável. Aqui só se relata o que aconteceu:
  // 'moveend' já dispara uma vez por gesto concluído, e não a cada frame do arraste.
  mapa.on('moveend', function () {
    var centro = mapa.getCenter();
    var limites = mapa.getBounds();
    avisar({
      tipo: 'regiao',
      lat: centro.lat,
      lon: centro.lng,
      raioM: centro.distanceTo(L.latLng(limites.getNorth(), centro.lng))
    });
  });

  mapa.on('click', function (e) {
    avisar({ tipo: 'toque', lat: e.latlng.lat, lon: e.latlng.lng });
  });
</script>
</body>
</html>`;
}

const estilos = StyleSheet.create({
  container: { flex: 1, overflow: 'hidden', backgroundColor: cores.papel },
  webview: { flex: 1, backgroundColor: cores.papel },
});
