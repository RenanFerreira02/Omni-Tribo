# Mobile

## Estrutura

- app/ só rotas do Expo Router. Tela é composição, sem lógica de negócio.
- src/features/<dominio>/ hooks de TanStack Query e lógica. src/api/ é o único lugar que fala HTTP.
- src/components/ design system, sem chamada de API. src/stores/ Zustand só para UI e sessão.
- src/theme/tokens.ts — NENHUM hex literal fora daqui. A regra é aplicada por lint
  (`no-restricted-syntax` em eslint.config.js), não por disciplina. **A segunda metade dela também é
  lint agora**: `cores.{ambar,coral,tinta50,verdePrimario}` numa propriedade `color` é erro. Só o hex
  era travado, e as duas violações que existiam estavam justamente na metade descoberta — uma delas
  no `Aviso`, por onde passa TODO erro do app.
- **Preenchimento usa `cores`; TEXTO usa `textoAcessivel`.** Os 12 tokens de marca foram desenhados
  para preencher, e como texto reprovavam em WCAG AA — `tinta50` dava 3,54:1, `ambar` 3,36:1,
  `coral` 3,20:1, contra o mínimo de 4,5:1. Onze dos vinte e dois pares texto/fundo do app
  reprovavam. `textoAcessivel` traz as mesmas cores escurecidas até o limiar, preservando o matiz.
  Verde como texto é `verdeEscuro`, que já atendia. Um `color: cores.ambar` novo é regressão.
- **Acessibilidade é LINT, não boa vontade** (F18). `eslint-plugin-react-native-a11y` roda no
  `npm run lint`, que o CI do mobile já executa. Onze regras ligadas uma a uma em `eslint.config.js`,
  com o comentário de cada decisão. **Nenhuma exceção inline sem justificativa** — e hoje não há
  nenhuma: o único falso positivo (o fundo da `FolhaInferior`) foi resolvido declarando
  `accessibilityRole="none"`, que é a verdade sobre aquele elemento, em vez de silenciar a regra.
  - O gate real é **`has-valid-accessibility-descriptors`**: todo `Pressable` e todo `TextInput`
    precisa de papel, rótulo ou ação. **`has-accessibility-props`, apesar do nome, é INERTE aqui** —
    ela só dispara sobre `accessibilityTraits`/`accessibilityComponentType`, depreciadas e ausentes
    do app. Fica ligada como trava de regressão; não conte com ela para cobrar anotação nova.
  - `has-accessibility-hint` NÃO é ligada: exige dica sempre que existe rótulo, e dica em "Voltar"
    ou no número do saldo é ruído. **Hint é para quando a consequência não é óbvia** — check-in,
    transferência, exclusão de conta —, e isso é julgamento, não regra.
  - A lista `touchables` NÃO inclui `Botao`/`Chip`/`MissaoCard`: eles já emitem semântica por
    dentro, e listá-los obrigaria os pontos de uso a repetir o que o componente entrega.
- **Resultado assíncrono é ANUNCIADO** (`useAnuncio`, em `src/lib/anunciar.ts`). O `Aviso` já era
  região viva, então a FALHA falava e o SUCESSO não — check-in aceito, transferência concluída e
  vizinho encontrado mudavam a tela em silêncio. E `accessibilityLiveRegion` **é prop de Android**:
  no iOS ela não faz nada, então o anúncio explícito entra mesmo onde já existe `Aviso`.
  `paraFala()` troca "180 m" por "180 metros", porque TTS trata abreviação de unidade de forma
  inconsistente e instrução sem unidade não orienta.
- **`TituloTela` é o cabeçalho de toda tela e seção.** Ele dá `accessibilityRole="header"` (a
  navegação por títulos do TalkBack depende disso) e move o foco para si na troca de rota — sem
  isso, cada navegação joga a pessoa no topo da árvore. Só o título de TELA puxa foco; o de seção
  não, senão dois competiriam e a leitura sairia na ordem de montagem.
- **`maxFontSizeMultiplier` SÓ em controle compacto** — hoje só no `Chip`, onde a barra de cinco
  filtros quebra em duas linhas. **Nunca em corpo de texto**, que é justamente o que precisa
  crescer. Altura de controle é `minHeight`, nunca `height`.
- **`useMovimentoReduzido()`** (`src/lib/movimento.ts`) desliga o pulso do `Esqueleto` e as
  transições do Expo Router. Com movimento reduzido o esqueleto não tem `Animated.Value` nenhum na
  árvore — é opacidade fixa, não animação parada.
- **Categoria tem GLIFO além de cor** (`glifoCategoria`): ◆ entrega · ● coleta · ▲ tribo · ■ ajuda.
  O glifo é decorativo (`importantForAccessibility="no"`) e por isso o `Chip` carrega
  `accessibilityLabel` explícito — sem ele o leitor de tela anunciaria "losango Entrega". Vale
  também no marcador do mapa, onde as quatro categorias eram indistinguíveis sem texto ao lado.
- **O radar tem DUAS apresentações da mesma rota** (ADR 0030): `Mapa | Lista`, com a escolha
  persistida em `src/features/mapa/apresentacao.ts`. A lista existe porque a WebView do Leaflet não
  expõe semântica — e o ponto de custódia só existia lá dentro. **Não é tela separada**: duas rotas
  divergiriam, e a que menos gente usa é a que fica para trás, que aqui seria justamente a acessível.
  - **O cliente NÃO reordena.** A ordem por distância vem do servidor (`ORDER BY distancia_m ASC`
    sobre `geography`), e recalcular aqui daria um segundo valor, ocasionalmente diferente do que o
    mapa desenha.
  - `ItemPontoCustodia` não reusa `MissaoCard` de propósito: ponto de custódia não tem recompensa
    nem prazo, e "0 XP e 0 tokens, encerrada" para um armário seria pior que a assimetria.
- **A ordem do rótulo é a ordem da DECISÃO**: categoria, recompensa, distância, prazo — título e
  local por último. Quem navega por voz decide no primeiro terço da frase, e o título é o que menos
  separa uma missão da outra. `formatarPrazo` é relativo ("termina em 40 min"), não absoluto: a
  pergunta é "dá tempo de ir?", não "quando foi?".
- **`useLocalizacao()` NÃO pede permissão ao montar, e o default é esse de propósito.** Quem quiser
  o pedido automático passa `true` explicitamente — e precisa ter mostrado a justificativa antes.
  Use `JustificativaLocalizacao`: o diálogo do sistema é de uma via só, e negado uma vez não volta.
  O default era `true` e produziu o defeito de a aba de missões gastar o prompt sem explicar nada,
  enquanto o card do mapa chegava tarde.
- **Segredo passa SEMPRE por `src/lib/armazenamentoSeguro.ts`, nunca por `expo-secure-store`
  direto.** A lib não tem implementação web — o módulo resolvido no bundle do browser é
  literalmente `export default {}` e toda chamada estoura com `... is not a function`, no boot,
  antes da primeira tela. Ver a seção Plataforma web abaixo e o ADR 0013.
- **Falha de rotação de refresh só encerra a sessão quando ela ACABOU** (`sessaoAcabou` em
  `src/api/erros.ts`). O `/auth/refresh` tem rate limit no backend, e `encerrar()` apaga o keystore:
  tratar 429 ou queda de rede como sessão morta custava um refresh de 30 dias, irreversível. Para
  obstáculo temporário existe `limparMemoria()`, que zera a memória e PRESERVA o cofre.
- **Sair precisa esquecer.** `queryClient.clear()` no logout, no registro e na exclusão de conta — as
  query keys são globais (`['perfil']`, `['carteira','saldo']`), então sem isso a próxima pessoa a
  entrar no aparelho vê nome, e-mail e saldo da anterior.
- **Deep link é validado em `src/lib/deepLink.ts`** (allowlist de rota + UUID), ligado por
  `useDeepLink` e coberto por teste. Toda tela autenticada fora das abas vive em `app/(app)/`, que
  tem guarda de sessão — o grupo entre parênteses não entra na URL.
- **Path param interpolado passa por `seg()`** (`src/api/caminho.ts`). Axios não escapa segmento de
  path, e um id com `/` remonta a requisição contra outro endpoint com o Bearer junto.
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
agora leva a `app/(app)/beneficios.tsx`. O raciocínio antigo ("um botão ausente não ensina nada") valia
enquanto não havia para onde mandar a pessoa; um catálogo mostra o que a moeda É, e isso ensina mais
que um aviso dizendo o que ela não é.

**Benefício se expressa em BEM ou em PORCENTAGEM. Nunca em reais.** O ADR 0009 §6 é a razão: se o
token virasse conversível, ele *seria* dinheiro, com KYC e enquadramento regulatório junto. "R$ 20
em compras" fixa uma cotação token→real exatamente onde o produto recusa ter uma — é a mesma regra
que faz a carteira nunca imprimir `R$`. **A garantia hoje é do SERVIDOR**, em duas camadas
(`@Pattern` em `CadastrarBeneficioRequest` e `ck_beneficio_sem_reais` na V24); do lado do app sobrou
o teste de que a tela não reintroduz `R$` por copy própria.

**O catálogo vem da API** — `GET /api/v1/beneficios`, por tribo ou por proximidade. Era dado LOCAL
enquanto o sumidouro não existia; a F16 (V24-V26, ADR 0027) o trouxe, e `src/features/beneficios/
catalogo.ts` encolheu para só `estadoDoResgate`.

**O resgate QUEIMA token, e o saldo só muda quando o servidor confirma.** `POST /api/v1/resgates`
com `Idempotency-Key`, confirmação explícita antes de debitar, e **sem atualização otimista** — a
mesma doutrina de `useTransferirTokens`, agravada porque token queimado não volta. O sucesso invalida
`chavesCarteira.todas`, então saldo e extrato se atualizam juntos.

**"Faltam N tokens" é calculado no CLIENTE.** O backend responde saldo insuficiente com
`422 regra-negocio-violada`, que não traz campos estruturados; a frase sai de `estadoDoResgate(saldo,
custo)`, com dois números que o app já tem. Parsear o `detail` daria o mesmo texto e violaria a regra
dura abaixo.

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

## Ambiente de teste — quatro armadilhas

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
- **`unmount()` da RNTL 14 também é ASSÍNCRONO.** Sem `await`, um segundo `render` no mesmo teste
  **trava o processo inteiro** — não falha, não estoura o `testTimeout` de 30 s, apenas para. O
  sintoma é o `npm test` pendurado sem nenhuma linha vermelha. Aparece em teste de percurso, que
  monta e desmonta telas em sequência.
- **O PRIMEIRO teste de uma suíte de tela é ordens de grandeza mais caro que os outros**, e os 5 s
  de default do jest não cabiam nele no CI. O `react-native` exporta componentes por getters
  preguiçosos: o grafo de módulos só carrega no primeiro `render()`, dentro do primeiro teste. Com
  `--coverage` e cache de transformação frio, medimos 221 ms → 2110 ms. Foi essa a causa do Mobile
  CI vermelho da F9 até 2026-08-13, e o motivo de a suíte passar em toda máquina local: cache quente
  e CPU rápida escondiam. Hoje `jest.config.js` fixa `testTimeout: 30000`. Se um teste novo estourar
  isso, é lentidão de verdade — não o aquecimento.
