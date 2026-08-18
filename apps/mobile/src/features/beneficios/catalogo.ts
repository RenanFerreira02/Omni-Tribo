/**
 * Catálogo de benefícios do bairro — **dado LOCAL, e isso é deliberado.**
 *
 * ## Por que não vem de API
 *
 * O resgate em benefício de parceiro é o **sumidouro do TOKEN** (ADR 0009 §3): é ele que fecha
 * `SUM(carteiras) + SUM(potes) + SUM(resgatado) == SUM(emitido)`. O ADR decide o sumidouro e **não
 * o atribui a nenhuma fase** — a F8 prevê o patrocinador como FONTE do pote, não como destino do
 * resgate. Hoje o backend não tem tabela de parceiro, endpoint de resgate, nem motivo `RESGATE` no
 * `lancamento.motivo` (são sete valores, e nenhum serve).
 *
 * Diante disso havia três caminhos: esconder o resgate, simular um débito no app, ou mostrar o
 * catálogo dizendo a verdade sobre ele. **Simular seria o pior**: o saldo debitado só no cliente é
 * desmentido pelo servidor no primeiro `refetch`, e um número que muda sozinho destrói a confiança
 * na carteira inteira. Esconder repetiria o problema que esta tela veio resolver — o app promete
 * resgate em três lugares (`SaldoToken`, `onboarding`, o card da carteira) e não oferecia porta.
 *
 * **Quando o backend existir**, este arquivo vira um `GET /beneficios` e some: o formato de
 * `Beneficio` já é o de uma resposta, `estadoDoResgate` continua puro e a tela não muda.
 *
 * ## A regra de copy que este arquivo carrega
 *
 * **`titulo` é um BEM ou uma PORCENTAGEM. Nunca um valor em reais.** Não é preciosismo: o ADR 0009
 * §6 diz que, se o token virasse conversível, ele *seria* dinheiro — com KYC e enquadramento
 * regulatório junto. "R$ 20 em compras" fixa uma cotação token→real exatamente onde o produto
 * recusa ter uma, e é a mesma razão pela qual a carteira nunca imprime `R$`. Há teste sobre isso.
 *
 * Os parceiros são de Cidade Líder, o bairro do seed de demonstração (`V903__seed_cidade_lider`),
 * para a tela conversar com o resto dos dados na apresentação.
 */

export type TipoParceiro = 'MERCADO' | 'FEIRA' | 'SERVICO';

export interface Beneficio {
  id: string;
  parceiro: string;
  /** Bem ou porcentagem de desconto — NUNCA valor em reais. Ver o cabeçalho deste arquivo. */
  titulo: string;
  descricao: string;
  custoTokens: number;
  tipo: TipoParceiro;
}

export const BENEFICIOS: readonly Beneficio[] = [
  {
    id: 'padaria-cafe-dois',
    parceiro: 'Padaria Pão Nosso',
    titulo: 'Café da manhã para duas pessoas',
    descricao: 'Dois cafés, dois pães na chapa e um suco. Válido de segunda a sexta, até as 10h.',
    custoTokens: 40,
    tipo: 'MERCADO',
  },
  {
    id: 'hortifruti-cesta-semana',
    parceiro: 'Hortifruti Bem Viver',
    titulo: 'Cesta de verduras da semana',
    descricao: 'Montada com o que chegou da roça no dia. Retire às quartas ou aos sábados.',
    custoTokens: 45,
    tipo: 'FEIRA',
  },
  {
    id: 'feira-sacola',
    parceiro: 'Feira da Cidade Líder',
    titulo: 'Sacola de feira',
    descricao: 'Uma sacola cheia, à escolha do feirante parceiro. Só na feira de domingo.',
    custoTokens: 80,
    tipo: 'FEIRA',
  },
  {
    id: 'mercado-dez-porcento',
    parceiro: 'Mercado do Zé',
    titulo: '10% de desconto na compra do mês',
    descricao: 'Aplicado no caixa, uma vez por mês, em compras acima de dez itens.',
    custoTokens: 120,
    tipo: 'MERCADO',
  },
  {
    id: 'bicicletaria-revisao',
    parceiro: 'Bicicletaria do Léo',
    titulo: 'Revisão completa da bicicleta',
    descricao: 'Freios, câmbio, corrente e calibragem. Quem entrega de bike anda mais seguro.',
    custoTokens: 150,
    tipo: 'SERVICO',
  },
  {
    id: 'salao-corte',
    parceiro: 'Salão da Esquina',
    titulo: 'Corte de cabelo',
    descricao: 'Com hora marcada, de terça a sexta. Combine pelo balcão.',
    custoTokens: 200,
    tipo: 'SERVICO',
  },
];

export interface EstadoResgate {
  /** O saldo já cobre o custo. */
  alcanca: boolean;
  /** Quantos tokens faltam. Zero quando já alcança — nunca negativo. */
  faltam: number;
}

/**
 * Quanto falta para um benefício, dado o saldo.
 *
 * Função **pura**, no molde de `acoesDisponiveis` (`src/features/missoes/acoes.ts`): a regra fica
 * testável sem montar tela, e a tela não decide nada sozinha. O `Math.max` existe porque "faltam
 * −76 tokens" é a forma mais rápida de transformar boa notícia em texto sem sentido.
 */
export function estadoDoResgate(saldoTokens: number, custoTokens: number): EstadoResgate {
  const faltam = Math.max(custoTokens - saldoTokens, 0);
  return { alcanca: faltam === 0, faltam };
}
