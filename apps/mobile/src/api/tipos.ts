/**
 * Espelho TypeScript do contrato do backend (`services/api`).
 *
 * Os nomes dos campos são os do JSON, verbatim — nada de camelCase "arrumado" aqui. Quando o
 * backend mudar, o lugar de sentir é este arquivo mais os schemas Zod de `src/schemas`, e não
 * quinze telas.
 */

export type CategoriaMissao = 'ENTREGA' | 'COLETA' | 'TRIBO' | 'AJUDA';

export type StatusMissao =
  | 'RASCUNHO'
  | 'ABERTA'
  | 'ACEITA'
  | 'EM_ANDAMENTO'
  | 'AGUARDANDO_CONFIRMACAO'
  | 'EM_DISPUTA'
  | 'CONCLUIDA'
  | 'CANCELADA'
  | 'EXPIRADA';

export const STATUS_TERMINAIS: readonly StatusMissao[] = ['CONCLUIDA', 'CANCELADA', 'EXPIRADA'];

export type ComplexidadeMissao = 'LEVE' | 'MEDIA' | 'PESADA';

export type PapelUsuario = 'USUARIO' | 'ADMIN';

export type SinalLancamento = 'CREDITO' | 'DEBITO';

export type MotivoLancamento =
  | 'RECOMPENSA_MISSAO'
  | 'TRANSFERENCIA_ENVIADA'
  | 'TRANSFERENCIA_RECEBIDA'
  | 'FINANCIAMENTO_TRIBO'
  | 'SAQUE'
  | 'BONUS'
  | 'ESTORNO';

/** Envelope de paginação do backend. NÃO é o `Page` do Spring Data — ver `PaginaResponse.java`. */
export interface PaginaResponse<T> {
  conteudo: T[];
  pagina: number;
  tamanho: number;
  totalElementos: number;
  totalPaginas: number;
  primeira: boolean;
  ultima: boolean;
}

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  tipoToken: string;
  /** Segundos até o access token expirar (900 = 15 min). */
  expiresIn: number;
}

export interface MeResponse {
  id: string;
  email: string;
  papel: PapelUsuario;
}

export interface MissaoResponse {
  id: string;
  criadorId: string;
  executorId: string | null;
  categoria: CategoriaMissao;
  status: StatusMissao;
  titulo: string;
  descricao: string;
  xpRecompensa: number;
  /**
   * Sempre 0 — `ck_missao_economia` (V15) exige `valor_brl = 0` em toda missão. Existe no tipo
   * porque existe no JSON, e some daqui no dia em que sumir de lá. NENHUM componente o exibe:
   * ver ADR 0009 e apps/mobile/CLAUDE.md.
   */
  valorBrl: number;
  tokensRecompensa: number;
  poteTokens: number;
  origemLat: number | null;
  origemLon: number | null;
  destinoLat: number | null;
  destinoLon: number | null;
  pontoCustodiaId: string | null;
  cep: string;
  logradouro: string;
  bairro: string;
  cidade: string;
  uf: string;
  raioCheckinM: number;
  pesoKg: number | null;
  volumeL: number | null;
  janelaInicio: string;
  janelaFim: string;
  criadaEm: string;
  aceitaEm: string | null;
  concluidaEm: string | null;
  versao: number;
}

/**
 * Item do radar. `distanciaM` é medida por `ST_Distance` sobre `geography` no PostGIS — metros,
 * uma casa decimal. O app FORMATA esse número; jamais o recalcula.
 */
export interface MissaoProximaResponse {
  missao: MissaoResponse;
  distanciaM: number;
}

export interface CarteiraResponse {
  id: string;
  usuarioId: string;
  /** Sempre 0.00 e sem movimentação (ADR 0009). A UI não o exibe. */
  saldoBrl: number;
  saldoTokens: number;
}

export interface LancamentoResponse {
  id: string;
  sinal: SinalLancamento;
  motivo: MotivoLancamento;
  valorBrl: number;
  valorTokens: number;
  missaoId: string | null;
  contraparteCarteiraId: string | null;
  mensagem: string | null;
  saldoAposBrl: number;
  saldoAposTokens: number;
  criadoEm: string;
}

export interface PreviaRecompensaResponse {
  xpRecompensa: number;
  tokensRecompensa: number;
  complexidade: ComplexidadeMissao;
  versaoFormula: number;
}

export interface RegistrarCheckinRequest {
  lat: number;
  lon: number;
  acuraciaM: number;
  mocked: boolean | null;
}

export type EscopoMissao = 'CRIADAS' | 'EXECUTANDO';

export interface FiltroMissoes {
  status?: StatusMissao;
  categoria?: CategoriaMissao;
  cidade?: string;
  bairro?: string;
  minhas?: EscopoMissao;
  pagina?: number;
  tamanho?: number;
  ordenarPor?: 'CRIADA_EM' | 'JANELA_FIM' | 'VALOR_BRL' | 'XP_RECOMPENSA';
  direcao?: 'ASC' | 'DESC';
}

export interface FiltroProximas {
  lat: number;
  lon: number;
  raioMetros?: number;
  categoria?: CategoriaMissao;
  limite?: number;
}
