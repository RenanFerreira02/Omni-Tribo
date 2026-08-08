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
  // Esquema do deep link. Todo link recebido é entrada NÃO CONFIÁVEL: ver src/lib/deepLink.ts,
  // que valida esquema, host e formato antes de qualquer navegação.
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
        // Texto exibido pelo sistema no diálogo de permissão. O check-in é o único uso.
        locationWhenInUsePermission:
          'O Omni-Tribo usa sua localização para mostrar missões perto de você e validar o check-in.',
      },
    ],
  ],
  experiments: {
    typedRoutes: true,
  },
  extra: {
    apiUrl: process.env.EXPO_PUBLIC_API_URL ?? null,
  },
};

export default config;
