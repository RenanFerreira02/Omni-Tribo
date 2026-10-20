import { useEffect, useMemo, useRef, useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { chaveGoogleMaps } from '@/lib/chaveMapa';
import { cores } from '@/theme';

import { ORIGEM_MAPA_GOOGLE, paginaGoogle } from './mapa/paginaGoogle';
import { paginaLeaflet } from './mapa/paginaLeaflet';
import { PaginaMapa, type PaginaMapaHandle } from './mapa/PaginaMapa';
import { numero, type MapaProps } from './mapa/tipos';

export type { MapaProps, MarcadorMapa, RegiaoMapa } from './mapa/tipos';

export type MotorMapa = 'google' | 'leaflet';

/**
 * O mapa do app. **É o único ponto que as telas conhecem** — nenhuma sabe qual motor desenha.
 *
 * Dois motores com o MESMO protocolo de mensagens: Google Maps JavaScript (principal, quando há
 * chave) e Leaflet (reserva, sem chave). A troca é AUTOMÁTICA e só num sentido: se a página do
 * Google avisar `falha` — chave recusada, script que não carrega, prazo estourado —, o componente
 * remonta com o Leaflet e não tenta de novo. Na feira o mapa nunca fica cinza (ADR 0039).
 *
 * App → página: `omniDefinirMarcadores` e `omniCentralizar`, reenviados quando a página avisa
 * `pronto` — um script injetado antes de a página carregar se perderia.
 * Página → app: `marcador`, `regiao`, `toque`, `pronto` e `falha`.
 */
export function Mapa({
  centro,
  marcadores,
  mostrarUsuario = false,
  aoTocarMarcador,
  aoMudarRegiao,
  aoTocarMapa,
  estilo,
  testID,
}: MapaProps) {
  const chave = chaveGoogleMaps();
  const [motor, setMotor] = useState<MotorMapa>(chave ? 'google' : 'leaflet');
  const pagina = useRef<PaginaMapaHandle>(null);
  const [pronta, setPronta] = useState(false);

  // O HTML nasce UMA vez por motor: recriá-lo a cada mudança recarregaria a página e jogaria a câmera
  // de volta ao centro a cada arraste. `centro` e `mostrarUsuario` só semeiam; depois quem os aplica
  // é `omniCentralizar`, no efeito abaixo.
  const html = useMemo(
    () =>
      motor === 'google' && chave
        ? paginaGoogle(chave, centro, mostrarUsuario)
        : paginaLeaflet(centro, mostrarUsuario),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [motor],
  );

  useEffect(() => {
    if (!pronta) return;
    pagina.current?.executar(`window.omniDefinirMarcadores(${JSON.stringify(marcadores)})`);
  }, [marcadores, pronta]);

  useEffect(() => {
    if (!pronta) return;
    pagina.current?.executar(
      `window.omniCentralizar(${numero(centro.lat)}, ${numero(centro.lon)}, ${mostrarUsuario === true})`,
    );
  }, [centro.lat, centro.lon, mostrarUsuario, pronta]);

  // Sem useCallback: o React Compiler memoiza este handler sozinho, e a dependência manual divergia
  // da que ele infere.
  const aoMensagem = (dados: string) => {
    let msg: Record<string, unknown>;
    try {
      msg = JSON.parse(dados);
    } catch {
      // A página é nossa e não recebe entrada do usuário: mensagem malformada só nasceria de um
      // bug de serialização, e derrubar a tela por isso seria pior que ignorar um evento.
      return;
    }
    if (msg.tipo === 'pronto') {
      setPronta(true);
    } else if (msg.tipo === 'falha') {
      if (motor === 'google') {
        setPronta(false);
        setMotor('leaflet');
      }
    } else if (msg.tipo === 'marcador' && typeof msg.id === 'string') {
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
  };

  return (
    <View style={[estilos.container, estilo]} testID={testID}>
      <PaginaMapa
        key={motor}
        ref={pagina}
        html={html}
        origem={motor === 'google' ? ORIGEM_MAPA_GOOGLE : undefined}
        aoMensagem={aoMensagem}
      />
    </View>
  );
}

const estilos = StyleSheet.create({
  container: { flex: 1, overflow: 'hidden', backgroundColor: cores.papel },
});
