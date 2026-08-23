import { gravarSeguro, lerSeguro } from '@/lib/armazenamentoSeguro';

/** As duas formas de ver o mesmo radar. Não são telas: são apresentações do mesmo destino. */
export type ApresentacaoRadar = 'mapa' | 'lista';

const CHAVE = 'omnitribo.radar.apresentacao';

/**
 * Mapa ou lista, lembrado entre sessões.
 *
 * **Persistir importa mais aqui do que numa preferência comum.** Quem depende da lista depende dela
 * SEMPRE — e teria de reencontrar e reacionar o alternador a cada abertura do app, com leitor de
 * tela, antes de conseguir usar a tela. Uma preferência de acessibilidade que não é lembrada é uma
 * barreira cobrada por sessão.
 *
 * **Guardado em `expo-secure-store` e isto não é segredo**, pela mesma razão de
 * `features/onboarding/visto.ts`: é para não trazer o `@react-native-async-storage/async-storage`
 * ao projeto por causa de uma string de cinco letras. O acesso passa por `@/lib/armazenamentoSeguro`
 * porque na web o secure-store não existe.
 *
 * Consequência conhecida, e é a mesma do onboarding: **na web nada persiste** (ADR 0013), então a
 * preferência volta ao default a cada reload. Irritante e inofensivo — bem diferente do que a mesma
 * limitação significa para o refresh token.
 */
export async function lerApresentacaoRadar(): Promise<ApresentacaoRadar> {
  try {
    return (await lerSeguro(CHAVE)) === 'lista' ? 'lista' : 'mapa';
  } catch {
    // Falha de leitura cai no default. O pior caso é a pessoa tocar no alternador uma vez.
    return 'mapa';
  }
}

export async function gravarApresentacaoRadar(valor: ApresentacaoRadar): Promise<void> {
  try {
    await gravarSeguro(CHAVE, valor);
  } catch {
    // Não conseguir gravar não pode impedir a troca de apresentação: ela já aconteceu em memória.
    // A pessoa reencontra o default na próxima abertura, o que é pior que lembrar e melhor que
    // travar a tela.
  }
}
