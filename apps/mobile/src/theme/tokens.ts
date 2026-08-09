/**
 * ÚNICO arquivo do app autorizado a conter cor literal (apps/mobile/CLAUDE.md).
 * A regra é aplicada por lint: ver `no-restricted-syntax` em eslint.config.js.
 *
 * A paleta é herdada do protótipo Flutter descartado — a identidade visual continua, a tecnologia
 * é outra.
 */

export const cores = {
  verdePrimario: '#1D9E75',
  verdeEscuro: '#0F6E56',
  verdeClaro: '#E1F5EE',
  ambar: '#BA7517',
  ambarClaro: '#FFF1E6',
  coral: '#D85A30',
  coralClaro: '#FFE4DA',
  tinta: '#1A2520',
  tinta70: '#4A5853',
  tinta50: '#7A8782',
  linha: '#D7DDDA',
  papel: '#F7F9F8',
  branco: '#FFFFFF',
  transparente: 'transparent',
} as const;

export type NomeCor = keyof typeof cores;

/**
 * Escala de espaçamento. Só estes valores — margem "de olho" (13, 18, 22) é o que faz um design
 * system parar de parecer um sistema depois da quinta tela.
 */
export const espaco = {
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 24,
  xxl: 32,
} as const;

export const raio = {
  sm: 6,
  md: 10,
  lg: 16,
  pilula: 999,
} as const;

export const tipografia = {
  titulo: { fontSize: 22, lineHeight: 28, fontWeight: '700' },
  subtitulo: { fontSize: 17, lineHeight: 24, fontWeight: '600' },
  corpo: { fontSize: 15, lineHeight: 22, fontWeight: '400' },
  rotulo: { fontSize: 13, lineHeight: 18, fontWeight: '600' },
  legenda: { fontSize: 12, lineHeight: 16, fontWeight: '400' },
} as const;

/**
 * Cor por categoria de missão. A chave é o enum `CategoriaMissao` do backend — não string solta,
 * para que uma categoria nova quebre o typecheck em vez de cair num fallback silencioso.
 */
export const coresCategoria = {
  ENTREGA: { fundo: cores.verdeClaro, texto: cores.verdePrimario },
  COLETA: { fundo: cores.ambarClaro, texto: cores.ambar },
  TRIBO: { fundo: cores.verdeClaro, texto: cores.verdeEscuro },
  AJUDA: { fundo: cores.coralClaro, texto: cores.coral },
} as const;

/**
 * Cor por STATUS de missão, para o chip do detalhe e da lista.
 *
 * Antes o chip de status saía sem cor nenhuma, e os nove estados eram indistinguíveis à distância —
 * o usuário tinha de ler para saber se a própria missão estava aberta, em disputa ou expirada.
 *
 * A paleta agrupa por SIGNIFICADO, não por estado: neutro para o que só espera (RASCUNHO,
 * CANCELADA, EXPIRADA), verde para o que avança, âmbar para o que exige ação de alguém, coral para
 * o que deu errado. Dois estados com a mesma cor é intencional quando pedem a mesma leitura.
 *
 * A chave é o enum `StatusMissao` do backend, com o mesmo efeito de `coresCategoria`: um status novo
 * sem cor quebra o typecheck em vez de cair em `undefined` na tela.
 */
export const coresStatus = {
  RASCUNHO: { fundo: cores.papel, texto: cores.tinta70 },
  ABERTA: { fundo: cores.verdeClaro, texto: cores.verdeEscuro },
  ACEITA: { fundo: cores.verdeClaro, texto: cores.verdePrimario },
  EM_ANDAMENTO: { fundo: cores.verdeClaro, texto: cores.verdePrimario },
  AGUARDANDO_CONFIRMACAO: { fundo: cores.ambarClaro, texto: cores.ambar },
  CONCLUIDA: { fundo: cores.verdePrimario, texto: cores.branco },
  CANCELADA: { fundo: cores.papel, texto: cores.tinta50 },
  EXPIRADA: { fundo: cores.papel, texto: cores.tinta50 },
  EM_DISPUTA: { fundo: cores.coralClaro, texto: cores.coral },
} as const;
