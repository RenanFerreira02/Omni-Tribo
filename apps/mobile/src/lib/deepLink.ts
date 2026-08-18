/**
 * Validação de deep link — entrada NÃO CONFIÁVEL, tratada como tal.
 *
 * **Este arquivo era uma promessa.** `app.config.ts` afirmava, num comentário, que ele existia e
 * que "valida esquema, host e formato antes de qualquer navegação". Não existia: o `scheme` estava
 * registrado, o Expo Router navegava sozinho para qualquer caminho recebido, e nada era validado. Um
 * comentário que descreve uma defesa inexistente é pior que a ausência dela, porque impede que
 * alguém procure.
 *
 * **O que um link malicioso conseguia.** `omnitribo://missao/<qualquer-coisa>` montava a tela de
 * detalhe e passava o segmento cru para `GET /missoes/{id}`, com o `Bearer` injetado pelo
 * interceptor. Um `id` contendo `/` ou `?` remonta a URL contra outro endpoint — não é execução de
 * código, mas é entrada externa reescrevendo a requisição de um cliente autenticado.
 *
 * A defesa tem três camadas, e nenhuma substitui as outras:
 *  1. aqui: allowlist de rota e formato de parâmetro, ANTES de navegar;
 *  2. `encodeURIComponent` em `src/api/missoes.ts`, para o caso de um id chegar por outro caminho;
 *  3. guarda de sessão no layout de `(app)`, para o link não montar tela autenticada sem token.
 */

/**
 * Rota interna que um link externo pode alcançar.
 *
 * Tipo literal, e não `string`: é o que faz o Expo Router aceitar o valor em `<Redirect href>` sem
 * asserção, e o que garante que só rota EXISTENTE saia daqui. Um `string` solto obrigaria um `as` na
 * hora de navegar — justamente onde a garantia importa.
 */
export type RotaDeepLink = `/missao/${string}` | '/beneficios';

/** Rotas que aceitamos abrir por link externo. Fechada de propósito. */
const ROTAS_PERMITIDAS = {
  missao: (parametro: string | undefined): RotaDeepLink | null =>
    parametro && ehUuid(parametro) ? `/missao/${parametro}` : null,
  beneficios: (): RotaDeepLink => '/beneficios',
} satisfies Record<string, (parametro: string | undefined) => RotaDeepLink | null>;

/**
 * Forma 8-4-4-4-12, sem checar versão.
 *
 * `z.guid()` e não `z.uuid()` pelo mesmo motivo documentado em `src/schemas/index.ts`: os
 * identificadores do seed têm nibble de versão zero — são legíveis de propósito — e um validador
 * estrito os reprovaria.
 */
function ehUuid(valor: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(valor);
}

/**
 * Caminho interno seguro para navegar, ou `null` quando o link não é aceitável.
 *
 * `null` é o desfecho de tudo o que não reconhecemos — esquema errado, rota fora da allowlist,
 * parâmetro malformado, lixo. O chamador leva o usuário para a tela inicial; abrir "quase certo"
 * é o que transformaria isto em teatro.
 *
 * @param url link recebido, no formato `omnitribo://missao/<uuid>`
 */
export function rotaSeguraDe(url: string | null | undefined): RotaDeepLink | null {
  if (!url) return null;

  let alvo: URL;
  try {
    alvo = new URL(url);
  } catch {
    // URL inválida não é caso excepcional: é o caminho mais provável de um link forjado.
    return null;
  }

  // `new URL('omnitribo://missao/abc').protocol` é `'omnitribo:'`, com os dois-pontos.
  if (alvo.protocol !== 'omnitribo:') return null;

  // Num esquema custom, `omnitribo://missao/<id>` põe "missao" no host e "/<id>" no pathname.
  // Concatenar os dois e reparticionar é o que faz a leitura funcionar igual com e sem `//`.
  const segmentos = `${alvo.host}${alvo.pathname}`.split('/').filter(Boolean);
  const [rota, parametro] = segmentos;

  if (!rota || !(rota in ROTAS_PERMITIDAS)) return null;
  // Um segmento a mais é link que não entendemos — recusar é mais barato que adivinhar.
  if (segmentos.length > 2) return null;

  return ROTAS_PERMITIDAS[rota as keyof typeof ROTAS_PERMITIDAS](parametro);
}
