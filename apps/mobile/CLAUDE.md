# Mobile

## Estrutura

- app/ só rotas do Expo Router. Tela é composição, sem lógica de negócio.
- src/features/<dominio>/ hooks de TanStack Query e lógica. src/api/ é o único lugar que fala HTTP.
- src/components/ design system, sem chamada de API. src/stores/ Zustand só para UI e sessão.
- src/theme/tokens.ts — NENHUM hex literal fora daqui.
- TypeScript strict. `any` só com comentário justificando.
- Toda chamada de API tem estado de carregando, vazio e erro tratados na UI.
- npx expo install, nunca npm install, para pacotes do ecossistema Expo.
- Antes de terminar: npm run typecheck && npm run lint && npm test, e cole a saída.

## Economia — o que a UI precisa saber (ADR 0009)

**Quem cria a missão NÃO paga.** A recompensa é XP + TOKEN, dimensionada por complexidade e tipo, e
**calculada pelo servidor** — o app nunca envia nem recalcula valor de recompensa. Se precisar
mostrar o valor antes de criar, use o endpoint de prévia; duplicar a fórmula no cliente reabre por
outro caminho a divergência que o ADR fechou.

**TOKEN é a moeda principal da tela de carteira.** É o que o usuário ganha e o que resgata em
benefício de parceiro do bairro.

**`saldoBrl` é sempre `0` e não se movimenta.** A coluna existe como infraestrutura de uma conversão
patrocinada futura; nenhuma missão remunera em BRL. Não construa UI que sugira o contrário.

**`POST /carteira/saques` responde 422**, com `type` `.../saque-desabilitado` (ADR 0010). O saque
está desligado por configuração (`app.carteira.saque-habilitado`), não quebrado. A tela precisa de
estado e mensagem próprios — o `detail` da resposta já explica ao usuário o que ele PODE fazer.

## Tratamento de erro — regra dura

**Discrimine erro pelo campo `type` do ProblemDetail. NUNCA pelo `detail`.**

`detail` é texto em português voltado a humano e muda a cada revisão de copy — um `if` sobre ele
quebra silenciosamente. `status` sozinho é ambíguo: dois 409 diferentes pedem reações diferentes
(transição inválida → recarregue a tela; colisão de versão → tente de novo).

O catálogo de URIs estáveis está em `compartilhado/api/TipoProblema` no backend. Toda resposta de
erro carrega `type`, inclusive as que nascem na cadeia de filtros (401, 429) — nenhuma sai como
`about:blank`.

A granularidade é **uma URI por REAÇÃO DE UI** (ADR 0010): ganha `type` próprio a causa que faz a
tela agir diferente, não toda causa distinta. Por isso `regra-negocio-violada` continua sendo o 422
padrão — saldo, pote, janela, todos só exibem o `detail` —, enquanto têm URI própria
`saque-desabilitado` e as três rejeições de check-in (`checkin-localizacao-simulada`,
`checkin-acuracia-insuficiente`, `checkin-fora-do-raio`), que pedem instruções mutuamente inúteis
entre si. O espelho no app é `src/api/erros.ts`, e a tradução em orientação está em
`src/features/missoes/mensagensCheckin.ts`.

Precisa de uma URI nova? Peça no backend — subclasse de `DominioException` sobrescrevendo
`getTipo()`. Nunca parseie `detail`.

Campos garantidos em toda resposta de erro: `type`, `title`, `status`, `detail`, `instance` e
`traceId`. Erros de validação trazem também `errors[{campo, mensagem}]`, prontos para marcar o campo
no formulário.

## Ambiente de teste — três armadilhas

Custaram tempo e não aparecem em lugar nenhum da documentação do Expo:

- **jest-expo 57 fixa o ecossistema jest 29.** Instalar o `jest` 30 (que é o `latest` do npm) mistura
  `jest-runtime` 30 com `jest-environment-node` 29 e a suíte morre em
  `this._moduleMocker.clearMocksOnScope is not a function` — erro que não menciona versão nenhuma.
- **RNTL 14 tornou `render` e `fireEvent` ASSÍNCRONOS.** Sem `await`, `screen` fica vazio e todo
  `getByTestId` estoura com "`render` function has not been called".
- **O ambiente do jest-expo não faz rede de verdade** — o `XMLHttpRequest` e o `fetch` dele são
  dublês. Por isso o teste de integração roda em `testEnvironment: 'node'`
  (`jest.e2e.config.js`), com stub de `react-native`; sob o preset do RN toda chamada volta como
  `semRede`, indistinguível de backend desligado.
