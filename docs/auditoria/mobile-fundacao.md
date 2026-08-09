# Auditoria — Fundação do app mobile (`apps/mobile/`)

**Data:** 2026-08-09
**Branch:** `develop`
**HEAD:** `02d7caa` — *Merge pull request #9 from RenanFerreira02/feat/front-end-mobile*
**Escopo:** a FUNDAÇÃO — stack, design system, cliente HTTP, sessão, contrato de erro, tooling,
testes e README. **Não** reaudita telas, acessibilidade nem contraste: isso está em
`docs/auditoria/mobile-completo.md`, e onde eu cruzo com aquele relatório digo explicitamente.

> Nota de numeração: este arquivo não é `F8.md`. O `docs/PROGRESSO.md` reserva F8 para "Logística,
> notificações e patrocinador"; o commit `3e7e68e`, rotulado "F8 - Fundação Mobile", entregou o que o
> PROGRESSO numera como F9–F11. O histórico do git engana.

---

## Método — o que foi EXECUTADO, não lido

| # | O que | Ferramenta | Resultado |
|---|---|---|---|
| 1 | Comparação dos 12 hex e da escala de espaçamento, token a token | script Python sobre `tokens.ts` | 12/12 e 6/6 exatos |
| 2 | A regra "nenhum hex fora de `tokens.ts`" é **aplicada**? | `eslint --stdin` com `color: '#fff'` | 2 erros — regra viva |
| 2b | Limites da regra: `rgba()`, `rgb()`, template literal | `eslint --stdin` | 3 de 4 evasões passam |
| 3 | **Mutação**: remover a promessa compartilhada de refresh | cópia da árvore em `/tmp/audit-mobile` + Jest | teste vai de verde a vermelho (1 → 3) |
| 4 | Paridade do catálogo de erro, **nos dois sentidos** | `comm` sobre `TipoProblema.java` × `erros.ts` | 15 = 15, zero diferença |
| 5 | `AsyncStorage` | `grep` em `src/`, `app/`, `package.json` | nenhum import; nem a dependência existe |
| 6 | Validação Zod sob `__DEV__` e log de divergência | `npm test` (saída real de `console.warn`) | dispara e registra o caminho do campo |
| 6b | Schema de resposta × backend **em execução** | `curl` + diff de chaves em Python | `MissaoResponse` bate 30/30; página 6/6 |
| 7 | Compatibilidade do ecossistema Expo | `npx expo install --check` | "Dependencies are up to date" |
| 7b | A justificativa da exceção de hex em `app.config.ts` se sustenta? | projeto mínimo em `/tmp/audit-cfg` + `npx expo config` | conclusão certa, **razão escrita errada** |
| 8 | `npm run typecheck`, `npm run lint`, `npm test` | tsc / ESLint / Jest | 0 erros · 0 erros (9 warnings) · 125 testes verdes |
| 9 | **Sonda descartável** no formulário de transferência | teste em `/tmp/audit-mobile`, fora da árvore do projeto | requisição inválida sai para a rede; botão silencioso |

Não executei `npm run test:e2e` (instrução da tarefa: o bloqueio de login por tentativa produziria
429 e ruído). O único `curl` de login foi **um**, para obter o token usado nas medições 6b.

**Nenhum arquivo do projeto foi alterado.** `git status --porcelain` ao fim da auditoria:

```
?? docs/auditoria/mobile-completo.md
```

(o único não rastreado é o relatório do outro auditor; toda mutação minha viveu em `/tmp/audit-mobile`
e `/tmp/audit-cfg`).

---

## Veredito

**A fundação está sólida.** Os cinco itens que a tarefa mandou priorizar — tokens, regra de hex,
tempestade de refresh, paridade do catálogo e ausência de `AsyncStorage` — **passam com evidência
executada**, e o teste de refresh é dos raros que eu consegui provar que **falha quando deveria**.
Os achados abaixo são um defeito de formulário com consequência medida, uma lacuna de dependência
(`expo-dev-client`) e dois buracos pequenos em redes de proteção que hoje ninguém está explorando.

| # | Item da especificação | Classe |
|---|---|---|
| 1 | 12 tokens de cor, escala 4/8/12/16/24/32, cor por categoria | **CONFORME** |
| 2 | "Nenhum hex literal fora de `tokens.ts`" — aplicado por lint | **CONFORME**, com o buraco A1 |
| 3 | Refresh transparente em 401 **sem tempestade** | **CONFORME** — e o teste tem dentes |
| 4 | ProblemDetail → `ErroApi` discriminado por `type` | **CONFORME** (paridade exata 15/15) |
| 5 | Access token só em memória; refresh em `expo-secure-store` | **CONFORME** |
| 6 | Zod validando respostas em dev, logando divergência | **CONFORME** |
| 7 | Componentes exigidos (8) e cliente axios (baseURL, Bearer, correlation-id) | **CONFORME** |
| 8 | ESLint + Prettier + typecheck, scripts, workflow de CI | **CONFORME** |
| 9 | Testes exigidos (login ok/erro, lista, vazio, refresh, mapeamento) | **CONFORME** |
| 10 | README com celular físico, `10.0.2.2`, `0.0.0.0` e firewall | **CONFORME** |
| 11 | `app.config.ts` com 2 hex literais, exceção no lint | **DIVERGÊNCIA ACEITÁVEL**, com a razão escrita errada (A2) |
| 12 | **`expo-dev-client` ausente**, exigido nominalmente pela spec | **LACUNA (L1)** |
| 13 | **`react-hook-form` em 1 de 4 formulários; `transferenciaSchema` é código morto** | **DEFEITO (D1)** |
| 14 | Regra de hex cega a template literal e a `rgb()`/`rgba()` | **LACUNA (A1), menor** |
| 15 | Comentário em `erros.test.ts` promete uma rede de proteção que não existe | **DEFEITO (D2), de comentário** |
| 16 | Discriminação do 401 elegível **por `type`**, não por status | **EXCEDENTE** |

---

# Bloco por item

## 1. Tokens de cor e escala — **CONFORME**

Comparação um a um, script contra `apps/mobile/src/theme/tokens.ts:9-24` e `:32-39`:

```
verdePrimario   spec=#1D9E75 codigo=#1D9E75 OK
verdeEscuro     spec=#0F6E56 codigo=#0F6E56 OK
verdeClaro      spec=#E1F5EE codigo=#E1F5EE OK
ambar           spec=#BA7517 codigo=#BA7517 OK
ambarClaro      spec=#FFF1E6 codigo=#FFF1E6 OK
coral           spec=#D85A30 codigo=#D85A30 OK
coralClaro      spec=#FFE4DA codigo=#FFE4DA OK
tinta           spec=#1A2520 codigo=#1A2520 OK
tinta70         spec=#4A5853 codigo=#4A5853 OK
tinta50         spec=#7A8782 codigo=#7A8782 OK
linha           spec=#D7DDDA codigo=#D7DDDA OK
papel           spec=#F7F9F8 codigo=#F7F9F8 OK
escala: {'xs': '4', 'sm': '8', 'md': '12', 'lg': '16', 'xl': '24', 'xxl': '32'}
```

Doze de doze, nenhum dígito fora. Dois extras não pedidos e legítimos: `branco: '#FFFFFF'` e
`transparente: 'transparent'` (`tokens.ts:22-23`) — sem eles, todo `backgroundColor` branco viraria
hex literal em componente e esbarraria na própria regra de lint.

Cor por categoria (`tokens.ts:60-65`) confere com a spec: `ENTREGA` → `verdePrimario` sobre
`verdeClaro`; `COLETA` → `ambar` sobre `ambarClaro`; `TRIBO` → `verdeEscuro`; `AJUDA` → `coral`.

Fora da escala, no app inteiro, sobraram **dois** valores crus, ambos `gap: 2` em pilha de texto
(`app/(tabs)/mapa.tsx:256`, `app/(tabs)/perfil.tsx:340`). Não vejo motivo para tratar isso como
achado: 2 pt não é espaçamento de layout, é entrelinha.

Os oito componentes exigidos existem: `Botao`, `Card`, `Chip`, `CampoTexto`, `MissaoCard`,
`SaldoToken`, `EstadoVazio`, `Esqueleto` (`apps/mobile/src/components/`). `Botao` tem as três
variantes e `carregando`/`disabled` (`Botao.tsx:13,18-19,38`), com a decisão certa em
`Botao.tsx:38` — `const inativo = disabled || carregando` —, que fecha o caminho de dois aceites com
o mesmo toque. `CampoTexto` tem `erro?: string | null` (`CampoTexto.tsx:9`) e o expõe também como
`accessibilityHint` (`:23`).

## 2. A regra do hex literal é **aplicada**, não é disciplina — **CONFORME**

Isto era o ponto a provar, e prova-se assim (nenhum arquivo criado no repositório):

```console
$ printf "...return <View style={{ color: '#fff', backgroundColor: '#1D9E75' }} />;..." \
  | npx eslint --stdin --stdin-filename src/foo.tsx

/home/renan/.../apps/mobile/src/foo.tsx
  3:32  error  Cor literal é proibida fora de src/theme/tokens.ts. Importe de "@/theme"  no-restricted-syntax
  3:57  error  Cor literal é proibida fora de src/theme/tokens.ts. Importe de "@/theme"  no-restricted-syntax

✖ 2 problems (2 errors, 0 warnings)
```

A regra está em `apps/mobile/eslint.config.js:18-25`, é `error` (não warning), e o CI roda
`npm run lint` (`.github/workflows/mobile.yml:35`) — portanto **barra o merge**. E a varredura
confirma que ela está sendo obedecida: o único hex fora de `tokens.ts` em `src/` e `app/` é… nenhum.

### A1 — o seletor é cego a três formas de escrever a mesma cor — **LACUNA, menor**

Mesmo experimento, com quatro tentativas de evasão:

```console
$ printf "export const a = 'rgba(29, 158, 117, 0.5)';\nexport const b = \`#1D9E75\`;\n\
export const c = 'rgb(255,255,255)';\nexport const d = '#fff';\n" \
  | npx eslint --stdin --stdin-filename src/evasao.ts

  4:18  error  Cor literal é proibida fora de src/theme/tokens.ts...
✖ 1 problem (1 error, 0 warnings)
```

Passam: `rgba(...)`, `rgb(...)` e **o hex dentro de template literal**. O último importa mais do que
parece, porque existe exatamente um arquivo no app que escreve CSS dentro de um template literal —
`src/components/MapaLeaflet.tsx`, o HTML injetado na WebView (`:158`). Hoje ele faz a coisa certa e
interpola `${cores.papel}`; um `background: #fff` escrito ali amanhã **não seria reprovado por
nada**. A consequência não é estética: é que o único ponto do app onde a regra tem mais chance de ser
violada é justamente o ponto onde ela não olha.

Baixo impacto hoje (zero ocorrências), custo de correção baixo (acrescentar `TemplateLiteral` e
`rgba?\(` ao `no-restricted-syntax`).

### A2 — `app.config.ts` duplica dois hex, e a justificativa escrita está errada — **DIVERGÊNCIA ACEITÁVEL**

`eslint.config.js:34-41` desliga a regra para `app.config.ts`, onde há duas cores literais
(`app.config.ts:24` `#E1F5EE` e `:46` `#1D9E75`). Os valores conferem com `tokens.ts` — hoje.

O comentário justifica assim: *"não há como importá-las de src/theme, que o Metro só resolve
depois"*. Testei num projeto mínimo em `/tmp/audit-cfg`, com o mesmo `node_modules`:

```console
# app.config.ts importando './tokens'  (TypeScript)
$ npx expo config --type public
Error: Error reading Expo config at /tmp/audit-cfg/app.config.ts:
Cannot find module './tokens'
Require stack:
- /tmp/audit-cfg/app.config.js
- .../node_modules/@expo/require-utils/build/load.js

# o MESMO app.config.ts, com './tokens' agora sendo um arquivo .js
$ npx expo config --type public
    adaptiveIcon: {
      backgroundColor: '#E1F5EE'
    }
```

Ou seja: a **conclusão** está certa (não dá para importar `src/theme/tokens.ts` de dentro do
`app.config.ts`), mas a **razão** não é o Metro — o Metro não participa disso. Quem carrega o
config é `@expo/require-utils`, que transpila **só o próprio arquivo de config** para
`app.config.js` e delega o resto ao `require` do Node, que não resolve `.ts`. Com um módulo `.js` o
import funciona, como a segunda execução mostra.

Mantenho como **divergência aceitável** — duas cores, valores corretos, exceção explícita e
localizada. Mas o risco fica registrado: **se alguém mudar `verdePrimario` em `tokens.ts`, o splash
continua verde antigo e nada acusa** — não há teste, lint ou typecheck ligando os dois arquivos
(`grep -rn "app.config" src app` não retorna nenhum consumidor). Se for corrigir A1, corrija junto.

## 3. Tempestade de refresh — **CONFORME, e o teste falha quando deveria**

O desenho está em `src/api/cliente.ts:45-70`: uma promessa em módulo (`rotacaoEmVoo`), criada pela
primeira requisição que toma 401 e assinada pelas demais, limpa no `.finally`.

O teste `src/api/__tests__/refresh.test.ts:53-91` afirma que três requisições concorrentes disparam
UM refresh. **Assertion que nunca falhou não é rede de proteção**, então mutei o código fora da
árvore do projeto (`/tmp/audit-mobile`, cópia de `src/` + `node_modules` por symlink), trocando
`await rotacaoCompartilhada()` por `await rotacionar()` — a mutação exata que remove o
compartilhamento e mantém tudo o mais:

```console
# baseline, cópia intacta
Tests: 7 passed, 7 total

# mutante: rotacaoCompartilhada() → rotacionar()
✓ rotaciona uma vez e reenvia a requisição com o token novo (22 ms)
✕ N requisições concorrentes disparam UM único POST /auth/refresh (15 ms)
✓ não entra em laço quando o reenvio também toma 401 (5 ms)
✓ refresh recusado encerra a sessão e apaga o refresh persistido (8 ms)
✓ 401 do próprio login NÃO dispara rotação (2 ms)
✓ 403 não dispara rotação: o token é válido, falta permissão (2 ms)

  ● rotação de refresh no 401 › N requisições concorrentes disparam UM único POST /auth/refresh
    Expected: 1
    Received: 3

Tests: 1 failed, 6 passed, 7 total
```

O teste é sensível à exata propriedade que anuncia, e o número recebido (3) é o número de
requisições concorrentes — a tempestade materializada. Esse é o cenário que revogaria a família de
refresh tokens no backend e deslogaria o usuário por ter tentado se manter logado.

Duas defesas independentes acompanham, e cada uma tem seu próprio teste vermelho-quando-deveria:
`_jaTentouRefresh` (`cliente.ts:81`, testado em `refresh.test.ts:93-105`, que conta exatamente 2
chamadas) e a exclusão das rotas de auth (`cliente.ts:10,82`, testada em `:121-147`).

Um comportamento que o teste **não** cobre, e que examinei no código: uma requisição que já estava em
voo com o token velho e só toma 401 **depois** da rotação terminar dispara uma segunda rotação.
Isso é correto — ela apresenta o refresh **novo**, já rotacionado e persistido em `definirSessao`
(`cliente.ts:59`), não o antigo. Não é achado; registro para quem for mexer ali não "consertar".

## 4. Contrato de erro: paridade exata, nos dois sentidos — **CONFORME**

```console
$ comm -23 backend.txt front.txt   # só no BACKEND (o app cairia em 'desconhecido')
$ comm -13 backend.txt front.txt   # só no APP (URI que o backend nunca emite)
backend=15 front=15
(nenhuma linha nos dois sentidos)
```

Fontes: `services/api/.../compartilhado/api/TipoProblema.java:37-98` e
`apps/mobile/src/api/erros.ts:67-83`. As 15 URIs batem exatamente, inclusive as quatro que o ADR 0010
justifica (`saque-desabilitado` e as três de check-in) e a do ADR 0011
(`servico-externo-indisponivel`).

O desenho do lado do app está bem resolvido e vale citar o que é acerto, não sorte: a chave do mapa é
o **último segmento** da URI e não a URI inteira (`erros.ts:116-122`), o mapa é **fechado** e URI
desconhecida vira `desconhecido` preservando status e `detail` (`:200-201`) em vez de bater num
`default` que finge ter entendido, e `ehErroApi` (`:92-96`) torna a conversão idempotente.

A tradução também aguenta os **três** produtores de erro do backend, que é onde este tipo de código
costuma quebrar: `GlobalExceptionHandler` (com `traceId`), os escritores manuais do `SecurityConfig`
(401/403, sem `traceId`) e o `RateLimitFilter` (429, com `retryAfter`) — todos com caso próprio em
`erros.test.ts:109-154`, inclusive o fallback para o header `Retry-After`.

O `it.each` de `erros.test.ts:38-54` lista 14 das 15 URIs; falta `servico-externo-indisponivel`, que
está coberto em outro lugar (`app/__tests__/telas.test.tsx:96`). Não é lacuna de cobertura.

### D2 — o comentário do teste promete uma proteção que não existe — **DEFEITO, de comentário**

`erros.test.ts:8-10` afirma:

> *"Cada caso aqui corresponde a uma constante de `compartilhado/api/TipoProblema` no backend. Se uma
> URI mudar de texto lá, este arquivo fica vermelho"*

**Não fica.** Este arquivo constrói as URIs a partir de literais próprios
(`erros.test.ts:27` — `type: \`https://omnitribo.dev/problemas/${segmento}\``) e nada na suíte do
mobile lê `TipoProblema.java`. Se a URI mudasse de texto no backend, o teste continuaria verde e o
app passaria a classificar aquela resposta como `desconhecido` **em produção**, sem nenhum sinal.

Isto é exatamente o tipo de achado que a leitura do código confirmaria em vez de refutar: o
comentário é plausível e está errado.

O risco real é pequeno, e por um motivo que o comentário deveria citar: o backend pina as mesmas
strings nos **seus** testes (`ContratoErroTest`, `SaqueDesabilitadoTest:62`,
`IntegracoesControllerTest:56,113` etc.), então mudar o texto de uma URI quebra o lado de lá. A
paridade é mantida à mão, com duas listas independentes que hoje coincidem — e eu medi que
coincidem. A correção é uma frase honesta no comentário, não código novo.

## 5. Armazenamento de credencial — **CONFORME**

`grep -rn "AsyncStorage\|async-storage"` em `src/`, `app/`, `package.json` e `jest.setup.ts` devolve
**quatro linhas, todas comentário explicando por que não se usa** (`src/stores/sessao.ts:9`,
`src/features/onboarding/visto.ts:9-10`, `jest.setup.ts:18`). A dependência
`@react-native-async-storage/async-storage` **não está no `package.json`** — não é "proibido por
convenção", é ininstalável sem alguém adicioná-la de propósito.

Access token: `sessao.ts:22,36,43` — campo do store Zustand, nunca gravado; não há `persist` nem
`createJSONStorage` no arquivo (verificado por grep). Refresh: `SecureStore.setItemAsync`
(`sessao.ts:42`), removido no `encerrar` (`:51`). O interceptor lê por `getState()` e não por closure
(`sessao.ts:70`, usado em `cliente.ts:24`), que é o bug clássico deste ponto — depois de uma rotação,
um closure continuaria mandando o token velho.

## 6. Zod em dev — **CONFORME**, com o contrato conferido contra o backend de pé

`src/schemas/validar.ts:17-32`: valida sob `__DEV__`, **não lança**, loga `console.warn` com o
caminho exato do campo e devolve o dado. Em produção `__DEV__` é `false` e a função vira um
`return dado as T` — cast cego, sem validação. É o que a especificação pede, e a assimetria está
argumentada no próprio arquivo (`:5-14`): derrubar a tela por um campo novo que ela nem usa seria
pior que o problema.

Que roda de verdade, e não só existe, aparece na saída de `npm test`:

```
console.warn
  [contrato] resposta de PATCH /alertas/dddddddd-0000-0000-0000-000000000003/lido divergiu do schema:
    tipo: Invalid input: expected string, received undefined
    titulo: Invalid input: expected string, received undefined
    ...
  O dado foi usado assim mesmo. Se a tela quebrar, é aqui que começa a investigação.
      at warn (src/schemas/validar.ts:25:15)
      at src/api/alertas.ts:32:22
```

Essa divergência é do **mock**, não do produto — corrobora, com evidência independente, a lacuna L3
de `docs/auditoria/mobile-completo.md`. O `AlertaController.marcarLido` devolve `AlertaResponse`
completo (`AlertaController.java:73-86`); o handler de `app/__tests__/telas.test.tsx:339` devolve um
corpo parcial. Concordo com a classificação de lá (lacuna menor) e não a duplico aqui.

O validador é aplicado em **todas** as leituras de `src/api/` (18 pontos: `auth.ts`, `alertas.ts`,
`lugares.ts`, `missoes.ts`, `carteira.ts`, `perfil.ts`).

E o contrato bate com o servidor real. Medido contra o backend em execução, com token de `alice`:

```console
$ curl -s "http://localhost:8080/api/v1/missoes?pagina=0&tamanho=1" -H "Authorization: Bearer $TK"
no backend, fora do schema: []
no schema, ausentes no backend: []
chaves da pagina: ['pagina', 'primeira', 'tamanho', 'totalElementos', 'totalPaginas', 'ultima']
paginaSchema: conteudo, pagina, tamanho, totalElementos, totalPaginas, primeira, ultima
```

Trinta campos de `MissaoResponse` e seis da paginação, sem sobra nem falta dos dois lados. A
observação de `schemas/index.ts:120-124` sobre usar `z.guid()` e não `z.uuid()` é correta e
verificável: os ids do seed (`bbbbbbbb-0000-0000-0000-000000000002`, devolvido pelo `GET /auth/me`
que eu chamei) têm nibble de versão zero e seriam reprovados por `uuid()` — o validador passaria a
gritar contra dado válido, treinando quem lê o log a ignorar o aviso.

## 7. Stack e versões — **CONFORME**, exceto `expo-dev-client`

`expo@57.0.11` (lido de `node_modules/expo/package.json`), Expo Router 57, TanStack Query 5,
Zustand 5, axios 1.19, Zod 4, `expo-secure-store` 57, TypeScript 6 com `strict` (o `npm run
typecheck` passa limpo). Compatibilidade do ecossistema conferida com a ferramenta do próprio Expo:

```console
$ npx expo install --check
Dependencies are up to date
```

### L1 — `expo-dev-client` está ausente — **LACUNA**

A especificação o exige nominalmente. Ele não está em `package.json`, não está em `node_modules`, não
há script `--dev-client`, e o `README.md` instrui `npm start` + leitura do QR **com o Expo Go**.

O que quebra por faltar: nada hoje, provavelmente. Todos os pacotes nativos são pinos do SDK 57
resolvidos pelo `expo install`, e o `--check` passa. Mas **não consegui medir** — e não vou afirmar —
se o runtime do Expo Go do SDK 57 embarca `react-native-webview` (o mapa do ADR 0012),
`react-native-reanimated` 4 + `react-native-worklets` e
`@react-native-community/datetimepicker`. Sem dev-client, a resposta a essa pergunta aparece só na
primeira execução em dispositivo, como tela branca ou "native module not found" — que é precisamente
o risco contra o qual o item da spec estava se segurando.

Aqui eu **discordo parcialmente da especificação**: exigir `expo-dev-client` *nominalmente*, sem
apontar qual módulo o obriga, é exigir uma dependência pelo nome e não pelo problema. Se todo o
conjunto roda em Expo Go, o dev-client custa um build nativo por máquina e por AVD, e o projeto é
acadêmico e local. A ação correta não é instalar por obediência: é **decidir e registrar** — uma
linha no `README.md` ou um ADR dizendo "roda em Expo Go, dev-client dispensado, verificado em
`<data>` no AVD `<x>`". O que não pode continuar é o silêncio, porque hoje não há nem o pacote nem a
justificativa da ausência.

### D1 — `react-hook-form` em 1 de 4 formulários, e `transferenciaSchema` é código morto — **DEFEITO**

A spec pede "react-hook-form + Zod". `react-hook-form` está instalado (`package.json:35`) com
`@hookform/resolvers` (`:18`), e `grep -rln "useForm"` devolve **um** arquivo:
`app/missao/criar.tsx`. Os outros três formulários validam à mão:

| Formulário | Como valida | Schema Zod correspondente |
|---|---|---|
| `app/missao/criar.tsx` | `useForm` + resolver | `criarMissaoSchema` — usado |
| `app/(auth)/login.tsx` | `useState` + `loginSchema.safeParse` (`:26`) | `loginSchema` — usado |
| `app/(auth)/registrar.tsx` | idem | `registroSchema` — usado |
| `app/(tabs)/carteira.tsx` (transferência) | `Number.isInteger(tokens) && tokens > 0` (`:52-53`) | **`transferenciaSchema` — NUNCA importado** |

As duas primeiras linhas eu classificaria como divergência inofensiva: `useState` + `safeParse` num
formulário de dois campos é menos código que `useForm`, e a validação Zod continua lá. A quarta é
outra coisa. `transferenciaSchema` (`src/schemas/index.ts:23-32`) declara o formato do destinatário
(`z.guid()`), o teto de 500 tokens por transação e as mensagens em português — e
`grep -rln transferenciaSchema app src` (excluindo a própria declaração) **não retorna nada**.

Sonda executada em `/tmp/audit-mobile` (cópia da árvore; nada escrito no repositório), renderizando
a tela real de carteira sobre MSW:

```
>>> quantidade vazia: requisicoes = 0 | erro exibido = false
>>> destinatario vazio: corpo enviado = {"destinatarioId":"","tokens":10}
>>> tokens que chegaram na rede = 9999 (teto do transferenciaSchema = 500)

✓ quantidade vazia: nenhuma requisição sai e NENHUMA mensagem aparece
✓ quantidade acima do teto de 500 do transferenciaSchema: sai para a rede assim mesmo
```

Três consequências, todas medidas:

1. **Quantidade vazia: o botão "Transferir" não faz nada e não diz nada.** `enviarTransferencia`
   (`carteira.tsx:51-53`) faz `return` antes de qualquer `setState`, então não há requisição, não há
   `erro-transferencia` na tela e não há estado de carregando. Da perspectiva do usuário, o app
   travou.
2. **Destinatário vazio vai para a rede** como `{"destinatarioId":"","tokens":10}`. Volta 400 com
   `errors[{campo, mensagem}]` — e a tela mostra `mensagemDe(transferir.error)` (`carteira.tsx:215`),
   que é o `detail` genérico ("Um ou mais campos falharam na validação"), **sem marcar o campo**.
   Existe helper pronto para isso, `errosPorCampo` em `src/lib/formulario.ts:10-15`, e ele não é
   usado aqui.
3. **O teto de 500 não é aplicado no cliente**: 9999 tokens sobem para o servidor, que recusa com
   422. Sem impacto de economia — o servidor é a autoridade, como manda a regra —, mas é um
   round-trip e uma mensagem genérica onde o schema já tinha a mensagem certa escrita.

Nenhum teste cobre esses três caminhos: `telas.test.tsx:180-236` testa só transferência **válida** e
recusa **do servidor**. Por isso o defeito é invisível na suíte verde.

Distinto da lacuna L1 de `mobile-completo.md` ("transferência exige digitar o UUID"): aquilo é sobre
*escolher* o destinatário; isto é sobre *validar* o que foi digitado. As duas se resolvem no mesmo
formulário e provavelmente no mesmo PR.

## 8. Tooling e CI — **CONFORME**

```console
$ npm run typecheck
> tsc --noEmit
(sem saída — 0 erros)

$ npm run lint
✖ 9 problems (0 errors, 9 warnings)

$ npm test
Test Suites: 8 passed, 8 total
Tests:       125 passed, 125 total
Time:        4.583 s
```

Scripts exigidos presentes (`package.json:6-16`): `start`, `android`, `test`, `lint`, `typecheck` —
mais `ios`, `web`, `format` e `test:e2e`. Prettier entra pelo ESLint
(`eslint-plugin-prettier/recommended`, `eslint.config.js:4`), então formatação errada aparece como
lint vermelho, como o `CLAUDE.md` da raiz descreve.

Workflow em `.github/workflows/mobile.yml`: Node 22, `npm ci`, typecheck, lint, `npm test --ci
--coverage`, artefato de cobertura. `test:e2e` fora, de propósito e documentado (`:18-20` do
`jest.config.js`).

Os 9 warnings são `no-require-imports` em arquivos de config/setup, `import/no-named-as-default-member`
do axios e um `react-hooks/incompatible-library` do `watch()` do react-hook-form. Nenhum é sinal de
defeito. Registro sem classificar como achado: o `lint` não usa `--max-warnings 0`, então warnings
não barram o CI — o que é uma escolha razoável, e **não afeta a regra do hex**, que é `error`.

## 9. Testes exigidos — **CONFORME**

| Exigido | Onde | Nome do caso |
|---|---|---|
| Login sucesso | `app/__tests__/login.test.tsx:31` | "autentica, guarda a sessão e navega para as abas" |
| Login erro | `:45` | "credencial inválida mostra a mensagem e NÃO navega" |
| (extra) validação local e erro de campo do servidor | `:65`, `:84` | — |
| Lista com dados mockados | `app/__tests__/missoes.test.tsx:16` | "lista o que o radar devolveu, com a distância medida pelo servidor" |
| Estado vazio | `:40` | "radar vazio mostra o estado vazio, não uma lista em branco" |
| Interceptor de refresh em 401 | `src/api/__tests__/refresh.test.ts:31,53,93,107` | 4 casos, mutação comprovada acima |
| ProblemDetail → `ErroApi` por `type` | `src/api/__tests__/erros.test.ts:37-75` | 14 casos tabelados + os cinco 422 distinguíveis |

O caso `erros.test.ts:63-75` merece nota: monta os cinco 422 do catálogo e afirma
`new Set(tipos).size === 5`. É o teste que prova o ponto do ADR 0010 — sem `type` próprio, o conjunto
teria tamanho 1 e a tela não teria como dar instruções diferentes sem parsear `detail`. Assertion
com significado, não com contagem.

Cobertura da economia na UI, já auditada em `mobile-completo.md` e que eu confirmo por leitura:
`missoes.test.tsx:67` — "o card mostra XP e TOKEN, e NUNCA valor em reais". Não há `R$`,
`Intl.NumberFormat` com `style: 'currency'` nem `toFixed(2)` em contexto monetário em lugar nenhum
do app (grep completo: as ocorrências de `toFixed` são distância em km e arredondamento de
coordenada para chave de cache).

## 10. README — **CONFORME**

`apps/mobile/README.md` cobre os três pontos exigidos, com número de linha:

- **:31** celular físico — `http://<IP-da-sua-máquina>:8080`, com exemplo, e **:46** a seção
  "Descobrindo o IP da máquina";
- **:32** emulador Android — `http://10.0.2.2:8080`, com a explicação do alias;
- **:58** Spring escutando em `0.0.0.0`, com instrução de conferir;
- **:71-72** firewall do Fedora (`firewall-cmd`, sessão e permanente).

O código faz o mesmo raciocínio em runtime (`src/api/baseUrl.ts:23-38`): variável
`EXPO_PUBLIC_API_URL` → host do Metro → default por plataforma. Derivar do `hostUri` do Metro é a
decisão que evita o erro mais comum de quem começa (`localhost` dentro do celular é o próprio
celular), e o fallback `10.0.2.2` só entra quando não há bundler.

## 11. EXCEDENTE — o 401 elegível é decidido por `type`, não por status

`cliente.ts:78-86` só considera rotação quando `paraErroApi(erro).tipo === 'naoAutenticado'`. A
especificação pedia "refresh transparente em 401"; a implementação é mais estreita de propósito, e o
ganho é concreto: um 401 do **próprio login** (senha errada de outra pessoa no mesmo aparelho) não
gasta o refresh do usuário anterior. Está medido em `refresh.test.ts:121-147` (`refreshes === 0`), e
o caso irmão do 403 em `:149-168`.

O segundo excedente do mesmo arquivo é a ordem dos interceptors (`cliente.ts:116-119`, com a
justificativa em `:108-115`): o conversor para `ErroApi` é registrado **depois** do de 401, então a
fronteira do axios acaba nesse arquivo e nenhuma tela, hook ou teste precisa saber que existe axios
embaixo. Isso é o que permite os testes de tela afirmarem `rejects.toMatchObject({ tipo: ... })` sem
tocar em `AxiosError`.

---

# Ordem de correção por impacto

1. **D1 — validação do formulário de transferência** (`app/(tabs)/carteira.tsx:51-53,183-225`).
   É o único achado com falha visível para o usuário e já medida: botão que não responde e não
   explica. Aplicar `transferenciaSchema` (que já existe, com as mensagens escritas) e distribuir os
   erros de campo com `errosPorCampo` (`src/lib/formulario.ts:10`). **Faz sentido entregar junto com
   a L1 de `mobile-completo.md`** (escolher o destinatário em vez de digitar UUID): as duas tocam o
   mesmo `CampoTexto` de destinatário, e corrigir só a validação deixaria o app rejeitando com
   capricho um UUID que ele nunca deveria ter pedido para o usuário digitar.

2. **L1 — decidir sobre `expo-dev-client`.** Não é instalar por obediência: é verificar num AVD se
   `react-native-webview` + `reanimated 4` + `datetimepicker` rodam em Expo Go e **registrar a
   resposta**. Se rodarem, uma linha no README fecha o item; se não rodarem, o dev-client entra e o
   README muda junto. Vem antes dos itens 3 e 4 porque é o único cujo risco é "não funciona no
   aparelho", e porque a resposta pode chegar em dez minutos com um emulador aberto.

3. **A1 + A2 juntos — a regra do hex e a duplicação em `app.config.ts`.** Estes dois **não devem ser
   entregues separados**: A1 sozinho (fechar o seletor para template literal e `rgb()/rgba()`) dá a
   impressão de que a regra passou a ser total, enquanto `app.config.ts` continua com dois hex que
   ninguém verifica; A2 sozinho (extrair um `theme/cores.js` que os dois lados importem) fecha a
   duplicação e deixa o buraco do `MapaLeaflet.tsx` aberto. Feitos juntos, a afirmação "nenhum hex
   literal fora do tema" passa a ser verdadeira em toda a árvore, que é o que a especificação diz.

4. **D2 — o comentário de `erros.test.ts:8-10`.** Custo de minutos, e é dívida de confiança: alguém
   vai ler aquela frase antes de mudar uma URI e concluir que está protegido. Trocar por: "as URIs
   estão pinadas dos DOIS lados — aqui e nos testes do backend (`ContratoErroTest`); mudar o texto de
   uma quebra o lado de lá, não este arquivo". Se quiserem a proteção de verdade, o caminho é um
   teste que leia `TipoProblema.java` e compare com `POR_SEGMENTO` — mas isso é decisão de projeto,
   não correção de auditoria.

**Não achei o que corrigir** em tokens, escala, componentes, armazenamento de credencial, paridade do
catálogo, validação Zod em dev, cliente HTTP, README, scripts ou CI. Nesses pontos a fundação faz o
que a especificação pede e, em três deles (discriminação por `type` no 401, mapa fechado de URIs,
`z.guid()` em vez de `z.uuid()`), faz melhor do que ela pedia.

PARE aqui. Corrigir é tarefa separada, e quem decide quando é o autor do projeto.
