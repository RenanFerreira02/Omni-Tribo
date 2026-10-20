import { forwardRef, useImperativeHandle, useRef } from 'react';
import { StyleSheet } from 'react-native';
import { WebView } from 'react-native-webview';

import { cores } from '@/theme';

export interface PaginaMapaHandle {
  /** Executa JavaScript dentro da página — é como o app manda marcadores e câmera. */
  executar: (script: string) => void;
}

export interface PaginaMapaProps {
  html: string;
  /** Origem declarada da página. Ausente: `about:blank`. Ver `ORIGEM_MAPA_GOOGLE`. */
  origem?: string;
  aoMensagem: (dados: string) => void;
}

/**
 * Transporte da página do mapa no APARELHO: uma WebView. A versão web é `PaginaMapa.web.tsx`.
 *
 * A página nunca NAVEGA: o mapa busca tiles e scripts como sub-recursos, que não passam por
 * `onShouldStartLoadWithRequest`, mas um link no logo do Google ou num balão tiraria o usuário do app
 * sem aviso. Só a própria origem da página é aceita como navegação.
 */
export const PaginaMapa = forwardRef<PaginaMapaHandle, PaginaMapaProps>(function PaginaMapa(
  { html, origem, aoMensagem },
  ref,
) {
  const webview = useRef<WebView>(null);

  useImperativeHandle(ref, () => ({
    executar: (script: string) => webview.current?.injectJavaScript(`${script};true;`),
  }));

  const permitidas = origem ? ['about:', origem] : ['about:'];

  return (
    <WebView
      ref={webview}
      originWhitelist={origem ? ['about:*', `${origem}*`] : ['about:*']}
      onShouldStartLoadWithRequest={(req) => permitidas.some((p) => req.url.startsWith(p))}
      setSupportMultipleWindows={false}
      source={origem ? { html, baseUrl: `${origem}/` } : { html }}
      onMessage={(evento) => aoMensagem(evento.nativeEvent.data)}
      // O mapa é conteúdo visual: a rota acessível é a apresentação em lista do radar (ADR 0030).
      accessibilityLabel="Mapa das missões próximas"
      style={estilos.webview}
      showsHorizontalScrollIndicator={false}
      showsVerticalScrollIndicator={false}
      bounces={false}
      scrollEnabled={false}
    />
  );
});

const estilos = StyleSheet.create({
  webview: { flex: 1, backgroundColor: cores.papel },
});
