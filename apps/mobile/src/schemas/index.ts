import { z } from 'zod';

/** Espelha as regras de Bean Validation do backend, para falhar no formulário e não na rede. */

export const loginSchema = z.object({
  email: z.email('Informe um e-mail válido.'),
  senha: z.string().min(1, 'Informe sua senha.'),
});
export type LoginForm = z.infer<typeof loginSchema>;

export const registroSchema = z.object({
  nome: z.string().min(1, 'Informe seu nome.').max(100, 'Nome muito longo (máx. 100).'),
  email: z.email('Informe um e-mail válido.'),
  handle: z
    .string()
    .min(3, 'O @ precisa de ao menos 3 caracteres.')
    .max(50, 'O @ pode ter no máximo 50 caracteres.'),
  // 12 é o mínimo do backend. Deixar o app aceitar menos só trocaria um erro imediato por um 400.
  senha: z.string().min(12, 'A senha precisa de ao menos 12 caracteres.'),
});
export type RegistroForm = z.infer<typeof registroSchema>;

// ─── Respostas ────────────────────────────────────────────────────────────────────────────────
//
// `z.guid()` e NÃO `z.uuid()`. O Zod 4 valida a versão do UUID em `uuid()`, e os identificadores do
// seed do backend (`bbbbbbbb-0000-0000-0000-000000000002`, `dddddddd-0000-...`) têm nibble de versão
// zero — são legíveis de propósito, para leitura humana no psql. `uuid()` os reprovaria, e o
// validador de contrato passaria a gritar contra dado perfeitamente válido, treinando quem lê o log
// a ignorar o aviso. `guid()` confere a forma 8-4-4-4-12, que é o que o app realmente precisa saber.

export const loginResponseSchema = z.object({
  accessToken: z.string().min(1),
  refreshToken: z.string().min(1),
  tipoToken: z.string(),
  expiresIn: z.number(),
});

export const meResponseSchema = z.object({
  id: z.guid(),
  email: z.string(),
  papel: z.enum(['USUARIO', 'ADMIN']),
});

export const missaoResponseSchema = z.object({
  id: z.guid(),
  criadorId: z.guid(),
  executorId: z.guid().nullable(),
  categoria: z.enum(['ENTREGA', 'COLETA', 'TRIBO', 'AJUDA']),
  status: z.enum([
    'RASCUNHO',
    'ABERTA',
    'ACEITA',
    'EM_ANDAMENTO',
    'AGUARDANDO_CONFIRMACAO',
    'EM_DISPUTA',
    'CONCLUIDA',
    'CANCELADA',
    'EXPIRADA',
  ]),
  titulo: z.string(),
  descricao: z.string(),
  xpRecompensa: z.number(),
  valorBrl: z.number(),
  tokensRecompensa: z.number(),
  poteTokens: z.number(),
  origemLat: z.number().nullable(),
  origemLon: z.number().nullable(),
  destinoLat: z.number().nullable(),
  destinoLon: z.number().nullable(),
  pontoCustodiaId: z.guid().nullable(),
  cep: z.string(),
  logradouro: z.string(),
  bairro: z.string(),
  cidade: z.string(),
  uf: z.string(),
  raioCheckinM: z.number(),
  pesoKg: z.number().nullable(),
  volumeL: z.number().nullable(),
  janelaInicio: z.string(),
  janelaFim: z.string(),
  criadaEm: z.string(),
  aceitaEm: z.string().nullable(),
  concluidaEm: z.string().nullable(),
  versao: z.number(),
});

export const missaoProximaResponseSchema = z.object({
  missao: missaoResponseSchema,
  distanciaM: z.number(),
});

export const carteiraResponseSchema = z.object({
  id: z.guid(),
  usuarioId: z.guid(),
  saldoBrl: z.number(),
  saldoTokens: z.number(),
});

export const lancamentoResponseSchema = z.object({
  id: z.guid(),
  sinal: z.enum(['CREDITO', 'DEBITO']),
  motivo: z.enum([
    'RECOMPENSA_MISSAO',
    'TRANSFERENCIA_ENVIADA',
    'TRANSFERENCIA_RECEBIDA',
    'FINANCIAMENTO_TRIBO',
    'SAQUE',
    'BONUS',
    'ESTORNO',
  ]),
  valorBrl: z.number(),
  valorTokens: z.number(),
  missaoId: z.guid().nullable(),
  contraparteCarteiraId: z.guid().nullable(),
  mensagem: z.string().nullable(),
  saldoAposBrl: z.number(),
  saldoAposTokens: z.number(),
  criadoEm: z.string(),
});

/** `PaginaResponse<T>` do backend — envelope próprio, não o `Page` do Spring Data. */
export function paginaSchema<T extends z.ZodTypeAny>(item: T) {
  return z.object({
    conteudo: z.array(item),
    pagina: z.number(),
    tamanho: z.number(),
    totalElementos: z.number(),
    totalPaginas: z.number(),
    primeira: z.boolean(),
    ultima: z.boolean(),
  });
}
