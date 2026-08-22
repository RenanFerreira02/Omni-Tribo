/**
 * Regras puras do resgate. **O catálogo em si vem da API** — ver `src/api/beneficios.ts`.
 *
 * ## O que este arquivo era, e por que encolheu
 *
 * Até a F16 ele carregava uma lista `BENEFICIOS` hardcoded, porque o resgate era o sumidouro que o
 * ADR 0009 §3 decidia e nenhuma fase implementava: não havia tabela de parceiro, endpoint de resgate
 * nem motivo `RESGATE` no ledger. O cabeçalho antigo prometia: *"quando o backend existir, este
 * arquivo vira um GET /beneficios e some"*. O backend existe (V24-V26, ADR 0027), e a lista saiu.
 *
 * Junto saiu o teste que a percorria procurando "R$". A regra continua valendo — benefício se
 * expressa em BEM ou PERCENTUAL, nunca em reais, porque preço em moeda corrente publica uma cotação
 * token→real que o ADR 0009 §6 recusa — mas agora ela é garantida **no servidor**, em duas camadas:
 * validação na borda de `CadastrarBeneficioRequest` e `ck_beneficio_sem_reais` no banco. Um teste do
 * app sobre uma lista que o app não usa mais provaria apenas que a fixture está bem escrita.
 *
 * ## O que ficou
 *
 * `estadoDoResgate` é o que permite à tela dizer **quanto falta** sem parsear `detail`. O backend
 * responde saldo insuficiente com `422 regra-negocio-violada`, que não traz campos estruturados; a
 * frase é montada aqui, com dois números que o app já tem em mãos. Ver a regra dura em
 * `src/api/erros.ts`.
 */

export interface EstadoResgate {
  alcanca: boolean;
  /** Quantos tokens faltam. Zero quando o saldo já cobre. */
  faltam: number;
}

export function estadoDoResgate(saldoTokens: number, custoTokens: number): EstadoResgate {
  const faltam = Math.max(custoTokens - saldoTokens, 0);
  return { alcanca: faltam === 0, faltam };
}
