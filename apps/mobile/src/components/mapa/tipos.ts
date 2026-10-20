import type { StyleProp, ViewStyle } from 'react-native';

export interface MarcadorMapa {
  id: string;
  lat: number;
  lon: number;
  /** Cor do pino. Vem de `coresMarcador` — a regra de lint proíbe hex literal fora do tema. */
  cor: string;
  /** `pino` para missão, `quadrado` para ponto de custódia: FORMA além de cor, para daltonismo. */
  forma: 'pino' | 'quadrado';
  /** Glifo da categoria dentro do pino — num mapa não há texto ao lado para desempatar o matiz. */
  glifo?: string;
  rotulo: string;
}

export interface RegiaoMapa {
  lat: number;
  lon: number;
  /** Raio aproximado em metros do centro até a borda norte da viewport. */
  raioM: number;
}

export interface MapaProps {
  centro: { lat: number; lon: number };
  marcadores: MarcadorMapa[];
  /** Mostra o ponto da posição do usuário. Falso quando a permissão foi negada. */
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
 * Coage a número finito antes de interpolar em JavaScript da página do mapa.
 *
 * `centro` vem de resposta de servidor, e o validador de schema do app só AVISA em dev. Sem esta
 * barreira um valor inesperado seria concatenado dentro de `<script>`. Zero como fallback só afeta
 * a câmera: o pior caso é o mapa abrir no Golfo da Guiné, visivelmente errado, em vez de executar
 * algo.
 */
export function numero(valor: unknown): number {
  return typeof valor === 'number' && Number.isFinite(valor) ? valor : 0;
}

/**
 * Ponte página → app, comum aos dois motores e às duas plataformas.
 *
 * No aparelho a página vive numa WebView e fala por `ReactNativeWebView.postMessage`; na web ela
 * vive num `<iframe>` e fala por `parent.postMessage`, embrulhada em `omniMapa` para o app
 * reconhecer o que é dele entre as mensagens da janela.
 */
export const PONTE_JS = `
  function avisar(payload) {
    var texto = JSON.stringify(payload);
    if (window.ReactNativeWebView) {
      window.ReactNativeWebView.postMessage(texto);
    } else if (window.parent && window.parent !== window) {
      window.parent.postMessage({ omniMapa: texto }, '*');
    }
  }
`;

/**
 * CSS do marcador, comum aos dois motores: ALVO de 44 px (WCAG 2.5.5) com o DESENHO de 18 centrado.
 * O que cresce é a área sensível, não a mancha no mapa.
 */
export function cssMarcadores(cores: { branco: string; verdeEscuro: string }): string {
  return `
  .omni-alvo { width: 44px; height: 44px; display: flex; align-items: center; justify-content: center; }
  .omni-pino { width: 18px; height: 18px; border-radius: 50% 50% 50% 0; transform: rotate(-45deg);
               border: 2px solid ${cores.branco}; box-shadow: 0 1px 3px rgba(0,0,0,.35); }
  .omni-quadrado { width: 18px; height: 18px; border-radius: 3px; border: 2px solid ${cores.branco};
                   box-shadow: 0 1px 3px rgba(0,0,0,.35); }
  /* Contra-rotacionado: o pino é girado -45deg e o glifo herdaria o giro. */
  .omni-glifo { display: block; color: ${cores.branco}; font-size: 10px; line-height: 18px;
                text-align: center; transform: rotate(45deg); }
  .omni-quadrado .omni-glifo { transform: none; }
  .omni-usuario { width: 14px; height: 14px; border-radius: 50%; border: 3px solid ${cores.branco};
                  background: ${cores.verdeEscuro}; box-shadow: 0 0 0 6px ${cores.verdeEscuro}2E; }
  `;
}

/** HTML de um marcador, pelo mesmo desenho nos dois motores. */
export const HTML_MARCADOR_JS = `
  function htmlDoMarcador(m) {
    var classe = m.forma === 'quadrado' ? 'omni-quadrado' : 'omni-pino';
    var glifo = m.glifo ? '<span class="omni-glifo">' + m.glifo + '</span>' : '';
    return '<div class="' + classe + '" style="background:' + m.cor + '">' + glifo + '</div>';
  }
`;
