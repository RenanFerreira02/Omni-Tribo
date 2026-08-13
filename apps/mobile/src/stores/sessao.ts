import { create } from 'zustand';

import type { LoginResponse, MeResponse } from '@/api/tipos';
import { apagarSeguro, gravarSeguro, lerSeguro } from '@/lib/armazenamentoSeguro';

/**
 * Chave do refresh token no keystore do dispositivo.
 *
 * **expo-secure-store, NUNCA AsyncStorage.** AsyncStorage grava em claro — SQLite no Android,
 * arquivo no iOS — e qualquer processo com acesso ao sandbox (device rooteado, backup do
 * dispositivo, malware com as permissões certas) lê o conteúdo. SecureStore usa o Android Keystore
 * e o iOS Keychain, onde o material fica protegido por hardware quando disponível. Um refresh token
 * do Omni-Tribo vale 30 dias de sessão: vazá-lo é entregar a conta, não um inconveniente.
 *
 * O acesso passa por `@/lib/armazenamentoSeguro`, e não pelo `expo-secure-store` direto, porque a
 * web não tem keystore nenhum. Lá o token fica só em memória e o reload desloga — pelo mesmo
 * raciocínio acima, gravá-lo em claro no browser seria pior que perder a sessão. Ver ADR 0013.
 */
const CHAVE_REFRESH = 'omnitribo.refreshToken';

interface EstadoSessao {
  /**
   * Access token. **Só em memória, nunca persistido.** Vive 15 minutos e é reconstruível a partir
   * do refresh; gravá-lo em disco só ampliaria a superfície sem economizar nada.
   */
  accessToken: string | null;
  refreshToken: string | null;
  usuario: MeResponse | null;
  /** true enquanto a restauração de boot não terminou — segura o redirecionamento das rotas. */
  restaurando: boolean;

  definirSessao: (tokens: LoginResponse) => Promise<void>;
  definirUsuario: (usuario: MeResponse | null) => void;

  /**
   * Esquece a sessão em MEMÓRIA e preserva o refresh no cofre.
   *
   * <p>Para quando a rotação falhou por obstáculo TEMPORÁRIO — 429 do rate limit, rede caindo no
   * boot. O access token já não sobrevive ao processo, então zerar a memória não perde nada; o que
   * não pode acontecer é apagar o cofre, porque aí uma sessão de 30 dias perfeitamente válida morre
   * por causa de um limite de um minuto. Use `encerrar` quando a sessão de fato acabou.
   */
  limparMemoria: () => void;

  encerrar: () => Promise<void>;
  concluirRestauracao: () => void;
}

export const useSessao = create<EstadoSessao>((set) => ({
  accessToken: null,
  refreshToken: null,
  usuario: null,
  restaurando: true,

  definirSessao: async (tokens) => {
    await gravarSeguro(CHAVE_REFRESH, tokens.refreshToken);
    set({ accessToken: tokens.accessToken, refreshToken: tokens.refreshToken });
  },

  definirUsuario: (usuario) => set({ usuario }),

  limparMemoria: () => set({ accessToken: null, refreshToken: null, usuario: null }),

  encerrar: async () => {
    await apagarSeguro(CHAVE_REFRESH);
    set({ accessToken: null, refreshToken: null, usuario: null });
  },

  concluirRestauracao: () => set({ restaurando: false }),
}));

export async function lerRefreshPersistido(): Promise<string | null> {
  return lerSeguro(CHAVE_REFRESH);
}

/**
 * Acesso ao token fora de componente React.
 *
 * O interceptor do axios roda fora da árvore, então não pode usar hook. `getState()` é a porta
 * oficial do Zustand para isso e lê sempre o valor corrente — não uma cópia congelada no closure,
 * que é o bug clássico deste ponto: depois de um refresh, o interceptor continuaria mandando o
 * token velho.
 */
export const sessaoAtual = () => useSessao.getState();
