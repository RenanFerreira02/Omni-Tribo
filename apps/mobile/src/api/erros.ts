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
  /**
   * 422 fora do raio, COM os números medidos pelo servidor.
   *
   * `distanciaM` e `raioM` chegam como campos de extensão do RFC 9457 e existem para a tela poder
   * escrever "você está a 180 m; aproxime-se para até 50 m". A alternativa seria parsear o
   * `detail` — proibido, porque é copy e muda a cada revisão. Opcionais porque o replay de uma
   * rejeição antiga pode não ter a medida persistida.
   */
  | (Base & { tipo: 'checkinForaDoRaio'; distanciaM?: number; raioM?: number })
  | (Base & { tipo: 'checkinAcuraciaInsuficiente'; acuraciaM?: number; acuraciaMaximaM?: number })
  | (Base & { tipo: 'checkinLocalizacaoSimulada' })
  /** 503: provedor externo (clima, CEP) fora do ar. A tela ESCONDE o recurso, não mostra erro. */
  | (Base & { tipo: 'servicoExternoIndisponivel' })
  | (Base & { tipo: 'limiteRequisicoes'; retryAfter: number | null })
  // `naoImplementado` SAIU: o backend não emite mais `nao-implementado` — o handler de
  // `UnsupportedOperationException` era código morto e foi removido junto com a URI do catálogo.
  // Um `type` que ninguém emite é promessa de reação de UI para um caso que não acontece.
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
  'erro-interno': 'erroInterno',
  'saque-desabilitado': 'saqueDesabilitado',
  'checkin-fora-do-raio': 'checkinForaDoRaio',
  'checkin-acuracia-insuficiente': 'checkinAcuraciaInsuficiente',
  'checkin-localizacao-simulada': 'checkinLocalizacaoSimulada',
  'servico-externo-indisponivel': 'servicoExternoIndisponivel',
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
  /** Campos de extensão das rejeições de check-in. Ver `CheckinRejeitadoException` no backend. */
  distanciaM?: unknown;
  raioM?: unknown;
  acuraciaM?: unknown;
  acuraciaMaximaM?: unknown;
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
    case 'checkinForaDoRaio':
      return { ...base, tipo, distanciaM: numero(corpo.distanciaM), raioM: numero(corpo.raioM) };
    case 'checkinAcuraciaInsuficiente':
      return {
        ...base,
        tipo,
        acuraciaM: numero(corpo.acuraciaM),
        acuraciaMaximaM: numero(corpo.acuraciaMaximaM),
      };
    case null:
      return { ...base, tipo: 'desconhecido', type: texto(corpo.type, '') };
    default:
      return { ...base, tipo };
  }
}

/** `undefined` quando ausente ou não numérico — a tela cai no `detail` do servidor. */
function numero(valor: unknown): number | undefined {
  return typeof valor === 'number' && Number.isFinite(valor) ? valor : undefined;
}

function cabecalhoRetryAfter(erro: { response?: { headers?: unknown } }): number | null {
  const headers = erro.response?.headers as Record<string, unknown> | undefined;
  const bruto = headers?.['retry-after'] ?? headers?.['Retry-After'];
  const numero = Number(bruto);
  return Number.isFinite(numero) ? numero : null;
}

/**
 * Mensagem única para exibir ao usuário. Nenhuma decisão de fluxo depende dela.
 *
 * O 429 ganha o TEMPO DE ESPERA quando o servidor o informa. `retryAfter` era decodificado do corpo
 * e do header `Retry-After` e não tinha um único consumidor — o usuário lia "aguarde antes de tentar
 * novamente" sem saber se eram cinco segundos ou quinze minutos, que é a diferença entre esperar e
 * desistir do app. Compor aqui, e não em cada tela, é o que faz toda mensagem de limite ganhar o
 * dado de uma vez.
 */
export function mensagemDe(erro: ErroApi): string {
  if (erro.tipo === 'limiteRequisicoes' && erro.retryAfter !== null) {
    return `${erro.detail} ${esperaLegivel(erro.retryAfter)}`;
  }
  return erro.detail;
}

/** "Tente de novo em 45 segundos" / "em 2 minutos". Arredonda para cima: prometer menos frustra. */
function esperaLegivel(segundos: number): string {
  if (segundos <= 0) return 'Tente de novo agora.';
  if (segundos < 60) return `Tente de novo em ${Math.ceil(segundos)} segundos.`;
  const minutos = Math.ceil(segundos / 60);
  return `Tente de novo em ${minutos} ${minutos === 1 ? 'minuto' : 'minutos'}.`;
}

/**
 * Só `conflitoConcorrencia` merece "tentar de novo" automático: é a única variante em que o mesmo
 * pedido, repetido, tende a passar. Repetir um 422 ou um 409 de transição só gera ruído.
 */
export function valeTentarDeNovo(erro: ErroApi): boolean {
  return erro.tipo === 'conflitoConcorrencia' || erro.tipo === 'semRede';
}

/**
 * A rotação de refresh falhou porque a SESSÃO acabou — e não por um obstáculo temporário.
 *
 * **Só isto autoriza encerrar a sessão**, e a distinção passou a ser vital: o backend removeu a
 * isenção de rate limit do `POST /auth/refresh` (a justificativa antiga era falsa — `refresh()`
 * nunca chamou o serviço de bloqueio). Um 429 ali é rotina sob concorrência, e antes caía no mesmo
 * `catch` genérico que um 401 legítimo.
 *
 * O custo desse engano não era uma tela de erro: `encerrar()` APAGA o refresh do keystore, então um
 * limite temporário destruía uma sessão de 30 dias, de forma irreversível. No boot era pior — o
 * usuário abria o app deslogado sem ter feito nada.
 *
 * `semRede` e `limiteRequisicoes` preservam a sessão: o token continua válido, só não deu para usar
 * agora. Qualquer outro tipo inesperado também preserva — a postura segura aqui é manter a sessão e
 * deixar a requisição falhar, nunca o contrário.
 */
export function sessaoAcabou(erro: ErroApi): boolean {
  return erro.tipo === 'naoAutenticado' || erro.tipo === 'acessoNegado';
}
