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

/**
 * Variantes de TEXTO, derivadas das cores de marca.
 *
 * <b>Por que elas existem.</b> Os 12 tokens acima são a identidade visual e não mudam — a
 * especificação os fixou por hex. Mas eles foram desenhados para PREENCHIMENTO, e usados como
 * texto reprovavam em WCAG AA: `tinta50` dava 3,54:1, `ambar` 3,36:1 e `coral` 3,20:1 contra os
 * fundos reais do app, quando o mínimo para texto normal é 4,5:1. Onze dos vinte e dois pares
 * texto/fundo do app reprovavam.
 *
 * Cada variante é a mesma cor escurecida até o limiar, preservando o matiz — não uma cor nova.
 * A regra de uso é simples: <b>preenchimento e ícone usam `cores`; texto usa `textoAcessivel`.</b>
 *
 * O verde não precisou de variante: `verdeEscuro` já dá 5,46:1 sobre `verdeClaro` e 6,2:1 sobre
 * branco, e passou a ser o token de texto verde do app.
 */
export const textoAcessivel = {
  /** `tinta50` escurecido. Legenda, ajuda e texto secundário. 4,52:1 sobre papel. */
  suave: '#6A7571',
  /** `ambar` escurecido. XP e chips de COLETA. 4,55:1 no pior fundo. */
  ambar: '#9C6213',
  /** `coral` escurecido. Erros e chips de AJUDA. 4,56:1 no pior fundo. */
  coral: '#AF4927',
  /** `linha` escurecido. Borda de campo — WCAG 1.4.11 exige 3:1 para componente não-textual. */
  borda: '#909492',
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
  /**
   * Números grandes que existem para serem lidos de longe: saldo, XP na tela de entrada, glifo de
   * onboarding. Estavam como `fontSize` solto em quatro telas — a escala existe justamente para que
   * "o número grande" seja o MESMO número grande em todas elas.
   */
  display: { fontSize: 34, lineHeight: 40, fontWeight: '700' },
  destaque: { fontSize: 32, lineHeight: 38, fontWeight: '700' },
  titulo: { fontSize: 22, lineHeight: 28, fontWeight: '700' },
  subtitulo: { fontSize: 17, lineHeight: 24, fontWeight: '600' },
  corpo: { fontSize: 15, lineHeight: 22, fontWeight: '400' },
  rotulo: { fontSize: 13, lineHeight: 18, fontWeight: '600' },
  legenda: { fontSize: 12, lineHeight: 16, fontWeight: '400' },
  /** Glifo das abas. Decorativo — sempre com `accessibilityElementsHidden`. */
  icone: { fontSize: 20 },
} as const;

/**
 * Cor por categoria de missão. A chave é o enum `CategoriaMissao` do backend — não string solta,
 * para que uma categoria nova quebre o typecheck em vez de cair num fallback silencioso.
 */
/**
 * <b>TRIBO usa `verdeEscuro` como PREENCHIMENTO, e essa mudança resolve duas coisas de uma vez.</b>
 *
 * Antes, ENTREGA e TRIBO compartilhavam o mesmo fundo `verdeClaro` e se distinguiam só pela cor do
 * texto. Ao escurecer o texto de ENTREGA para atingir contraste, os dois chips ficariam
 * IDÊNTICOS — a correção de acessibilidade teria apagado a distinção de categoria.
 *
 * Invertendo TRIBO para fundo escuro com texto branco (6,2:1), as quatro categorias voltam a ser
 * distinguíveis, todas passam em AA, e o mapeamento fica mais literal do que era: a especificação
 * diz "Tribo→verdeEscuro", e agora é o verdeEscuro que se vê.
 */
export const coresCategoria = {
  ENTREGA: { fundo: cores.verdeClaro, texto: cores.verdeEscuro },
  COLETA: { fundo: cores.ambarClaro, texto: textoAcessivel.ambar },
  TRIBO: { fundo: cores.verdeEscuro, texto: cores.branco },
  AJUDA: { fundo: cores.coralClaro, texto: textoAcessivel.coral },
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
  ACEITA: { fundo: cores.verdeClaro, texto: cores.verdeEscuro },
  EM_ANDAMENTO: { fundo: cores.verdeClaro, texto: cores.verdeEscuro },
  AGUARDANDO_CONFIRMACAO: { fundo: cores.ambarClaro, texto: textoAcessivel.ambar },
  // Preenchimento verdeEscuro, e não verdePrimario: branco sobre verdePrimario dá 3,39:1, abaixo
  // do mínimo. Sobre verdeEscuro dá 6,2:1, e continua sendo o único status de fundo sólido.
  CONCLUIDA: { fundo: cores.verdeEscuro, texto: cores.branco },
  CANCELADA: { fundo: cores.papel, texto: textoAcessivel.suave },
  EXPIRADA: { fundo: cores.papel, texto: textoAcessivel.suave },
  EM_DISPUTA: { fundo: cores.coralClaro, texto: textoAcessivel.coral },
} as const;
