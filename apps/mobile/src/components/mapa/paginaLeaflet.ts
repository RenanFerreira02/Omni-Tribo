import { cores } from '@/theme';

import { HTML_MARCADOR_JS, PONTE_JS, cssMarcadores, numero } from './tipos';

/**
 * Página do motor de RESERVA: Leaflet com tiles Carto Voyager (base OpenStreetMap). Ver ADR 0012.
 *
 * É o mapa que aparece quando não há chave do Google, quando a chave falha na autenticação ou
 * quando a API do Google não carrega a tempo — o app nunca fica com um retângulo cinza. Não exige
 * chave nenhuma. Voyager em vez do tile cru do OSM: mesma base de dados, cartografia mais limpa.
 *
 * Leaflet vem do unpkg com SRI: sem `integrity`, um CDN comprometido executaria script arbitrário
 * dentro do contexto do mapa.
 */
export function paginaLeaflet(
  centro: { lat: number; lon: number },
  mostrarUsuario: boolean,
): string {
  return `<!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="utf-8" />
<!-- SEM user-scalable=no: ampliar é como se lê o nome da rua (WCAG 2.5.1). -->
<meta name="viewport" content="width=device-width, initial-scale=1" />
<link rel="stylesheet"
      href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"
      integrity="sha256-p4NxAoJBhIIN+hmNHrzRCf9tD/miZyoHS5obTRR9BMY="
      crossorigin="" />
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"
        integrity="sha256-20nQCchB9co0qIjJZRGuk2/Z9VM+kNiyxNV1lvTlZBo="
        crossorigin=""></script>
<style>
  html, body, #mapa { height: 100%; margin: 0; padding: 0; background: ${cores.papel}; }
  ${cssMarcadores(cores)}
</style>
</head>
<body>
<div id="mapa"></div>
<script>
  ${PONTE_JS}
  ${HTML_MARCADOR_JS}
  // Botões +/− ligados: a alternativa não-gestual ao pinch.
  var mapa = L.map('mapa', { zoomControl: true, attributionControl: true })
              .setView([${numero(centro.lat)}, ${numero(centro.lon)}], 15);
  L.tileLayer('https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png', {
    maxZoom: 19, subdomains: 'abcd',
    attribution: '© OpenStreetMap · © CARTO'
  }).addTo(mapa);

  var camadaMarcadores = L.layerGroup().addTo(mapa);
  var marcadorUsuario = null;

  function icone(m) {
    return L.divIcon({
      className: '',
      html: '<div class="omni-alvo">' + htmlDoMarcador(m) + '</div>',
      iconSize: [44, 44],
      iconAnchor: [22, 22]
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
        icon: L.divIcon({ className: '', html: '<div class="omni-usuario"></div>',
                          iconSize: [14, 14], iconAnchor: [7, 7] }),
        // Indicador de posição, não alvo de interação: fora da ordem de foco.
        keyboard: false, interactive: false
      }).addTo(mapa);
    }
  };

  // O debounce fica do lado React Native, onde é testável. 'moveend' já é um por gesto concluído.
  mapa.on('moveend', function () {
    var c = mapa.getCenter();
    var limites = mapa.getBounds();
    avisar({ tipo: 'regiao', lat: c.lat, lon: c.lng,
             raioM: c.distanceTo(L.latLng(limites.getNorth(), c.lng)) });
  });

  mapa.on('click', function (e) {
    avisar({ tipo: 'toque', lat: e.latlng.lat, lon: e.latlng.lng });
  });

  if (${mostrarUsuario === true}) {
    window.omniCentralizar(${numero(centro.lat)}, ${numero(centro.lon)}, true);
  }
  avisar({ tipo: 'pronto' });
</script>
</body>
</html>`;
}
