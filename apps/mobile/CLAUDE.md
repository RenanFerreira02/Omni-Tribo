# Mobile

## Estrutura

- app/ só rotas do Expo Router. Tela é composição, sem lógica de negócio.
- src/features/<dominio>/ hooks de TanStack Query e lógica. src/api/ é o único lugar que fala HTTP.
- src/components/ design system, sem chamada de API. src/stores/ Zustand só para UI e sessão.
- src/theme/tokens.ts — NENHUM hex literal fora daqui. A regra é aplicada por lint
  (`no-restricted-syntax` em eslint.config.js), não por disciplina.
- **Preenchimento usa `cores`; TEXTO usa `textoAcessivel`.** Os 12 tokens de marca foram desenhados
  para preencher, e como texto reprovavam em WCAG AA — `tinta50` dava 3,54:1, `ambar` 3,36:1,
  `coral` 3,20:1, contra o mínimo de 4,5:1. Onze dos vinte e dois pares texto/fundo do app
  reprovavam. `textoAcessivel` traz as mesmas cores escurecidas até o limiar, preservando o matiz.
  Verde como texto é `verdeEscuro`, que já atendia. Um `color: cores.ambar` novo é regressão.
- **`useLocalizacao()` NÃO pede permissão ao montar, e o default é esse de propósito.** Quem quiser
  o pedido automático passa `true` explicitamente — e precisa ter mostrado a justificativa antes.
  Use `JustificativaLocalizacao`: o diálogo do sistema é de uma via só, e negado uma vez não volta.
  O default era `true` e produziu o defeito de a aba de missões gastar o prompt sem explicar nada,
  enquanto o card do mapa chegava tarde.
- **Segredo passa SEMPRE por `src/lib/armazenamentoSeguro.ts`, nunca por `expo-secure-store`
  direto.** A lib não tem implementação web — o módulo resolvido no bundle do browser é
  literalmente `export default {}` e toda chamada estoura com `... is not a function`, no boot,
  antes da primeira tela. Ver a seção Plataforma web abaixo e o ADR 0013.
- TypeScript strict. `any` só com comentário justificando.
- Toda chamada de API tem estado de carregando, vazio e erro tratados na UI.
- npx expo install, nunca npm install, para pacotes do ecossistema Expo.
- Antes de terminar: npm run typecheck && npm run lint && npm test, e cole a saída.

## Plataforma web

`npm run web` funciona e é o caminho de demonstração que não depende de emulador nem de aparelho.
Duas coisas a saber antes de reportar bug:

- **Recarregar a página desloga, e isso é a decisão, não o defeito.** O browser não tem keystore, e
  as alternativas (`localStorage`, `sessionStorage`) gravariam em claro um refresh token que vale 30
  dias de sessão. Na web o cofre é um `Map` em memória que morre com a aba. Mesma coisa com a flag
  de onboarding, que reaparece a cada reload. Ver **ADR 0013**.
- **"Funciona na web" não prova que funciona no aparelho** para nada que envolva sessão persistida.
  O caminho nativo é o alvo real; teste no Expo Go antes de fechar qualquer coisa de auth.

## Economia — o que a UI precisa saber (ADR 0009)

**Quem cria a missão NÃO paga.** A recompensa é XP + TOKEN, dimensionada por complexidade e tipo, e
**calculada pelo servidor** — o app nunca envia nem recalcula valor de recompensa. Se precisar
mostrar o valor antes de criar, use o endpoint de prévia; duplicar a fórmula no cliente reabre por
outro caminho a divergência que o ADR fechou.

**TOKEN é a moeda principal da tela de carteira.** É o que o usuário ganha e o que resgata em
benefício de parceiro do bairro.

**`saldoBrl` é sempre `0` e não se movimenta.** A coluna existe como infraestrutura de uma conversão
patrocinada futura; nenhuma missão remunera em BRL. Não construa UI que sugira o contrário.

**A UI não oferece saque, e o endpoint continua existindo.** `POST /carteira/saques` responde 422 com
`type` `.../saque-desabilitado` (ADR 0010) — desligado por configuração, não quebrado —, e a camada
de API mantém `sacar()`, o mapeamento do `type` e o teste do 422. O que saiu foi o botão: a carteira
agora leva a `app/beneficios.tsx`. O raciocínio antigo ("um botão ausente não ensina nada") valia
enquanto não havia para onde mandar a pessoa; um catálogo mostra o que a moeda É, e isso ensina mais
que um aviso dizendo o que ela não é.

**Benefício se expressa em BEM ou em PORCENTAGEM. Nunca em reais.** O ADR 0009 §6 é a razão: se o
token virasse conversível, ele *seria* dinheiro, com KYC e enquadramento regulatório junto. "R$ 20
em compras" fixa uma cotação token→real exatamente onde o produto recusa ter uma — é a mesma regra
que faz a carteira nunca imprimir `R$`. Há teste dos dois lados: o catálogo
(`features/beneficios/__tests__/catalogo.test.ts`) e a tela (`app/__tests__/beneficios.test.tsx`).

**O catálogo de benefícios é dado LOCAL**, em `src/features/beneficios/catalogo.ts`, e nada nele
debita saldo. O resgate é o sumidouro do TOKEN (ADR 0009 §3) e o backend não o tem: não há tabela de
parceiro, endpoint, nem motivo `RESGATE` no ledger. Simular o débito no cliente produziria um saldo
que o servidor desmente no primeiro `refetch` — a tela diz ao usuário que a baixa ainda não acontece.

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
