import axios from 'axios';

/**
 * Tradução de RFC 9457 (`ProblemDetail`) para um tipo que a UI pode ramificar.
 *
 * REGRA DURA: a discriminação é pelo campo `type`, NUNCA pelo `detail`.
 * `detail` é texto em português voltado a humano e muda a cada revisão de copy — um `if` sobre ele
 * quebra sem avisar e sem quebrar teste nenhum. `status` sozinho é ambíguo: dois 409 daqui pedem
 * reações opostas (transição inválida → recarregue a tela; colisão de versão → tente de novo).
 *
 * O catálogo canônico é `compartilhado/api/TipoProblema` no backend. Aqui a chave é o ÚLTIMO
 * SEGMENTO da URI, não a URI inteira, para que trocar o domínio do catálogo não obrigue a mexer no
 * app; e a lista é fechada, para que uma URI nova apareça como `desconhecido` em vez de bater num
 * `default` que finge ter entendido.
 */

/** Campo recusado pela Bean Validation do backend, pronto para marcar o input do formulário. */
export interface ErroCampo {
  campo: string;
  mensagem: string;
}

interface Base {
  /** Texto do backend. Só para EXIBIR — nunca para decidir. */
  detail: string;
  status: number;
  /** Correlaciona a resposta com o log do servidor. Ausente nos erros nascidos nos filtros. */
  traceId?: string;
}

export type ErroApi =
  | (Base & { tipo: 'requisicaoInvalida'; erros: ErroCampo[] })
  | (Base & { tipo: 'naoAutenticado' })
  | (Base & { tipo: 'acessoNegado' })
  | (Base & { tipo: 'naoEncontrado' })
  /** 409: não cabe NESTE estado. Recarregue a tela — repetir não adianta. */
  | (Base & { tipo: 'transicaoInvalida' })
  /** 409: alguém alterou o recurso no meio do caminho. Repetir TENDE a funcionar. */
  | (Base & { tipo: 'conflitoConcorrencia' })
  /** 422 genérico: saldo, pote, janela. Sem causa distinguível — exiba `detail`. */
  | (Base & { tipo: 'regraNegocioViolada' })
  | (Base & { tipo: 'saqueDesabilitado' })
  | (Base & { tipo: 'checkinForaDoRaio' })
  | (Base & { tipo: 'checkinAcuraciaInsuficiente' })
  | (Base & { tipo: 'checkinLocalizacaoSimulada' })
  | (Base & { tipo: 'limiteRequisicoes'; retryAfter: number | null })
  | (Base & { tipo: 'naoImplementado' })
  | (Base & { tipo: 'erroInterno' })
  /** Requisição que não chegou a ter resposta: avião, DNS, backend desligado, IP errado. */
  | (Base & { tipo: 'semRede' })
  /** `type` fora do catálogo conhecido. Preserva status e detail sem fingir compreensão. */
  | (Base & { tipo: 'desconhecido'; type: string });

export type TipoErroApi = ErroApi['tipo'];

/** Segmento final da URI do catálogo → variante. Fechado de propósito. */
const POR_SEGMENTO = {
  'requisicao-invalida': 'requisicaoInvalida',
  'nao-autenticado': 'naoAutenticado',
  'acesso-negado': 'acessoNegado',
  'nao-encontrado': 'naoEncontrado',
  'transicao-invalida': 'transicaoInvalida',
  'regra-negocio-violada': 'regraNegocioViolada',
  'conflito-concorrencia': 'conflitoConcorrencia',
  'limite-requisicoes': 'limiteRequisicoes',
  'nao-implementado': 'naoImplementado',
  'erro-interno': 'erroInterno',
  'saque-desabilitado': 'saqueDesabilitado',
  'checkin-fora-do-raio': 'checkinForaDoRaio',
  'checkin-acuracia-insuficiente': 'checkinAcuraciaInsuficiente',
  'checkin-localizacao-simulada': 'checkinLocalizacaoSimulada',
} as const satisfies Record<string, TipoErroApi>;

const TIPOS_CONHECIDOS: ReadonlySet<string> = new Set<TipoErroApi>([
  ...Object.values(POR_SEGMENTO),
  'semRede',
  'desconhecido',
]);

/** Guarda para não reconverter o que o interceptor do cliente já converteu. */
export function ehErroApi(valor: unknown): valor is ErroApi {
  if (typeof valor !== 'object' || valor === null) return false;
  const candidato = valor as { tipo?: unknown };
  return typeof candidato.tipo === 'string' && TIPOS_CONHECIDOS.has(candidato.tipo);
}

interface CorpoProblema {
  type?: unknown;
  detail?: unknown;
  status?: unknown;
  traceId?: unknown;
  errors?: unknown;
  retryAfter?: unknown;
}

function texto(valor: unknown, padrao: string): string {
  return typeof valor === 'string' && valor.length > 0 ? valor : padrao;
}

function segmentoDe(type: unknown): string | null {
  if (typeof type !== 'string' || type.length === 0) return null;
  // `about:blank` é legal no RFC e significa "sem tipo". O backend garante que não emite isso;
  // se aparecer, cai como desconhecido em vez de virar um erro genérico plausível.
  const partes = type.split('/');
  return partes[partes.length - 1] || null;
}

function camposDe(errors: unknown): ErroCampo[] {
  if (!Array.isArray(errors)) return [];
  return errors.flatMap((item) => {
    if (typeof item !== 'object' || item === null) return [];
    const registro = item as Record<string, unknown>;
    if (typeof registro.campo !== 'string' || typeof registro.mensagem !== 'string') return [];
    return [{ campo: registro.campo, mensagem: registro.mensagem }];
  });
}

/**
 * Converte qualquer coisa lançada pelo axios num `ErroApi`.
 *
 * Precisa aguentar três formatos diferentes de corpo, porque o backend tem TRÊS caminhos que
 * produzem erro: o `GlobalExceptionHandler` (com `traceId`), os escritores manuais de JSON em
 * `SecurityConfig` (401/403, sem `traceId`) e o `RateLimitFilter` (429, sem `traceId`, com
 * `retryAfter`). Os dois últimos rodam antes do DispatcherServlet — e 401 e 429 são justamente os
 * que o app mais recebe.
 */
export function paraErroApi(erro: unknown): ErroApi {
  if (ehErroApi(erro)) return erro;

  if (!axios.isAxiosError(erro)) {
    return {
      tipo: 'desconhecido',
      type: '',
      status: 0,
      detail: erro instanceof Error ? erro.message : 'Erro inesperado.',
    };
  }

  if (!erro.response) {
    return {
      tipo: 'semRede',
      status: 0,
      detail: 'Não foi possível falar com o servidor. Verifique sua conexão e tente de novo.',
    };
  }

  const corpo: CorpoProblema =
    typeof erro.response.data === 'object' && erro.response.data !== null
      ? (erro.response.data as CorpoProblema)
      : {};

  const status = typeof corpo.status === 'number' ? corpo.status : erro.response.status;
  const base: Base = {
    status,
    detail: texto(corpo.detail, 'Não foi possível concluir a operação.'),
    traceId: typeof corpo.traceId === 'string' ? corpo.traceId : undefined,
  };

  const segmento = segmentoDe(corpo.type);
  const tipo =
    segmento && segmento in POR_SEGMENTO
      ? POR_SEGMENTO[segmento as keyof typeof POR_SEGMENTO]
      : null;

  switch (tipo) {
    case 'requisicaoInvalida':
      return { ...base, tipo, erros: camposDe(corpo.errors) };
    case 'limiteRequisicoes':
      return {
        ...base,
        tipo,
        retryAfter:
          typeof corpo.retryAfter === 'number' ? corpo.retryAfter : cabecalhoRetryAfter(erro),
      };
    case null:
      return { ...base, tipo: 'desconhecido', type: texto(corpo.type, '') };
    default:
      return { ...base, tipo };
  }
}

function cabecalhoRetryAfter(erro: { response?: { headers?: unknown } }): number | null {
  const headers = erro.response?.headers as Record<string, unknown> | undefined;
  const bruto = headers?.['retry-after'] ?? headers?.['Retry-After'];
  const numero = Number(bruto);
  return Number.isFinite(numero) ? numero : null;
}

/** Mensagem única para exibir ao usuário. Nenhuma decisão de fluxo depende dela. */
export function mensagemDe(erro: ErroApi): string {
  return erro.detail;
}

/**
 * Só `conflitoConcorrencia` merece "tentar de novo" automático: é a única variante em que o mesmo
 * pedido, repetido, tende a passar. Repetir um 422 ou um 409 de transição só gera ruído.
 */
export function valeTentarDeNovo(erro: ErroApi): boolean {
  return erro.tipo === 'conflitoConcorrencia' || erro.tipo === 'semRede';
}
