import { createElement, forwardRef, useEffect, useImperativeHandle, useRef } from 'react';

import type { PaginaMapaHandle, PaginaMapaProps } from './PaginaMapa';

export type { PaginaMapaHandle, PaginaMapaProps } from './PaginaMapa';

/**
 * Transporte da página do mapa na WEB: um `<iframe srcdoc>`.
 *
 * Existe porque o `react-native-webview` não tem implementação web — ele desenha "React Native
 * WebView does not support this platform" no lugar do mapa, e é na web que roda o telão da feira.
 *
 * `allow-same-origin` junto de `allow-scripts` é o que deixa o app chamar as funções da página
 * (`omniDefinirMarcadores`) direto; o conteúdo é HTML gerado por nós, sem entrada de usuário. A
 * página não pode abrir janela nem navegar o topo: `allow-popups` e `allow-top-navigation` ficam de
 * fora do sandbox. Scripts mandados antes de a página carregar entram numa fila.
 */
export const PaginaMapa = forwardRef<PaginaMapaHandle, PaginaMapaProps>(function PaginaMapa(
  { html, aoMensagem },
  ref,
) {
  const iframe = useRef<HTMLIFrameElement | null>(null);
  const carregada = useRef(false);
  const fila = useRef<string[]>([]);
  const aoMensagemAtual = useRef(aoMensagem);
  aoMensagemAtual.current = aoMensagem;

  const rodar = (script: string) => {
    const janela = iframe.current?.contentWindow as (Window & { eval: (s: string) => void }) | null;
    janela?.eval(script);
  };

  useImperativeHandle(ref, () => ({
    executar: (script: string) => {
      if (carregada.current) rodar(script);
      else fila.current.push(script);
    },
  }));

  useEffect(() => {
    const ouvir = (evento: MessageEvent) => {
      if (evento.source !== iframe.current?.contentWindow) return;
      const dados = (evento.data as { omniMapa?: unknown } | null)?.omniMapa;
      if (typeof dados === 'string') aoMensagemAtual.current(dados);
    };
    window.addEventListener('message', ouvir);
    return () => window.removeEventListener('message', ouvir);
  }, []);

  return createElement('iframe', {
    ref: iframe,
    srcDoc: html,
    title: 'Mapa das missões próximas',
    sandbox: 'allow-scripts allow-same-origin',
    onLoad: () => {
      carregada.current = true;
      fila.current.splice(0).forEach(rodar);
    },
    style: { border: 0, width: '100%', height: '100%', flex: 1 },
  });
});
