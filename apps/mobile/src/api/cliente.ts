import axios, { type AxiosError, type AxiosInstance, type InternalAxiosRequestConfig } from 'axios';

import { PREFIXO_API, resolverBaseUrl } from './baseUrl';
import { paraErroApi } from './erros';
import { novoCorrelationId } from '@/lib/ids';
import { lerRefreshPersistido, sessaoAtual } from '@/stores/sessao';
import type { LoginResponse } from './tipos';

/** Rotas públicas de auth: um 401 vindo delas é resposta legítima, não sessão expirada. */
const ROTAS_SEM_REFRESH = ['/auth/login', '/auth/registrar', '/auth/refresh'];

interface ConfigComRetentativa extends InternalAxiosRequestConfig {
  /** Marca que esta requisição JÁ passou por uma rotação. Impede o loop 401 → refresh → 401 → … */
  _jaTentouRefresh?: boolean;
}

export const cliente: AxiosInstance = axios.create({
  baseURL: `${resolverBaseUrl()}${PREFIXO_API}`,
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' },
});

cliente.interceptors.request.use((config) => {
  const { accessToken } = sessaoAtual();
  if (accessToken) {
    config.headers.set('Authorization', `Bearer ${accessToken}`);
  }
  // Mesmo nome e capitalização do `CorrelationIdFilter` do backend, que o ecoa na resposta e o usa
  // como `traceId` no corpo dos erros. É o que liga um erro visto no app à linha de log do servidor.
  config.headers.set('X-Correlation-Id', novoCorrelationId());
  return config;
});

/**
 * Rotação de refresh em voo, compartilhada.
 *
 * **Por que uma só, e por que uma fila.** Quando o access token expira, tudo o que estava na tela
 * toma 401 quase junto — lista, saldo, perfil. Se cada uma disparasse o próprio `POST /auth/refresh`,
 * seriam N rotações concorrentes do MESMO refresh token; o backend rotaciona a cada chamada e trata
 * reapresentação de token já rotacionado como indício de roubo, **revogando a família inteira**. O
 * app se deslogaria sozinho justamente por ter tentado se manter logado. Então: a primeira
 * requisição a tomar 401 dispara a rotação, as demais assinam a MESMA promessa e são reenviadas
 * quando ela resolve.
 */
let rotacaoEmVoo: Promise<string> | null = null;

async function rotacionar(): Promise<string> {
  const refresh = sessaoAtual().refreshToken ?? (await lerRefreshPersistido());
  if (!refresh) throw new Error('sem refresh token');

  // Instância limpa de propósito: usar `cliente` faria a resposta desta chamada passar pelo próprio
  // interceptor de 401 — recursão no exato caminho que existe para evitá-la.
  const resposta = await axios.post<LoginResponse>(
    `${resolverBaseUrl()}${PREFIXO_API}/auth/refresh`,
    { refreshToken: refresh },
    { headers: { 'Content-Type': 'application/json' }, timeout: 15000 },
  );

  await sessaoAtual().definirSessao(resposta.data);
  return resposta.data.accessToken;
}

function rotacaoCompartilhada(): Promise<string> {
  if (!rotacaoEmVoo) {
    rotacaoEmVoo = rotacionar().finally(() => {
      rotacaoEmVoo = null;
    });
  }
  return rotacaoEmVoo;
}

cliente.interceptors.response.use(
  (resposta) => resposta,
  async (erro: AxiosError) => {
    const config = erro.config as ConfigComRetentativa | undefined;
    const status = erro.response?.status;

    const elegivel =
      status === 401 &&
      config !== undefined &&
      config._jaTentouRefresh !== true &&
      !ROTAS_SEM_REFRESH.some((rota) => (config.url ?? '').includes(rota)) &&
      // Discrimina pelo `type`, não pelo status: o backend responde 401 com
      // `nao-autenticado` para credencial ausente/expirada, e essa é a única causa que uma
      // rotação resolve.
      paraErroApi(erro).tipo === 'naoAutenticado';

    if (!elegivel) {
      return Promise.reject(erro);
    }

    config._jaTentouRefresh = true;

    try {
      const novoToken = await rotacaoCompartilhada();
      config.headers.set('Authorization', `Bearer ${novoToken}`);
      return cliente.request(config);
    } catch {
      // Refresh recusado: a sessão acabou de verdade. Limpar aqui (e não na tela) garante que
      // qualquer requisição em qualquer aba chegue ao mesmo desfecho — o layout raiz observa o
      // store e redireciona para (auth)/login.
      await sessaoAtual().encerrar();
      return Promise.reject(erro);
    }
  },
);

/**
 * Última etapa da cadeia: tudo o que sai daqui rejeitado já é um `ErroApi`.
 *
 * Registrado DEPOIS do interceptor de 401 de propósito — interceptors de resposta rodam na ordem de
 * registro, então este recebe o que aquele decidiu não tratar. O ganho é que nenhuma tela, hook ou
 * teste precisa saber que existe axios embaixo: a fronteira do HTTP acaba neste arquivo, como manda
 * `apps/mobile/CLAUDE.md` ("src/api/ é o único lugar que fala HTTP").
 */
cliente.interceptors.response.use(
  (resposta) => resposta,
  (erro: unknown) => Promise.reject(paraErroApi(erro)),
);

/** Só para teste: zera a rotação em voo entre casos. */
export function _limparRotacaoEmVoo(): void {
  rotacaoEmVoo = null;
}
