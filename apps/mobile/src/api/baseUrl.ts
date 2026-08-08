import Constants from 'expo-constants';
import { Platform } from 'react-native';

/** Porta do Spring Boot em dev. O actuator vive na 8081 e não interessa ao app. */
const PORTA_API = 8080;

/**
 * Resolve a baseURL da API.
 *
 * Ordem: `EXPO_PUBLIC_API_URL` (via `extra.apiUrl` do app.config.ts) → host do Metro → default de
 * plataforma.
 *
 * **Por que o host do Metro é um bom default.** Em dev o bundler já está servindo o app a partir do
 * mesmo computador que roda o backend, e o `hostUri` traz o endereço pelo qual o dispositivo
 * conseguiu alcançá-lo — `192.168.15.6:8081` no celular físico, `localhost:8081` no emulador com
 * adb reverse. Trocar a porta por 8080 acerta os dois casos sem ninguém editar arquivo. É
 * exatamente o passo que mais trava iniciante: `localhost` dentro do celular é o PRÓPRIO celular,
 * não a máquina de desenvolvimento.
 *
 * O default de plataforma só entra quando não há bundler (build standalone), e aí `10.0.2.2` é o
 * alias do host visto de dentro do emulador Android.
 */
export function resolverBaseUrl(): string {
  const configurada = Constants.expoConfig?.extra?.apiUrl;
  if (typeof configurada === 'string' && configurada.length > 0) {
    return semBarraFinal(configurada);
  }

  const hostUri = Constants.expoConfig?.hostUri;
  if (typeof hostUri === 'string' && hostUri.length > 0) {
    const host = hostUri.split(':')[0];
    if (host) return `http://${host}:${PORTA_API}`;
  }

  return Platform.OS === 'android'
    ? `http://10.0.2.2:${PORTA_API}`
    : `http://localhost:${PORTA_API}`;
}

function semBarraFinal(url: string): string {
  return url.endsWith('/') ? url.slice(0, -1) : url;
}

export const PREFIXO_API = '/api/v1';
