import type {
  CarteiraResponse,
  LancamentoResponse,
  LoginResponse,
  MeResponse,
  MissaoProximaResponse,
  MissaoResponse,
  PaginaResponse,
} from '@/api/tipos';

export const TOKENS: LoginResponse = {
  accessToken: 'access-1',
  refreshToken: 'refresh-1',
  tipoToken: 'Bearer',
  expiresIn: 900,
};

export const USUARIO: MeResponse = {
  id: 'bbbbbbbb-0000-0000-0000-000000000002',
  email: 'alice@omnitribo.dev',
  papel: 'USUARIO',
};

export function missao(sobrescrever: Partial<MissaoResponse> = {}): MissaoResponse {
  return {
    id: 'dddddddd-0000-0000-0000-000000000003',
    criadorId: 'bbbbbbbb-0000-0000-0000-000000000001',
    executorId: null,
    categoria: 'ENTREGA',
    status: 'ABERTA',
    titulo: 'Entregar caixa de ferramentas na Rua dos Pinheiros',
    descricao: 'A entrega falhou ontem; o pacote está no ponto de custódia.',
    xpRecompensa: 69,
    // Sempre 0 — ck_missao_economia (V15). A UI ignora, e há teste garantindo que ignora.
    valorBrl: 0,
    tokensRecompensa: 23,
    poteTokens: 0,
    origemLat: -23.564,
    origemLon: -46.6934,
    destinoLat: null,
    destinoLon: null,
    pontoCustodiaId: null,
    cep: '05422030',
    logradouro: 'Rua dos Pinheiros, 500',
    bairro: 'Pinheiros',
    cidade: 'São Paulo',
    uf: 'SP',
    raioCheckinM: 50,
    pesoKg: 3.5,
    volumeL: 20,
    janelaInicio: '2026-08-08T12:00:00Z',
    janelaFim: '2026-08-09T12:00:00Z',
    criadaEm: '2026-08-07T10:00:00Z',
    aceitaEm: null,
    concluidaEm: null,
    versao: 0,
    ...sobrescrever,
  };
}

export function pagina<T>(
  conteudo: T[],
  sobrescrever: Partial<PaginaResponse<T>> = {},
): PaginaResponse<T> {
  return {
    conteudo,
    pagina: 0,
    tamanho: 20,
    totalElementos: conteudo.length,
    totalPaginas: conteudo.length === 0 ? 0 : 1,
    primeira: true,
    ultima: true,
    ...sobrescrever,
  };
}

export function proxima(
  distanciaM: number,
  sobrescrever: Partial<MissaoResponse> = {},
): MissaoProximaResponse {
  return { missao: missao(sobrescrever), distanciaM };
}

export const CARTEIRA: CarteiraResponse = {
  id: 'cccccccc-0000-0000-0000-000000000002',
  usuarioId: USUARIO.id,
  saldoBrl: 0,
  saldoTokens: 41,
};

export const LANCAMENTO: LancamentoResponse = {
  id: 'eeeeeeee-0000-0000-0000-000000000001',
  sinal: 'CREDITO',
  motivo: 'RECOMPENSA_MISSAO',
  valorBrl: 0,
  valorTokens: 23,
  missaoId: 'dddddddd-0000-0000-0000-000000000003',
  contraparteCarteiraId: null,
  mensagem: null,
  saldoAposBrl: 0,
  saldoAposTokens: 41,
  criadoEm: '2026-08-07T18:30:00Z',
};

/** Corpo RFC 9457 como o backend o emite, incluindo `traceId`. */
export function problema(
  type: string,
  status: number,
  detail: string,
  extra: Record<string, unknown> = {},
) {
  return {
    type: `https://omnitribo.dev/problemas/${type}`,
    title: 'Erro',
    status,
    detail,
    instance: '/api/v1/teste',
    traceId: '11111111-2222-3333-4444-555555555555',
    ...extra,
  };
}
