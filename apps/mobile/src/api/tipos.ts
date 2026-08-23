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

export type ComplexidadeMissao = 'LEVE' | 'MEDIA' | 'PESADA';

/**
 * Faixa de risco de falha da entrega, estimada pelo modelo do servidor.
 *
 * Três faixas e não a probabilidade crua: "62%" convida a uma precisão que o modelo não tem — ele
 * foi treinado em dados sintéticos (ver docs/qualidade/modelo-previsao.md) e a incerteza da
 * estimativa é da ordem de pontos percentuais. A faixa comunica a ordem de grandeza sem prometer
 * exatidão que não existe.
 */
export type FaixaRisco = 'BAIXO' | 'MEDIO' | 'ALTO';

export type PapelUsuario = 'USUARIO' | 'ADMIN';

export type SinalLancamento = 'CREDITO' | 'DEBITO';

export type MotivoLancamento =
  | 'RECOMPENSA_MISSAO'
  | 'TRANSFERENCIA_ENVIADA'
  | 'TRANSFERENCIA_RECEBIDA'
  | 'FINANCIAMENTO_TRIBO'
  /** Débito do patrocinador ao financiar o pote de uma missão de retirada (V23). */
  | 'FINANCIAMENTO_PATROCINADOR'
  /** O ÚNICO motivo que EMITE token. Só aparece no extrato de um patrocinador (V23). */
  | 'APORTE_PATROCINADOR'
  /** O ÚNICO motivo que QUEIMA token: resgate de benefício (V26, ADR 0027). */
  | 'RESGATE'
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

export interface TriboResponse {
  id: string;
  nome: string;
  bairro: string;
  /** Derivado por PostGIS das missões e pontos da tribo. Nulo quando ela não tem nenhum. */
  centroLat: number | null;
  centroLon: number | null;
}

export interface ConquistaResponse {
  codigo: string;
  titulo: string;
  descricao: string;
  conquistada: boolean;
  /** Já saturado na meta pelo servidor — nunca vem maior que ela. */
  progresso: number;
  meta: number;
}

/**
 * Perfil completo. Endpoint SEPARADO de `/auth/me`, que continua sendo a checagem barata de
 * identidade do boot, resolvida só dos claims do JWT.
 */
export interface PerfilResponse {
  id: string;
  nome: string;
  email: string;
  handle: string;
  papel: PapelUsuario;
  tribo: TriboResponse | null;
  xp: number;
  /** DERIVADO do XP pela fórmula do servidor, não a coluna cache `usuario.nivel`. */
  nivel: number;
  xpNivelAtual: number;
  xpProximoNivel: number;
  streak: number;
  conquistas: ConquistaResponse[];
}

export type TipoConsentimento = 'LOCALIZACAO' | 'NOTIFICACAO' | 'TERMOS';

export interface ConsentimentoResponse {
  tipo: TipoConsentimento;
  concedido: boolean;
  versaoTexto: string | null;
  /** Nulo quando o titular nunca decidiu este tipo. */
  registradoEm: string | null;
}

export interface AlertaResponse {
  id: string;
  /** Discriminador estável. O app ramifica por ele; título e corpo são copy. */
  tipo: string;
  titulo: string;
  corpo: string;
  missaoId: string | null;
  lido: boolean;
  criadoEm: string;
}

export interface PontoCustodiaResponse {
  id: string;
  codigo: string;
  tipo: 'LOJA' | 'LOCKER' | 'PORTARIA' | 'VIZINHO';
  apelido: string;
  lat: number;
  lon: number;
  capacidade: number;
  ocupacao: number;
  /** Só na busca por raio; nulo no detalhe por id. */
  distanciaM: number | null;
}

export interface ClimaResponse {
  temperaturaC: number;
  sensacaoC: number;
  codigo: number;
  descricao: string;
  medidoEm: string | null;
}

export interface EnderecoResponse {
  cep: string;
  logradouro: string;
  bairro: string;
  cidade: string;
  uf: string;
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

  /**
   * `cep` e `logradouro` são NULOS para quem não participa da missão.
   *
   * O servidor recorta por participação: criador e executor recebem o endereço completo e a
   * coordenada com 6 casas; qualquer outro usuário recebe `null` nestes dois campos e coordenada
   * com **3 casas (~110 m)**. A listagem devolvia endereço exato de toda missão do sistema a
   * qualquer autenticado, o que era um catálogo de endereços do bairro.
   *
   * Consequência para a UI: sempre trate como ausente e explique por quê — aceitar a missão é o
   * que revela o endereço. Nunca renderize direto: `<Text>{null}</Text>` não quebra, só deixa uma
   * linha vazia que ninguém entende.
   */
  cep: string | null;
  logradouro: string | null;

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

  /**
   * Complexidade EFETIVA e versão da fórmula, CONGELADAS na criação.
   *
   * Derivadas de peso e volume quando existem, declaradas quando não. É o par que responde "por que
   * esta missão vale isto?" — e `versaoFormula` é o que diz sob qual calibração a recompensa foi
   * congelada, já que os números do servidor são ajustáveis. `PreviaRecompensaResponse` já os
   * trazia; a missão criada não, e por isso o app não conseguia explicar a própria recompensa.
   */
  complexidade: ComplexidadeMissao;
  versaoFormula: number;

  /**
   * Nível mínimo para ACEITAR. 1 = sem restrição, que é o caso de toda missão criada por usuário.
   *
   * Maior que 1 só em missão gerada a partir de entrega falida: custódia de encomenda de terceiro é
   * restrita a quem tem reputação consolidada (Regra de Elegibilidade por Reputação do challenge).
   *
   * A UI usa isto para DESABILITAR o botão com a explicação certa em vez de deixar a pessoa tocar e
   * levar 422. A checagem do cliente é conveniência; a do servidor é a regra, e continua lá.
   */
  nivelMinimo: number;

  /**
   * Risco de falha avaliado na criação, CONGELADO junto com `versaoFormula`.
   *
   * `null` em toda missão que não veio do webhook de entrega falida — que é a MAIORIA. Trate
   * ausência como "sem avaliação", nunca como risco baixo: são coisas diferentes, e mostrar
   * "risco baixo" para uma missão que ninguém avaliou seria inventar uma garantia.
   *
   * `multiplicadorRisco` é o que EXPLICA a recompensa: sem ele, duas entregas de mesmo peso e
   * distância pagariam valores diferentes sem justificativa visível.
   */
  multiplicadorRisco: number | null;
  faixaRisco: FaixaRisco | null;

  /**
   * Texto pronto do aviso, montado no SERVIDOR, ou `null` quando não há o que avisar.
   *
   * Vem pronto de propósito: se o app compusesse a frase a partir da faixa, cada versão instalada
   * teria a sua, e mudar a orientação exigiria publicar na loja. Só ALTO e MEDIO produzem texto —
   * um aviso que aparece sempre deixa de ser lido.
   *
   * Nunca contém logradouro nem CEP. A resposta da missão recorta endereço a bairro para quem não
   * participa, e um aviso citando a rua devolveria pela porta de trás o que aquele recorte protege.
   */
  avisoRisco: string | null;

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
  /**
   * Sempre `1.00` nesta rota: a prévia serve missão criada por usuário, que não passa por avaliação
   * de risco. Vem mesmo assim para deixar explícito no contrato que o fator existe na fórmula e que
   * aqui ele não está agindo.
   */
  multiplicadorRisco: number;
}

/**
 * Corpo de `POST /missoes` e de `POST /missoes/previa-recompensa` — o backend usa o MESMO record
 * para os dois.
 *
 * **Não existe `xpRecompensa` nem `tokensRecompensa` aqui, e a ausência é o contrato.** A recompensa
 * é calculada pelo servidor e congelada na criação (ADR 0009); mandar um valor seria silenciosamente
 * ignorado (`fail-on-unknown-properties: false`), o que é pior que um erro — o criador veria um
 * número na tela e outro na missão publicada.
 *
 * `valorBrl` é obrigatório e tem de ser `0`: o campo é `@NotNull` no servidor e qualquer valor
 * maior é recusado com 400 por `CriacaoMissaoVerificador`.
 *
 * `complexidade` só vai quando NÃO há peso e volume. Com os dois presentes o servidor deriva, e
 * mandar junto é 400 — recusa, não "ignora".
 */
export interface CriarMissaoRequest {
  categoria: CategoriaMissao;
  titulo: string;
  descricao: string;
  valorBrl: 0;
  complexidade?: ComplexidadeMissao;
  origemLat: number;
  origemLon: number;
  destinoLat?: number;
  destinoLon?: number;
  cep: string;
  logradouro: string;
  bairro: string;
  cidade: string;
  uf: string;
  raioCheckinM: number;
  pesoKg?: number;
  volumeL?: number;
  janelaInicio: string;
  janelaFim: string;
  pontoCustodiaId?: string;
}

/**
 * O vizinho encontrado pela busca por `@`.
 *
 * Quatro campos e nada mais — o servidor não devolve e-mail, XP nem saldo. Existem para a pessoa
 * CONFERIR que acertou o destinatário antes de uma transferência que não tem volta.
 */
export interface UsuarioBuscaResponse {
  id: string;
  handle: string;
  nome: string;
  tribo: string | null;
}

export interface TransferenciaResponse {
  lancamentoSaidaId: string;
  /** Nulo num replay de idempotência. */
  lancamentoEntradaId: string | null;
  saldoTokensRemetente: number;
  replay: boolean;
}

/**
 * Um item do catálogo de benefícios — o que o TOKEN compra.
 *
 * `tipo` é BEM ou PERCENTUAL e NUNCA um valor em reais: preço em moeda corrente publicaria uma
 * cotação token→real implícita, que o ADR 0009 §6 recusa ter. O servidor garante isso em duas
 * camadas (validação na borda e `ck_beneficio_sem_reais`), então o app não precisa filtrar.
 */
export interface BeneficioResponse {
  id: string;
  titulo: string;
  descricao: string;
  custoTokens: number;
  tipo: 'BEM' | 'PERCENTUAL';
  parceiroId: string;
  parceiroNome: string;
  bairro: string;
  /** Metros até o parceiro, derivados pelo PostGIS. Ausente no recorte por tribo. */
  distanciaM?: number | null;
}

/**
 * O comprovante de um resgate.
 *
 * `codigoRetirada` NÃO é credencial: são 8 caracteres para o humano do balcão casar o papel com a
 * linha na tela do parceiro. Quem autoriza a baixa é um ADMIN, pelo id.
 */
export interface ResgateResponse {
  id: string;
  beneficioId: string;
  custoTokens: number;
  codigoRetirada: string;
  status: 'PENDENTE' | 'UTILIZADO';
  criadoEm: string;
  utilizadoEm: string | null;
  saldoTokensRestante: number;
  /** `true` quando a chave de idempotência já existia e NADA foi queimado nesta chamada. */
  replay: boolean;
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
  /**
   * Allowlist de ordenação do backend. `VALOR_BRL` SAIU e deu lugar a `TOKENS_RECOMPENSA`.
   *
   * Ordenar por `valorBrl` era ordenar por constante: a `ck_missao_economia` (V15) obriga a coluna a
   * ser ZERO em toda linha. O backend removeu a opção do enum, então enviá-la agora é 400 — e o
   * autocomplete daqui entregava exatamente esse valor.
   */
  ordenarPor?: 'CRIADA_EM' | 'JANELA_FIM' | 'TOKENS_RECOMPENSA' | 'XP_RECOMPENSA';
  direcao?: 'ASC' | 'DESC';
}

export interface FiltroProximas {
  lat: number;
  lon: number;
  raioMetros?: number;
  categoria?: CategoriaMissao;
  limite?: number;
}

/**
 * O painel de impacto — `GET /api/v1/admin/impacto`, só ADMIN.
 *
 * A única resposta do app que fala de VALOR e não de estado: quanto a tese economizou. Tudo aqui é
 * agregado pelo servidor a cada chamada, sobre tabelas que já existem — não há tabela de agregação
 * nem cache, então dois pedidos seguidos podem legitimamente diferir.
 */
export interface ImpactoResponse {
  /** Instante da apuração. Exibido porque o número é volátil e um painel sem data é uma afirmação sem validade. */
  geradoEm: string;
  entregasFalidas: ImpactoEntregasFalidas;
  missoesDeRetirada: ImpactoMissoesDeRetirada;
  custoEvitado: ImpactoCustoEvitado;
  tokens: ImpactoTokens;
}

export interface ImpactoEntregasFalidas {
  recebidas: number;
  convertidas: number;
  /**
   * Recebidas que não viraram missão e não foram recusadas: encomenda parada na custódia.
   *
   * É o número que EXPLICA uma taxa de conversão baixa. Sem ele na tela, quem lê conclui que o
   * bairro não responde — quando a maioria das linhas nunca chegou a ser oferecida a ninguém.
   */
  pendentes: number;
  recusadasPontoLotado: number;
  recusadasSemPatrocinio: number;
  /** Fração 0..1, ou `null` quando nada foi recebido. NUNCA renderize `null` como 0%. */
  taxaConversao: number | null;
}

export interface ImpactoMissoesDeRetirada {
  criadas: number;
  concluidas: number;
  taxaConclusao: number | null;
  /** Segundos entre o webhook e o primeiro check-in válido. `null` com amostra vazia. */
  medianaAteCheckinSegundos: number | null;
  /** Quantas missões entraram na mediana. Vai para a tela: mediana sem amostra não é interpretável. */
  amostraMediana: number;
}

/**
 * A conta que um parceiro compraria — e a premissa que a sustenta, ao lado dela.
 *
 * `reentregasEvitadas` é o MESMO número que `missoesDeRetirada.concluidas`, renomeado. Não são duas
 * evidências: é a interpretação de que a encomenda teria sido re-entregue. A tela diz isso.
 */
export interface ImpactoCustoEvitado {
  reentregasEvitadas: number;
  /**
   * Premissa vigente em BRL, de `app.impacto.custo-reentrega-brl`.
   *
   * `number`, como `valorBrl` e `saldoBrl`: são `BigDecimal` no servidor e Jackson os serializa
   * como NÚMERO JSON (`25.00`), não como string. O cliente só FORMATA — toda aritmética de dinheiro
   * acontece no servidor, em `BigDecimal`, e nenhuma conta é refeita aqui.
   */
  premissaCustoReentregaBrl: number;
  baseBrl: number;
  /** Premissa pela metade. */
  menos50Brl: number;
  /** Premissa uma vez e meia. */
  mais50Brl: number;
}

export interface ImpactoTokens {
  aportados: number;
  emCarteiras: number;
  emPotes: number;
  /** `emCarteiras + emPotes` — a conservação do ADR 0027 exibida como número. */
  emCirculacao: number;
  resgatados: number;
}
