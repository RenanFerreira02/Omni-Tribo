import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';

/**
 * Ponto ÚNICO de persistência de segredo do app.
 *
 * **Existe porque `expo-secure-store` não tem implementação web.** O módulo nativo resolvido no
 * bundle web é literalmente `export default {}`, e a biblioteca chama os métodos sem nenhuma guarda
 * — as três funções estouram com `... is not a function`. Não é um caso de borda: acontece no boot,
 * em `restaurarSessao`, antes de a primeira tela pintar.
 *
 * ## O que a web faz no lugar: nada é persistido
 *
 * O browser não tem keystore. As alternativas disponíveis — `localStorage` e `sessionStorage` —
 * gravam **em claro**, e o que guardamos aqui é um refresh token que vale 30 dias de sessão: uma
 * única falha de XSS entregaria a conta inteira, não um inconveniente. A regra do projeto
 * ("credencial em expo-secure-store, NUNCA AsyncStorage") vale pelo mesmo motivo, e não abre
 * exceção só porque a plataforma é outra.
 *
 * Então na web o cofre é um `Map` no escopo do módulo, que morre com a aba. O preço é explícito e
 * aceito: **recarregar a página desloga**. A web é alvo de demonstração; o alvo real é o nativo,
 * onde o Android Keystore e o iOS Keychain fazem o trabalho de verdade. Ver ADR 0013.
 *
 * ## Por que `Platform.OS` e não um arquivo `.web.ts`
 *
 * O Metro resolveria `armazenamentoSeguro.web.ts` sozinho, o que seria mais idiomático — e
 * intestável: o preset do jest-expo declara `platforms: [android, ios, native]`, então nenhum teste
 * jamais carregaria o arquivo web. Decidir em runtime deixa os dois caminhos sob assertion.
 */

/** Cofre da web. Vive no escopo do módulo, ou seja, morre no reload — que é o ponto. */
const cofreEfemero = new Map<string, string>();

const naWeb = () => Platform.OS === 'web';

export async function lerSeguro(chave: string): Promise<string | null> {
  if (naWeb()) return cofreEfemero.get(chave) ?? null;
  return SecureStore.getItemAsync(chave);
}

export async function gravarSeguro(chave: string, valor: string): Promise<void> {
  if (naWeb()) {
    cofreEfemero.set(chave, valor);
    return;
  }
  await SecureStore.setItemAsync(chave, valor);
}

/**
 * Apaga um segredo. **Nunca lança** — e isso é a correção de um bug real, não conveniência.
 *
 * Apagar credencial é sempre operação de limpeza, e todo chamador já está num caminho de falha:
 * refresh expirado, rotação recusada, logout. Deixar a limpeza explodir transforma um logout normal
 * numa exceção que sobe de dentro de um `catch` e **mascara o erro original** — foi exatamente essa
 * cadeia que escondeu, atrás de um `deleteItemAsync is not a function`, o fato de que quem tinha
 * falhado primeiro era o `getItemAsync`.
 *
 * O estado em memória é zerado pelo chamador de qualquer forma, então a sessão termina mesmo que o
 * keystore recuse a escrita. O pior caso é um refresh órfão que o servidor já não aceita.
 */
export async function apagarSeguro(chave: string): Promise<void> {
  if (naWeb()) {
    cofreEfemero.delete(chave);
    return;
  }
  try {
    await SecureStore.deleteItemAsync(chave);
  } catch {
    // Silêncio deliberado — ver o javadoc acima. Logar aqui também não ajudaria: o único dado que
    // daria contexto é a chave, e chave de credencial não vai para log.
  }
}
