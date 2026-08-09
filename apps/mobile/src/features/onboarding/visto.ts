import * as SecureStore from 'expo-secure-store';

const CHAVE = 'omnitribo.onboarding.visto';

/**
 * "Já viu o onboarding?", persistido entre sessões.
 *
 * **Guardado em `expo-secure-store`, e isso merece explicação.** A regra do projeto é "credencial em
 * expo-secure-store, NUNCA AsyncStorage" — e isto não é credencial. A escolha do secure-store aqui
 * não é por segurança: é para não instalar o `@react-native-async-storage/async-storage` só por uma
 * flag booleana, num projeto que deliberadamente o mantém fora das dependências. O custo é uma
 * escrita no keystore que não precisaria ser cifrada; o benefício é uma dependência a menos.
 *
 * Falha de leitura vira `false` — "não viu". O pior caso é alguém rever três slides; o inverso
 * seria um usuário novo cair direto no login sem nunca saber o que o app faz.
 */
export async function jaViuOnboarding(): Promise<boolean> {
  try {
    return (await SecureStore.getItemAsync(CHAVE)) === '1';
  } catch {
    return false;
  }
}

export async function marcarOnboardingVisto(): Promise<void> {
  try {
    await SecureStore.setItemAsync(CHAVE, '1');
  } catch {
    // Não conseguir gravar não pode impedir a pessoa de entrar no app. Ela verá o onboarding de
    // novo na próxima abertura, o que é irritante — e ainda assim melhor que travar a entrada.
  }
}
