import type { ExpoConfig } from 'expo/config';

/**
 * Configuração do app.
 *
 * A baseURL da API NUNCA é escrita numa chamada — ela entra aqui, em `extra.apiUrl`, a partir de
 * `EXPO_PUBLIC_API_URL`. Quando a variável não existe, `src/api/baseUrl.ts` deriva o endereço em
 * tempo de execução (ver o comentário lá: o host do Metro é o mesmo host do backend em dev).
 * Este arquivo roda em Node, no start do bundler, e por isso não sabe em qual plataforma o bundle
 * vai executar — a decisão por plataforma tem de ficar no runtime.
 */
const config: ExpoConfig = {
  name: 'Omni-Tribo',
  slug: 'omnitribo',
  version: '1.0.0',
  orientation: 'portrait',
  icon: './assets/images/icon.png',
  // Esquema do deep link. Todo link recebido é entrada NÃO CONFIÁVEL, e a validação está em
  // `src/lib/deepLink.ts` (allowlist de rota + UUID no parâmetro), ligada por
  // `src/features/navegacao/useDeepLink.ts` e coberta por teste.
  //
  // Este comentário já descreveu um arquivo que NÃO EXISTIA — o esquema estava registrado, o Expo
  // Router navegava sozinho para qualquer caminho, e o id descia cru até a URL da API. Se um dia a
  // validação sair, apague esta linha junto: um comentário que promete defesa inexistente é pior
  // que a ausência dela, porque impede que alguém procure.
  scheme: 'omnitribo',
  userInterfaceStyle: 'light',
  android: {
    adaptiveIcon: {
      backgroundColor: '#E1F5EE',
      foregroundImage: './assets/images/android-icon-foreground.png',
      backgroundImage: './assets/images/android-icon-background.png',
      monochromeImage: './assets/images/android-icon-monochrome.png',
    },
    package: 'dev.omnitribo.app',
    predictiveBackGestureEnabled: false,
  },
  ios: {
    bundleIdentifier: 'dev.omnitribo.app',
    supportsTablet: true,
  },
  web: {
    output: 'static',
    favicon: './assets/images/favicon.png',
  },
  plugins: [
    'expo-router',
    'expo-secure-store',
    [
      'expo-splash-screen',
      {
        backgroundColor: '#1D9E75',
        image: './assets/images/splash-icon.png',
        imageWidth: 160,
      },
    ],
    [
      'expo-location',
      {
        // Texto exibido pelo sistema no diálogo de permissão. Agora são DOIS usos: validar o
        // check-in e centrar o mapa. A tela mostra a justificativa ANTES de disparar este diálogo —
        // ver app/(tabs)/mapa.tsx.
        locationWhenInUsePermission:
          'O Omni-Tribo usa sua localização para mostrar missões perto de você e validar o check-in.',
      },
    ],
    // Seletor nativo de data e hora, para a janela da missão. Config plugin exigido a partir da
    // versão 8: sem ele o módulo nativo não é registrado no prebuild.
    '@react-native-community/datetimepicker',
  ],
  experiments: {
    typedRoutes: true,
  },
  extra: {
    apiUrl: process.env.EXPO_PUBLIC_API_URL ?? null,
  },
};

export default config;
