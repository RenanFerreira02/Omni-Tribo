import { z } from 'zod';

/** Espelha as regras de Bean Validation do backend, para falhar no formulário e não na rede. */

/**
 * Espelho de `ComplexidadeMissao`. Um lugar só — estava repetido em três, e um enum de domínio
 * copiado é a forma mais silenciosa de as cópias divergirem quando o backend ganhar um valor novo.
 */
export const complexidadeSchema = z.enum(['LEVE', 'MEDIA', 'PESADA']);

export const faixaRiscoSchema = z.enum(['BAIXO', 'MEDIO', 'ALTO']);

export const loginSchema = z.object({
  email: z.email('Informe um e-mail válido.'),
  senha: z.string().min(1, 'Informe sua senha.'),
});

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

export const usuarioBuscaResponseSchema = z.object({
  id: z.guid(),
  handle: z.string(),
  nome: z.string(),
  tribo: z.string().nullable(),
});

export const transferenciaSchema = z.object({
  // Continua validando o formato, mas o valor NÃO é mais digitado: vem do resultado da busca por
  // handle. A guarda fica porque um id inválido aqui significaria defeito nosso, não erro de quem
  // preenche — e falhar com mensagem é melhor que mandar lixo para a rede.
  destinatarioId: z.guid('Escolha um membro da sua tribo.'),
  tokens: z
    .number('Informe a quantidade de tokens.')
    .int('Token não é fracionado.')
    .positive('A transferência precisa ser maior que zero.')
    // Teto por transação do backend. Deixar passar aqui só trocaria um aviso imediato por um 422.
    .max(500, 'O máximo por transferência é 500 tokens.'),
  mensagem: z.string().max(200, 'A mensagem pode ter no máximo 200 caracteres.').optional(),
});

/**
 * Criação de missão.
 *
 * **Espelha as SEIS regras cruzadas de `CriacaoMissaoVerificador`**, e não só os campos. Elas não
 * são validação redundante: o servidor RECUSA com 400 uma combinação que a tela deixaria montar —
 * mandar `complexidade` junto com peso e volume, por exemplo, não é ignorado, é erro. Sem espelhar,
 * o usuário preencheria o formulário inteiro para descobrir isso no envio.
 *
 * O que NÃO está aqui, deliberadamente: recompensa. Não há campo de XP nem de token, e a fórmula
 * não é reimplementada — o número vem de `POST /missoes/previa-recompensa`. Duas fontes de verdade
 * divergiriam no primeiro ajuste de parâmetro do servidor. Ver ADR 0009.
 */
export const criarMissaoSchema = z
  .object({
    categoria: z.enum(['ENTREGA', 'COLETA', 'TRIBO', 'AJUDA']),
    titulo: z
      .string()
      .min(5, 'O título precisa de ao menos 5 caracteres.')
      .max(120, 'O título pode ter no máximo 120 caracteres.'),
    descricao: z
      .string()
      .min(1, 'Descreva o que precisa ser feito.')
      .max(2000, 'A descrição pode ter no máximo 2000 caracteres.'),
    complexidade: complexidadeSchema.optional(),
    pesoKg: z.number().min(0, 'O peso não pode ser negativo.').optional(),
    volumeL: z.number().min(0, 'O volume não pode ser negativo.').optional(),
    origemLat: z.number().min(-90).max(90),
    origemLon: z.number().min(-180).max(180),
    cep: z.string().regex(/^\d{8}$/, 'O CEP tem 8 dígitos, sem hífen.'),
    logradouro: z.string().min(1, 'Informe o logradouro.').max(200),
    bairro: z.string().min(1, 'Informe o bairro.').max(100),
    cidade: z.string().min(1, 'Informe a cidade.').max(100),
    uf: z.string().regex(/^[A-Z]{2}$/, 'UF com 2 letras maiúsculas.'),
    raioCheckinM: z
      .number()
      .int()
      .min(10, 'O raio mínimo de check-in é 10 m.')
      .max(2000, 'O raio máximo de check-in é 2000 m.'),
    janelaInicio: z.date(),
    janelaFim: z.date(),
    pontoCustodiaId: z.guid().optional(),
  })
  .superRefine((dados, ctx) => {
    if (dados.janelaFim <= dados.janelaInicio) {
      ctx.addIssue({
        code: 'custom',
        path: ['janelaFim'],
        message: 'O fim da janela precisa ser depois do início.',
      });
    }

    const temPeso = dados.pesoKg !== undefined;
    const temVolume = dados.volumeL !== undefined;
    const movimentaObjeto = dados.categoria === 'ENTREGA' || dados.categoria === 'COLETA';

    if (movimentaObjeto && !(temPeso && temVolume)) {
      ctx.addIssue({
        code: 'custom',
        path: temPeso ? ['volumeL'] : ['pesoKg'],
        message: 'Entrega e coleta exigem peso e volume — é deles que sai a complexidade.',
      });
    }

    if (temPeso && temVolume && dados.complexidade !== undefined) {
      // Recusa, não "ignora": com peso e volume o servidor DERIVA a complexidade, e um valor
      // declarado junto vira 400.
      ctx.addIssue({
        code: 'custom',
        path: ['complexidade'],
        message: 'Com peso e volume, a complexidade é calculada — não a informe.',
      });
    }

    if (!temPeso && !temVolume && dados.complexidade === undefined) {
      ctx.addIssue({
        code: 'custom',
        path: ['complexidade'],
        message: 'Sem peso e volume, informe a complexidade.',
      });
    }
  });
export type CriarMissaoForm = z.infer<typeof criarMissaoSchema>;

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
  // Nulos para quem não participa da missão — o servidor recorta o endereço por participação.
  // Sem `.nullable()` aqui, toda listagem de missão alheia dispara um aviso de divergência em dev,
  // treinando quem lê o log a ignorar o aviso. Ver o comentário em `tipos.ts`.
  cep: z.string().nullable(),
  logradouro: z.string().nullable(),
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
  complexidade: complexidadeSchema,
  versaoFormula: z.number(),
  nivelMinimo: z.number(),
  // Risco congelado na criação. Nulos na maioria das missões — só o webhook de entrega falida
  // avalia risco. `nullable`, e não `optional`: o servidor SEMPRE manda as chaves, com valor nulo
  // quando não houve avaliação. Aceitar ausência esconderia uma mudança de contrato.
  multiplicadorRisco: z.number().nullable(),
  faixaRisco: faixaRiscoSchema.nullable(),
  avisoRisco: z.string().nullable(),
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
  // Espelha o CHECK de `lancamento.motivo` no backend. Os três últimos entraram depois:
  // FINANCIAMENTO_PATROCINADOR e APORTE_PATROCINADOR na V23, RESGATE na V26. Sem eles aqui, um
  // extrato que contivesse a linha da queima reprovaria em `validarEmDev` — o app quebraria em
  // desenvolvimento exatamente quando o usuário resgatasse algo.
  motivo: z.enum([
    'RECOMPENSA_MISSAO',
    'TRANSFERENCIA_ENVIADA',
    'TRANSFERENCIA_RECEBIDA',
    'FINANCIAMENTO_TRIBO',
    'FINANCIAMENTO_PATROCINADOR',
    'APORTE_PATROCINADOR',
    'RESGATE',
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

export const triboResponseSchema = z.object({
  id: z.guid(),
  nome: z.string(),
  bairro: z.string(),
  centroLat: z.number().nullable(),
  centroLon: z.number().nullable(),
});

export const conquistaResponseSchema = z.object({
  codigo: z.string(),
  titulo: z.string(),
  descricao: z.string(),
  conquistada: z.boolean(),
  progresso: z.number(),
  meta: z.number(),
});

export const perfilResponseSchema = z.object({
  id: z.guid(),
  nome: z.string(),
  email: z.string(),
  handle: z.string(),
  papel: z.enum(['USUARIO', 'ADMIN']),
  // `.nullable()` e não `.optional()`: o backend emite a chave com null quando o usuário não tem
  // tribo. Marcar como opcional deixaria passar uma resposta SEM a chave, que é outra coisa.
  tribo: triboResponseSchema.nullable(),
  xp: z.number(),
  nivel: z.number(),
  xpNivelAtual: z.number(),
  xpProximoNivel: z.number(),
  streak: z.number(),
  conquistas: z.array(conquistaResponseSchema),
});

export const consentimentoResponseSchema = z.object({
  tipo: z.enum(['LOCALIZACAO', 'NOTIFICACAO', 'TERMOS']),
  concedido: z.boolean(),
  versaoTexto: z.string().nullable(),
  registradoEm: z.string().nullable(),
});

export const alertaResponseSchema = z.object({
  id: z.guid(),
  tipo: z.string(),
  titulo: z.string(),
  corpo: z.string(),
  missaoId: z.guid().nullable(),
  lido: z.boolean(),
  criadoEm: z.string(),
});

export const pontoCustodiaResponseSchema = z.object({
  id: z.guid(),
  codigo: z.string(),
  tipo: z.enum(['LOJA', 'LOCKER', 'PORTARIA', 'VIZINHO']),
  apelido: z.string(),
  lat: z.number(),
  lon: z.number(),
  capacidade: z.number(),
  ocupacao: z.number(),
  distanciaM: z.number().nullable(),
});

export const climaResponseSchema = z.object({
  temperaturaC: z.number(),
  sensacaoC: z.number(),
  codigo: z.number(),
  descricao: z.string(),
  medidoEm: z.string().nullable(),
});

export const enderecoResponseSchema = z.object({
  cep: z.string(),
  logradouro: z.string(),
  bairro: z.string(),
  cidade: z.string(),
  uf: z.string(),
});

export const previaRecompensaResponseSchema = z.object({
  xpRecompensa: z.number(),
  tokensRecompensa: z.number(),
  complexidade: complexidadeSchema,
  versaoFormula: z.number(),
  // Sempre 1,00 nesta rota: a prévia serve missão criada por usuário, que não passa por avaliação
  // de risco. Não é nullable — aqui o servidor manda o neutro, não a ausência.
  multiplicadorRisco: z.number(),
});

export const transferenciaResponseSchema = z.object({
  lancamentoSaidaId: z.guid(),
  lancamentoEntradaId: z.guid().nullable(),
  saldoTokensRemetente: z.number(),
  replay: z.boolean(),
});

/** `PaginaResponse<T>` do backend — envelope próprio, não o `Page` do Spring Data. */
export const beneficioResponseSchema = z.object({
  id: z.guid(),
  titulo: z.string(),
  descricao: z.string(),
  custoTokens: z.number(),
  tipo: z.enum(['BEM', 'PERCENTUAL']),
  parceiroId: z.guid(),
  parceiroNome: z.string(),
  bairro: z.string(),
  // Ausente no recorte por tribo, e nulo é resposta legítima do servidor.
  distanciaM: z.number().nullish(),
});

export const resgateResponseSchema = z.object({
  id: z.guid(),
  beneficioId: z.guid(),
  custoTokens: z.number(),
  codigoRetirada: z.string(),
  status: z.enum(['PENDENTE', 'UTILIZADO']),
  criadoEm: z.string(),
  utilizadoEm: z.string().nullable(),
  saldoTokensRestante: z.number(),
  replay: z.boolean(),
});

/**
 * Painel de impacto (ADMIN).
 *
 * As taxas e a mediana são `nullable` porque o servidor devolve `null` — e não zero — quando não há
 * denominador ou amostra. Aceitar só `number` aqui faria `validarEmDev` gritar num sistema
 * recém-instalado, que é exatamente quando os nulos aparecem.
 *
 * Os valores em BRL são `number`, como `valorBrl` e `saldoBrl` do resto do app: o servidor os
 * serializa como NÚMERO JSON. A primeira versão deste schema pedia `string` — e passou nos testes
 * porque a FIXTURE também mentia. Quem desmentiu foi a resposta real do servidor. Fixture que não
 * espelha o servidor não testa contrato nenhum; testa a si mesma.
 */
export const impactoResponseSchema = z.object({
  geradoEm: z.string(),
  entregasFalidas: z.object({
    recebidas: z.number(),
    convertidas: z.number(),
    pendentes: z.number(),
    recusadasPontoLotado: z.number(),
    recusadasSemPatrocinio: z.number(),
    taxaConversao: z.number().nullable(),
  }),
  missoesDeRetirada: z.object({
    criadas: z.number(),
    concluidas: z.number(),
    taxaConclusao: z.number().nullable(),
    medianaAteCheckinSegundos: z.number().nullable(),
    amostraMediana: z.number(),
  }),
  custoEvitado: z.object({
    reentregasEvitadas: z.number(),
    premissaCustoReentregaBrl: z.number(),
    baseBrl: z.number(),
    menos50Brl: z.number(),
    mais50Brl: z.number(),
  }),
  tokens: z.object({
    aportados: z.number(),
    emCarteiras: z.number(),
    emPotes: z.number(),
    emCirculacao: z.number(),
    resgatados: z.number(),
  }),
});

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
