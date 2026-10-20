import { cores } from '@/theme';

import { HTML_MARCADOR_JS, PONTE_JS, cssMarcadores, numero } from './tipos';

/**
 * Origem declarada da página do Google dentro da WebView (`baseUrl`).
 *
 * É o que permite RESTRINGIR a chave por referrer no Google Cloud: sem `baseUrl` a página nasce em
 * `about:blank`, não manda Referer, e a única restrição possível seria nenhuma. A chave do Maps
 * JavaScript é pública por natureza — vai no HTML —, então a restrição de referrer e o teto diário
 * de cota são a proteção dela, não o sigilo.
 */
export const ORIGEM_MAPA_GOOGLE = 'https://mapa.omnitribo.app';

/** Sem `pronto` até aqui, a página desiste e o app cai para o Leaflet. */
const PRAZO_CARGA_MS = 12_000;

/**
 * Página do motor PRINCIPAL: Google Maps JavaScript API (ADR 0039).
 *
 * Por que JavaScript dentro da WebView, e não `react-native-maps`: no Expo Go Android do SDK 57 o
 * mapa nativo do Google sai preto — a chave que o próprio Expo embute no cliente expirou
 * (expo/expo#49323), e "Expo Go usa a chave do Expo, não a sua". A saída nativa exigiria
 * development build e abandonaria o QR do Expo Go, que é o caminho de demonstração inteiro.
 *
 * Marcador é `OverlayView` com HTML próprio, e não `AdvancedMarkerElement`: este exige `mapId`,
 * que desliga o estilo inline do mapa, e o `Marker` clássico está depreciado. O overlay dá o mesmo
 * desenho do Leaflet — alvo de 44 px, forma e glifo — sem depender de nenhum dos dois.
 *
 * Falha de qualquer tipo — chave inválida ou restrita (`gm_authFailure`), script que não carrega,
 * ou prazo estourado — vira a mensagem `falha`, e o app troca de motor.
 */
export function paginaGoogle(
  chave: string,
  centro: { lat: number; lon: number },
  mostrarUsuario: boolean,
): string {
  const lat = numero(centro.lat);
  const lon = numero(centro.lon);
  return `<!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<style>
  html, body, #mapa { height: 100%; margin: 0; padding: 0; background: ${cores.papel}; }
  .omni-alvo { position: absolute; cursor: pointer; }
  ${cssMarcadores(cores)}
</style>
</head>
<body>
<div id="mapa"></div>
<script>
  ${PONTE_JS}
  ${HTML_MARCADOR_JS}
  var mapa = null, Marcador = null, marcadores = [], usuario = null;
  var marcadoresPendentes = null, centroPendente = null, primeiraParada = true;

  window.gm_authFailure = function () { avisar({ tipo: 'falha', motivo: 'autenticacao' }); };
  var prazo = setTimeout(function () {
    if (!mapa) { avisar({ tipo: 'falha', motivo: 'prazo' }); }
  }, ${PRAZO_CARGA_MS});

  function distanciaM(lat1, lon1, lat2, lon2) {
    var r = 6371000, rad = Math.PI / 180;
    var dLat = (lat2 - lat1) * rad, dLon = (lon2 - lon1) * rad;
    var a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * r * Math.asin(Math.sqrt(a));
  }

  function criarClasseMarcador() {
    Marcador = function (posicao, html, rotulo, aoTocar) {
      this.posicao = posicao;
      var div = document.createElement('div');
      div.className = 'omni-alvo';
      div.innerHTML = html;
      if (aoTocar) {
        div.setAttribute('role', 'button');
        div.setAttribute('aria-label', rotulo);
        div.title = rotulo;
        div.addEventListener('click', function (e) { e.stopPropagation(); aoTocar(); });
      } else {
        div.style.pointerEvents = 'none';
        div.setAttribute('aria-hidden', 'true');
      }
      this.div = div;
    };
    Marcador.prototype = new google.maps.OverlayView();
    Marcador.prototype.onAdd = function () { this.getPanes().overlayMouseTarget.appendChild(this.div); };
    Marcador.prototype.draw = function () {
      var p = this.getProjection().fromLatLngToDivPixel(this.posicao);
      if (!p) { return; }
      this.div.style.left = (p.x - 22) + 'px';
      this.div.style.top = (p.y - 22) + 'px';
    };
    Marcador.prototype.onRemove = function () {
      if (this.div.parentNode) { this.div.parentNode.removeChild(this.div); }
    };
  }

  window.omniDefinirMarcadores = function (lista) {
    if (!mapa) { marcadoresPendentes = lista; return; }
    marcadores.forEach(function (m) { m.setMap(null); });
    marcadores = lista.map(function (m) {
      var mk = new Marcador(new google.maps.LatLng(m.lat, m.lon), htmlDoMarcador(m), m.rotulo,
                            function () { avisar({ tipo: 'marcador', id: m.id }); });
      mk.setMap(mapa);
      return mk;
    });
  };

  window.omniCentralizar = function (lat, lon, comUsuario) {
    if (!mapa) { centroPendente = [lat, lon, comUsuario]; return; }
    mapa.setCenter({ lat: lat, lng: lon });
    if (usuario) { usuario.setMap(null); usuario = null; }
    if (comUsuario) {
      usuario = new Marcador(new google.maps.LatLng(lat, lon), '<div class="omni-usuario"></div>', 'Você', null);
      usuario.setMap(mapa);
    }
  };

  window.omniIniciar = function () {
    google.maps.importLibrary('maps').then(function () {
      clearTimeout(prazo);
      criarClasseMarcador();
      mapa = new google.maps.Map(document.getElementById('mapa'), {
        center: { lat: ${lat}, lng: ${lon} },
        zoom: 15,
        disableDefaultUI: true,
        // Botões +/−: a alternativa não-gestual ao pinch (WCAG 2.5.1).
        zoomControl: true,
        // Toque em ponto comercial do Google abriria um balão que concorre com o nosso marcador.
        clickableIcons: false,
        // Um dedo arrasta: dentro da WebView não há rolagem de página a preservar.
        gestureHandling: 'greedy',
        styles: [
          { featureType: 'poi.business', stylers: [{ visibility: 'off' }] },
          { featureType: 'transit', elementType: 'labels.icon', stylers: [{ visibility: 'off' }] }
        ]
      });

      // 'idle' dispara uma vez por gesto concluído, como o 'moveend' do Leaflet. A primeira parada é
      // a própria carga — relatá-la faria a tela refazer a busca que acabou de fazer.
      mapa.addListener('idle', function () {
        if (primeiraParada) { primeiraParada = false; return; }
        var c = mapa.getCenter(), limites = mapa.getBounds();
        if (!limites) { return; }
        avisar({ tipo: 'regiao', lat: c.lat(), lon: c.lng(),
                 raioM: distanciaM(c.lat(), c.lng(), limites.getNorthEast().lat(), c.lng()) });
      });
      mapa.addListener('click', function (e) {
        avisar({ tipo: 'toque', lat: e.latLng.lat(), lon: e.latLng.lng() });
      });

      if (centroPendente) {
        window.omniCentralizar(centroPendente[0], centroPendente[1], centroPendente[2]);
        centroPendente = null;
      } else if (${mostrarUsuario === true}) {
        window.omniCentralizar(${lat}, ${lon}, true);
      }
      if (marcadoresPendentes) {
        window.omniDefinirMarcadores(marcadoresPendentes);
        marcadoresPendentes = null;
      }
      avisar({ tipo: 'pronto' });
    }, function () { avisar({ tipo: 'falha', motivo: 'biblioteca' }); });
  };
</script>
<script async
        src="https://maps.googleapis.com/maps/api/js?key=${encodeURIComponent(chave)}&callback=omniIniciar&loading=async&v=weekly&language=pt-BR&region=BR"
        onerror="avisar({ tipo: 'falha', motivo: 'rede' })"></script>
</body>
</html>`;
}
