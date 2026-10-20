import Constants from 'expo-constants';

/**
 * Chave da Google Maps JavaScript API, ou `null` quando não há.
 *
 * Entra por `EXPO_PUBLIC_GOOGLE_MAPS_KEY` (arquivo `apps/mobile/.env`, fora do git) via
 * `extra.googleMapsKey` do `app.config.ts`. Sem ela o mapa usa o Leaflet — o app funciona igual,
 * só com outro desenho. Ver `src/components/mapa/paginaGoogle.ts` sobre como restringir a chave.
 */
export function chaveGoogleMaps(): string | null {
  const chave = Constants.expoConfig?.extra?.googleMapsKey;
  return typeof chave === 'string' && chave.trim().length > 0 ? chave.trim() : null;
}
