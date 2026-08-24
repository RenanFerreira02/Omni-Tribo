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
  /**
   * MEIO-PASSO, e o único. Existe para um caso só: par de textos que forma um bloco — título e
   * legenda, nome e handle, descrição e sensação térmica. Ali 4 já separa demais e o par deixa de
   * ser lido como uma coisa.
   *
   * <b>Não é permissão para inventar outros.</b> É o único valor da escala que não é múltiplo de 4,
   * e está aqui declarado em vez de aparecer como `gap: 2` solto em três telas, que era o estado
   * anterior.
   */
  xxs: 2,
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 24,
  xxl: 32,
} as const;

/**
 * ALVO DE TOQUE — e a razão de ser escala PRÓPRIA, e não parte de `espaco`.
 *
 * Estes números não são ritmo visual: são o mínimo da WCAG 2.5.5. Misturá-los com espaçamento
 * convida a próxima pessoa a "arredondar 44 para 48" ou "44 para 40" como se fosse escolha de
 * respiro — e 40 reprova a norma. Separando, mexer aqui exige saber o que se está mexendo.
 *
 * `confortavel` é o padrão dos controles do app; `minimo` é o piso, usado onde o desenho pede um
 * alvo visualmente menor e a área sensível cresce por `hitSlop` ou `padding`.
 */
export const alvo = {
  minimo: 44,
  confortavel: 48,
} as const;

/**
 * Fio de 1px: divisor, borda de cartão, trilho.
 *
 * Fora da escala de espaçamento de propósito — 1 nunca vai ser múltiplo de 4, e forçá-lo a 4
 * transformaria um fio numa faixa. É primitivo de traço, não de respiro.
 */
export const traco = 1;

/**
 * Glifos DECORATIVOS — fora da rampa de texto.
 *
 * Não são texto: não têm peso, não têm entrelinha, e são sempre marcados com
 * `importantForAccessibility="no"` porque quem os lê em voz alta atrapalha. Ficavam dentro de
 * `tipografia`, o que fazia a rampa parecer ter um degrau a mais do que tem.
 */
export const glifo = {
  /** Ícone da barra de abas. */
  aba: 20,
  /** Emoji dos slides do onboarding — ilustração, não tipografia. */
  ilustracao: 64,
} as const;

export const raio = {
  sm: 6,
  md: 10,
  lg: 16,
  pilula: 999,
} as const;

/**
 * A RAMPA. Seis tamanhos, três pesos, e nada fora dela.
 *
 * <b>Por que seis e não sete.</b> Havia `display` a 34 e `destaque` a 32 — dois "números grandes" a
 * dois pixels de distância, que é o sintoma exato de tamanho decidido tela a tela em vez de
 * desenhado. `destaque` tinha UM uso em todo o app. Fundidos, sobra um degrau para "número que se
 * lê de longe", que é o papel que os dois disputavam.
 *
 * <b>A entrelinha aperta conforme o texto cresce, e isso é regra, não gosto.</b> Título é uma linha
 * só e ganha densidade; corpo é parágrafo e precisa de ar para o olho achar a linha seguinte. As
 * razões, do mais apertado ao mais folgado:
 *
 * <pre>
 *   display    34/40 = 1,18   ← título gigante, quase sempre uma linha
 *   titulo     22/28 = 1,27
 *   legenda    12/16 = 1,33
 *   rotulo     13/18 = 1,38
 *   subtitulo  17/24 = 1,41
 *   corpo      15/22 = 1,47   ← parágrafo, o mais folgado
 * </pre>
 *
 * <b>Os NÚMEROS não mudaram nesta migração</b>, só a fusão de 34/32 e a saída dos glifos. É
 * deliberado: a F19 é substituição de valor, não redesenho, e mexer em entrelinha reflui toda tela
 * do app de uma vez.
 *
 * Três pesos: 700 para o que titula, 600 para o que rotula, 400 para o que se lê.
 */
export const tipografia = {
  /**
   * O número que se lê de longe: saldo da carteira, custo evitado no painel, marca do login.
   *
   * Absorveu o antigo `destaque` (32). Ver o javadoc acima: dois degraus a 2px um do outro não são
   * dois degraus, são uma decisão que ninguém tomou.
   */
  display: { fontSize: 34, lineHeight: 40, fontWeight: '700' },
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
 * GLIFO por categoria — o segundo canal, ao lado da cor.
 *
 * <b>Por que existe.</b> As quatro categorias se distinguiam por matiz, e o javadoc de
 * `coresCategoria` registra que a F12 quase apagou essa distinção: ao escurecer o texto de ENTREGA
 * para atingir contraste, ENTREGA e TRIBO ficariam idênticas. A correção de então foi inverter
 * TRIBO para fundo escuro — resolveu, e manteve a cor como único canal.
 *
 * Com o glifo, a categoria continua legível para daltonismo, sob luz forte e em impressão em preto
 * e branco. E resolve de graça o mapa, onde os quatro pinos eram indistinguíveis entre si por não
 * haver texto ao lado.
 *
 * <b>Formas, e não emoji.</b> Emoji varia de desenho entre aparelhos, pesa no bundle quando
 * embarcado e é lido em voz alta pelo leitor de tela com um nome que ninguém escolheu. Estas quatro
 * formas geométricas já existem em qualquer fonte de sistema — é a mesma decisão dos glifos das
 * abas e do ★/☆ das conquistas.
 *
 * O glifo é DECORATIVO: quem o renderiza precisa escondê-lo da árvore de acessibilidade
 * (`importantForAccessibility="no"`), senão o leitor de tela passa a anunciar "losango, Entrega".
 * O `Chip` já faz isso.
 */
export const glifoCategoria = {
  ENTREGA: '\u25C6',
  COLETA: '\u25CF',
  TRIBO: '\u25B2',
  AJUDA: '\u25A0',
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
