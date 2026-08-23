# Inventário de acessibilidade — `apps/mobile`

> **STATUS (2026-08-24): fechado, menos dois cosméticos.** A F18 corrigiu A1, A3, A4, A5, A6, A7,
> A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19 e A21. **O A2 fechou depois**, com o
> radar em lista (ADR 0030): o ponto de custódia deixou de existir só dentro da WebView. **Seguem
> abertos só A20 e A22**, ambos COSMÉTICO — contraste de borda de chip e de preenchimento de barra,
> nenhum dos dois com perda de informação.
>
> **A LACUNA L4 continua aberta: nenhuma passada de TalkBack foi executada até hoje.** Não há `adb`,
> emulador nem aparelho Android nesta máquina. O que existe é
> `app/__tests__/radarLista.test.tsx`, que percorre login → lista → detalhe → aceitar consultando só
> por papel e nome acessível — condição necessária, não a verificação com leitor de tela.
>
> O documento abaixo fica como está — é o levantamento que originou as correções, e reescrevê-lo
> apagaria a medição.


**Data:** 2026-08-23
**HEAD:** `e6abcdbde95ebca7acf7d2ecad6abdaeebd785e4` (branch `develop`)
**Escopo:** 8.935 linhas em `app/` e `src/`, sem testes. 58 instâncias pressáveis em 19 arquivos.
**Método:** leitura de código, contagem por `grep` e cálculo de contraste WCAG sobre os tokens de
`src/theme/tokens.ts`. **Nenhuma passada de TalkBack foi executada** — continua sendo a LACUNA L4 de
`mobile-completo.md`, e este documento é inventário estático, não verificação com leitor de tela.

> **Este relatório não altera arquivo nenhum.** Corrigir é tarefa separada.

---

## Sumário

| # | Achado | Impacto |
|---|---|---|
| A1 | Marcador do mapa: alvo de **18×18 dp** dentro da WebView, e é a única forma de chegar aos pontos de custódia | **BLOQUEIA** |
| A2 | Ponto de custódia e clima **só existem no mapa** — a "rota textual equivalente" alegada não cobre os dois | **BLOQUEIA** |
| A3 | `user-scalable=no` + `zoomControl: false`: **não há como ampliar o mapa sem pinça** | **BLOQUEIA** |
| A4 | Sucesso de operação de valor (check-in, transferência, ação de missão) **não é anunciado**; só o erro é | **BLOQUEIA** |
| A5 | Erro de campo do `CampoTexto` e erro de login/registro **aparecem sem live region** | **BLOQUEIA** |
| A6 | 8 `FolhaInferior` e só 1 tem `ScrollView`: a 200% de fonte o formulário de transferência fica inalcançável | **BLOQUEIA** |
| A7 | Chip selecionado: branco sobre `verdePrimario` = **3,39:1** (mínimo 4,5:1) — o controle mais tocado do app | **DIFICULTA** |
| A8 | `accessibilityHint` existe em **2 de 58** pressáveis | **DIFICULTA** |
| A9 | `AccessibilityInfo` é consultado em **1 arquivo**; reduce-motion e leitor-ativo **nunca** são consultados | **DIFICULTA** |
| A10 | Contador de não lidos no `tabBarBadge` é **invisível** para leitor de tela | **DIFICULTA** |
| A11 | Falha ao alternar consentimento e ao marcar aviso como lido é **silenciosa para todos** | **DIFICULTA** |
| A12 | Aba ativa 3,39:1 e badge 3,87:1 (mínimo 4,5:1) | **DIFICULTA** |
| A13 | Grupos de chips (categoria, complexidade, modo) são radiogroups anunciados como botões soltos | **DIFICULTA** |
| A14 | Sinal `+`/`−` do extrato é nó de texto isolado que o TTS tende a não pronunciar | **DIFICULTA** |
| A15 | 7 títulos de seção sem `accessibilityRole="header"` | **DIFICULTA** |
| A16 | 4 alvos abaixo de 44 dp fora do mapa (2 `<Link>` a 22, "Fechar" a 42, `Switch` nativo) | **DIFICULTA** |
| A17 | Severidade do `Aviso` é só matiz em 6 de 12 usos; `role="alert"` em todos, inclusive informativo | **DIFICULTA** |
| A18 | 4 categorias de missão no mapa se distinguem **só por matiz** | **DIFICULTA** |
| A19 | `MissaoCard` não pressável continua anunciado como `button` | **COSMÉTICO** |
| A20 | Borda do chip 1,38:1 sobre fundo branco quase idêntico | **COSMÉTICO** |
| A21 | `IconeToken` é `role="image"` sem rótulo — ruído a cada saldo | **COSMÉTICO** |
| A22 | `BarraProgresso` preenchida 2,46:1 sobre o trilho | **COSMÉTICO** |

**Duas afirmações da auditoria anterior estão incorretas** e são retificadas em §7 e §2.

---

## 1. Componentes pressáveis

### 1.1 Os três componentes-base (herdados por todas as telas)

| Arquivo | Controle | `Role` | `Label` | `Hint` | `State` |
|---|---|---|---|---|---|
| `src/components/Botao.tsx:44-49` | `Pressable` | ✅ `button` | ✅ `titulo` | ❌ | ✅ `{disabled, busy}` |
| `src/components/Chip.tsx:22-30` | `Pressable` | ✅ `button`/`text` | ⚠️ herdado do `<Text>` filho | ❌ | ✅ `{selected}`, só quando pressável |
| `src/components/CampoTexto.tsx:20-22` | `TextInput` | — (nativo) | ✅ `rotulo` | ⚠️ recebe o **erro** (ver A5) | ❌ |

A concentração nos componentes é boa arquitetura — `mobile-completo.md` §L4 já registrou isso, e
continua valendo. O que **não** é herdado: `hint` (nenhum dos três o expõe como prop) e a associação
de grupo (A13).

### 1.2 Componentes compostos

| Arquivo:linha | Controle | `Role` | `Label` | `Hint` | `State` | Observação |
|---|---|---|---|---|---|---|
| `MissaoCard.tsx:30-38` | `Pressable` | ✅ `button` | ✅ frase composta | ❌ | ❌ | `disabled={!onPress}` **não** vira `accessibilityState` → A19 |
| `FolhaInferior.tsx:57-62` | fundo | — | — | — | — | ✅ `accessibilityElementsHidden` — correto |
| `FolhaInferior.tsx:79-84` | "Fechar" | ✅ | ✅ | ❌ | ❌ | 42 dp → A16 |
| `DialogoConfirmacao.tsx:55-58` | caixa | ✅ `alert` | ❌ | ❌ | ❌ | ✅ `accessibilityViewIsModal` |
| `SeletorDataHora.tsx:71-76` | `Pressable` | ✅ | ✅ inclui "Toque para alterar" | ❌ | ❌ | hint dobrado no label — funciona |
| `EstadoVazio.tsx:16-19` | `View`+`Botao` | ❌ no título | — | — | — | título sem `header` |
| `IndicadorPaginas.tsx:19-30` | — | — | ✅ "Página N de M" | — | — | **exemplar**: pontos com `importantForAccessibility="no"` |
| `BarraProgresso.tsx:37-41` | — | ✅ `progressbar` | ✅ `rotuloAcessivel` | ❌ | ✅ `accessibilityValue` | **exemplar** |
| `SaldoToken.tsx:29-31` | — | — | ✅ `"N tokens"` | — | — | o `IconeToken` irmão é `role="image"` sem rótulo → A21 |
| `MapaLeaflet.tsx:150` | `WebView` | ❌ | ✅ | ❌ | ❌ | ver §7 |

### 1.3 Telas

| Arquivo:linha | Controle | `Role` | `Label` | `Hint` | `State` |
|---|---|---|---|---|---|
| `(auth)/login.tsx:78` | `Botao` "Entrar" | ✅ herdado | ✅ | ❌ | ✅ |
| `(auth)/login.tsx:88` | `<Link>` "Criar conta" | ✅ `link` (expo-router `useLinkToPathProps.js:46`) | ✅ texto | ❌ | ❌ |
| `(auth)/registrar.tsx:97,107` | idem | idem | idem | ❌ | idem |
| `(tabs)/index.tsx:73` | `Botao` "Criar" | ✅ | ✅ | ❌ | ✅ |
| `(tabs)/index.tsx:81,87` | `Chip` modo | ✅ | ✅ | ❌ | ✅ `selected` |
| `(tabs)/index.tsx:97,99` | `Chip` categoria (×5) | ✅ | ✅ | ❌ | ✅ `selected` |
| `(tabs)/index.tsx:181` | `MissaoCard` | ✅ | ✅ | ❌ | ❌ |
| `(tabs)/mapa.tsx:155,214` | `Botao` | ✅ | ✅ | ❌ | ✅ |
| `(tabs)/mapa.tsx` (marcadores) | HTML na WebView | ⚠️ ver §7 | ⚠️ só `title` | ❌ | ❌ |
| `(tabs)/carteira.tsx:189,210,273,334` | `Botao` | ✅ | ✅ | ❌ | ✅ |
| `(tabs)/notificacoes.tsx:60,66` | `Chip` filtro | ✅ | ✅ | ❌ | ✅ |
| `(tabs)/notificacoes.tsx:103-108` | `Pressable` alerta | ✅ | ✅ inclui "Lido/Não lido" | ❌ | ❌ |
| `(tabs)/perfil.tsx:192,203,218,250,263,316` | `Botao` | ✅ | ✅ | ❌ | ✅ |
| `(tabs)/perfil.tsx:236-243` | `Switch` | ✅ nativo | ✅ | ❌ | ✅ nativo |
| `(app)/missao/[id].tsx:159,165` | `Chip` categoria/status | ✅ `text` | ✅ | — | — |
| `(app)/missao/[id].tsx:283,318` | `Botao` ação | ✅ | ✅ | ❌ | ✅ |
| `(app)/missao/criar.tsx:132,224` | `Chip` categoria/complexidade | ✅ | ✅ | ❌ | ✅ `selected` |
| `(app)/missao/criar.tsx:303,314,359,397,406,415` | `Botao` | ✅ | ✅ | ❌ | ✅ |
| `(app)/beneficios.tsx:384-395` | `Pressable` cartão | ✅ | ✅ | ✅ **único da app** | ❌ |
| `(app)/beneficios.tsx:137,312,344,352,362` | `Botao` | ✅ | ✅ | ❌ | ✅ |
| `(app)/impacto.tsx` | — | — | — | — | — | sem pressáveis |
| `onboarding.tsx:80,108` | `Botao` | ✅ | ✅ | ❌ | ✅ |

**A8 — DIFICULTA.** `accessibilityHint` aparece em **2 arquivos**: `CampoTexto.tsx` (e ali é usado
para carregar o *erro*, não a consequência da ação — ver A5) e `beneficios.tsx:388`. Nos outros 56
pressáveis não há dica de consequência. O caso mais caro é `[id].tsx:283`: o botão diz "Fazer
check-in" e não diz que isso vai ler o GPS e é o ato que credita a recompensa.

**A19 — COSMÉTICO.** `MissaoCard.tsx:31-34` faz `disabled={!onPress}` mas mantém
`accessibilityRole="button"` e não emite `accessibilityState={{ disabled: true }}`. Onde o card é
decorativo, o leitor de tela oferece um botão que não faz nada.

---

## 2. Resultado assíncrono que aparece só visualmente

O padrão do app é **assimétrico e o erro é o lado bem servido**: `Aviso.tsx:30-31` tem
`accessibilityLiveRegion="polite"` + `role="alert"`, então quase toda falha é falada. O **sucesso**
quase nunca é.

| Local | O que acontece | Anunciado? | Impacto |
|---|---|---|---|
| `[id].tsx:118-120` | check-in aceito → status muda, botões trocam | ❌ nada | **BLOQUEIA** |
| `[id].tsx` ação (aceitar/iniciar/desistir/confirmar) | chip de status muda | ❌ nada | **BLOQUEIA** |
| `carteira.tsx:126-135` | transferência OK → folha fecha, saldo muda | ❌ nada | **BLOQUEIA** |
| `carteira.tsx:92` | busca por handle OK → `Card` com o nome aparece | ❌ nada | **BLOQUEIA** |
| `criar.tsx` prévia de recompensa (`testID="previa-recompensa"`) | XP/tokens recalculam ao digitar | ❌ nada | DIFICULTA |
| `perfil.tsx:236` consentimento | `Switch` muda; **falha não é renderizada em lugar nenhum** | ❌ nada | DIFICULTA |
| `notificacoes.tsx:29` `marcarLido` | ponto some, título muda de peso; **falha não é renderizada** | ❌ nada | DIFICULTA |
| `perfil.tsx` exportar dados | abre o `Share` nativo; erro não é renderizado | ❌ nada | DIFICULTA |
| `login.tsx:73` / `registrar.tsx:92` | erro de credencial | ❌ `<Text>` sem live region | **BLOQUEIA** |
| `CampoTexto.tsx:34` | erro de validação por campo | ❌ sem live region (vai para o `hint`) | **BLOQUEIA** |
| `(tabs)/_layout.tsx:47` `tabBarBadge` | contador de não lidos | ❌ | DIFICULTA |
| `beneficios.tsx:83-97` | resgate concluído **e** falho | ✅ `announceForAccessibility` | — |
| `[id].tsx:256-262` check-in recusado | `Aviso` com orientação | ✅ live region | — |
| `criar.tsx:234`, `SeletorDataHora.tsx:81` | erro de campo | ✅ live region | — |

**A4 — BLOQUEIA.** As quatro primeiras linhas são as operações de VALOR do produto. Quem usa leitor
de tela toca "Fazer check-in", ouve o botão sumir e não recebe confirmação de que a recompensa foi
creditada. `beneficios.tsx` já resolveu exatamente este problema — com um `useEffect` que anuncia o
desfecho — e é o único lugar onde isso foi feito.

**A5 — BLOQUEIA.** `CampoTexto.tsx:22` põe o erro em `accessibilityHint`. `hint` **não é live
region**: é lido depois do rótulo, com pausa, e só quando o campo recebe foco de novo. Na prática o
formulário rejeita em silêncio. `criar.tsx:234` e `SeletorDataHora.tsx:81` fazem certo, com
`accessibilityLiveRegion="polite"` numa `<Text>` própria — a solução já existe no repositório e não
foi aplicada ao componente que concentra todos os campos.

**A11 — DIFICULTA.** `definirConsentimento.error`, `marcarLido.error` e `exportar.error` **não são
renderizados em lugar nenhum** (verificado por varredura das mutações contra os usos de `.error`).
Isto não é só acessibilidade: revogar um consentimento pode falhar e a interface volta ao estado
anterior sem dizer nada, para qualquer usuário.

**Retificação de `mobile-completo.md` §L4.** Aquela auditoria concluiu que "não encontrei ausência de
rótulo em controle sem texto" e tratou a acessibilidade como lacuna menor. A afirmação é correta no
que mediu — rótulos —, mas o inventário de rótulos não cobre anúncio de mudança de estado, que é
onde estão os achados BLOQUEIA desta seção.

---

## 3. Dimensão fixa contra escala de fonte a 200%

Ponto de partida favorável: **`allowFontScaling` não é desligado em lugar nenhum do app**, e os
controles usam `minHeight`, não `height`.

| Local | Valor | Cresce com a fonte? | Veredito |
|---|---|---|---|
| `Botao.tsx:71,104` | `minHeight: 48` / `44` | ✅ | ok |
| `CampoTexto.tsx:44,53` | `minHeight: 48` / `96` | ✅ | ok |
| `SeletorDataHora.tsx:116` | `minHeight: 48` | ✅ | ok |
| `onboarding.tsx:120` | `minHeight: 48` | ✅ | ok |
| `criar.tsx:482` | `mapaSeletor: { height: 320 }` | ❌ | ok — é mapa, não texto |
| `notificacoes.tsx:142` | ponto 10×10 | ❌ | ok — decorativo |
| `impacto.tsx:350` | divisor `height: 1` | ❌ | ok — decorativo |
| `IndicadorPaginas.tsx:40-44` | 8/24×8 | ❌ | ok — decorativo, com rótulo textual |
| `SaldoToken.tsx:26` | ícone 16/28 | ❌ | COSMÉTICO — ícone encolhe ao lado do número ampliado |
| `MissaoCard.tsx:53,57` | `numberOfLines={2}` e `{1}` | — | **DIFICULTA** — título e bairro truncam antes a 200% |
| `FolhaInferior.tsx:52` | `outputRange: [420, 0]` | ❌ | COSMÉTICO — folha mais alta que 420 começa parcialmente visível |
| **`FolhaInferior.tsx:88`** | `{children}` sem `ScrollView` nem `maxHeight` | — | **BLOQUEIA** |

**A6 — BLOQUEIA.** São **8 usos de `FolhaInferior`** e **apenas um** (`perfil.tsx:228`) embrulha o
conteúdo num `ScrollView`. Os outros sete injetam os filhos direto numa `Animated.View` sem altura
máxima e sem rolagem. O pior caso é `carteira.tsx:252-341`: texto explicativo + campo `@` + botão
buscar + card de confirmação + campo de quantidade + campo de mensagem + aviso de erro + botão de
transferir. A 200% de fonte, o botão "Transferir" fica abaixo da borda da tela **sem gesto que o
alcance** — a transferência deixa de ser executável. Mesmo padrão em `criar.tsx:400-426` (folha de
pontos de custódia, um `Botao` por ponto) e `beneficios.tsx:168-193`.

**A6 é o achado mais fácil de corrigir e o mais caro de ignorar**: um `ScrollView` em
`FolhaInferior.tsx` conserta os oito de uma vez, e a solução já está escrita em `perfil.tsx:228`.

---

## 4. Alvos de toque abaixo de 44 dp sem `hitSlop`

Existem **três** `hitSlop` no app inteiro: `Chip.tsx:30`, `beneficios.tsx:395`, `FolhaInferior.tsx:83`.

| Controle | Cálculo | Efetivo | Veredito |
|---|---|---|---|
| `Botao` primário/secundário | `minHeight: 48` | 48 | ok |
| `Botao` variante `texto` | `minHeight: 44` | 44 | ok |
| `CampoTexto`, `SeletorDataHora` | `minHeight: 48` | 48 | ok |
| `Chip` | 8 + 18 + 8 + 2×borda = 36; `hitSlop` 6/6 | 48 | ok |
| Cartão de benefício | conteúdo alto; `hitSlop: 8` | > 44 | ok |
| `MissaoCard`, alerta de `notificacoes` | `Card` com `padding: 16` | > 44 | ok |
| Abas | altura padrão do React Navigation | ~49 | ok |
| **"Fechar" da `FolhaInferior`** | lineHeight 18 + `hitSlop: 12`×2 | **42** | **DIFICULTA** |
| **`<Link>` "Criar conta" / "Entrar"** | `tipografia.corpo` lineHeight 22, **sem `hitSlop`** | **22** | **DIFICULTA** |
| **`Switch` de consentimento** | nativo (~31 no iOS), sem `hitSlop` | **~31** | **DIFICULTA** |
| **Marcador do mapa** | `iconSize: [18, 18]` (`MapaLeaflet.tsx:216`) | **18** | **BLOQUEIA** |

**A16 — DIFICULTA.** O "Fechar" a 42 dp já era conhecido (`mobile-completo.md` §L4) e **continua
aberto**. Os dois `<Link>` a **22 dp** são novos: não foram medidos na auditoria anterior porque ela
inventariou `Pressable` e `Botao`, e o `<Link>` do expo-router é um `<Text>`. São o único caminho
entre login e cadastro, e ficam abaixo de metade do mínimo.

**A1 — BLOQUEIA.** O marcador do mapa tem 18×18 dp e não há `hitSlop` possível: ele é um `<div>`
dentro da WebView, gerado por `L.divIcon` em `MapaLeaflet.tsx:210-219`. É menos de **17% da área**
que a WCAG 2.5.5 pede. E não é um alvo qualquer: **é a única forma de abrir um ponto de custódia**
(ver A2).

---

## 5. Informação transmitida só por cor

### 5.1 Chips de categoria — o ponto de partida pedido: **não é violação**

`coresCategoria` (`tokens.ts:104-109`) dá fundo e texto por categoria, mas **o chip sempre carrega o
nome da categoria em texto** (`rotuloCategoria`). A cor é reforço redundante, não o canal. O javadoc
do token registra que ENTREGA e TRIBO já foram corrigidos justamente para não colidirem depois do
ajuste de contraste — a preocupação já tinha sido levantada e resolvida.

O mesmo vale para `coresStatus` (`tokens.ts:114-134`): ABERTA, ACEITA e EM_ANDAMENTO compartilham a
mesma dupla de cores, e CANCELADA/EXPIRADA também — mas o chip diz o status por extenso. Agrupamento
por cor sem perda de informação.

**Conclusão: os chips estão certos.** O que está errado neles é contraste (A7) e semântica de grupo
(A13), não dependência de cor.

### 5.2 Onde a cor É o único canal

| Local | Informação | Canal redundante? | Veredito |
|---|---|---|---|
| `MapaLeaflet` — 4 categorias de missão | qual categoria | ❌ todas são `forma: 'pino'`, só o matiz muda | **A18 — DIFICULTA** |
| `MapaLeaflet` — missão × ponto de custódia | qual tipo | ✅ `pino` vs `quadrado` | ok |
| `Aviso` — `informacao`/`atencao`/`erro` | severidade | ⚠️ só quando há `titulo` | **A17 — DIFICULTA** |
| `Chip` selecionado × não selecionado | seleção | ✅ luminância (3,39:1 entre os fundos) + `accessibilityState` | ok |
| Aba ativa × inativa | qual aba | ✅ `accessibilityState` nativo do React Navigation | ok |
| `notificacoes` lido × não lido | leitura | ✅ ponto + peso da fonte + **rótulo diz "Lido/Não lido"** | ok — exemplar |
| `Conquista` (`perfil.tsx:328`) | conquistada | ✅ glifo ★/☆ | ok — exemplar |
| Extrato crédito × débito | direção | ⚠️ `+`/`−` são texto, mas em nó isolado | **A14 — DIFICULTA** |
| `CampoTexto` com erro | inválido | ✅ borda coral **e** texto abaixo | ok |
| `IndicadorPaginas` | página atual | ✅ ponto ativo é mais largo | ok — exemplar |

**A18 — DIFICULTA.** `mapa.tsx:88` usa `coresCategoria[...].texto` como cor do pino e `forma: 'pino'`
para todas as quatro categorias. O comentário em `MapaLeaflet.tsx:64-66` afirma distinguir por forma
"além de cor", e isso é verdade **entre missão e ponto de custódia** — não entre as quatro
categorias de missão. Para deuteranopia, ENTREGA (`verdeEscuro`) e COLETA (`ambar`) ficam próximos.

**A17 — DIFICULTA.** Em **6 dos 12** usos de `<Aviso>` não há `titulo`: `carteira.tsx:284,331`,
`perfil.tsx:275,314`, `criar.tsx:356`, `mapa.tsx:191`. Nesses, a severidade existe só no matiz do
fundo. E os três tons emitem `accessibilityRole="alert"` (`Aviso.tsx:31`), então o leitor de tela
anuncia um informativo com a mesma urgência de um erro — a distinção não chega por canal nenhum.

**A14 — DIFICULTA.** `carteira.tsx:358-362` renderiza `+` ou `−` (U+2212) numa `<Text>` própria, ao
lado de um `SaldoToken` cujo rótulo é só `"23 tokens"`. A cor é redundante (e corretamente usa
`textoAcessivel.coral`, como o comentário do próprio arquivo explica), mas o sinal fica num nó de
texto isolado com um único caractere de pontuação — que a maioria dos motores de TTS não pronuncia.
O resultado provável: crédito e débito indistinguíveis no extrato falado.

### 5.3 Contraste medido

Calculado sobre os hex de `tokens.ts` pela fórmula WCAG 2.1.

| Par | Razão | Mínimo | |
|---|---:|---:|---|
| **Chip selecionado: branco sobre `verdePrimario` (13 px)** | **3,39:1** | 4,5 | **REPROVA — A7** |
| **Aba ativa: `verdePrimario` sobre branco (12 px)** | **3,39:1** | 4,5 | **REPROVA — A12** |
| **Badge de avisos: branco sobre `coral`** | **3,87:1** | 4,5 | **REPROVA — A12** |
| Chip não selecionado: `tinta70` sobre branco | 7,47:1 | 4,5 | passa |
| Chip ENTREGA / COLETA / TRIBO / AJUDA | 5,46 / 4,55 / 6,20 / 4,56 | 4,5 | passa |
| Aba inativa: `suave` sobre branco | 4,78:1 | 4,5 | passa |
| **`BarraProgresso` preenchida sobre o trilho** | **2,46:1** | 3,0 | **REPROVA — A22** |
| **Borda do chip: `linha` sobre branco** | **1,38:1** | 3,0 | **REPROVA — A20** |
| Trilho do `Switch` desligado: `linha` sobre branco | 1,38:1 | 3,0 | ver nota |
| Divisor do impacto, `Esqueleto` | 1,38 / 1,30 | — | decorativo, não se aplica |

**A7 — DIFICULTA, e é o achado de contraste que importa.** O projeto **já encontrou este número
exato**: o javadoc de `coresStatus` registra que "branco sobre `verdePrimario` dá 3,39:1, abaixo do
mínimo" e por isso moveu o status `CONCLUIDA` para `verdeEscuro`. A mesma dupla continua no
`Chip.tsx:16-17` para todo chip selecionado — filtros de categoria, modo da lista, filtro de avisos,
complexidade da criação. A correção foi aplicada ao caso descoberto e não à causa.

**A20 — COSMÉTICO, com ressalva.** Um chip não selecionado é `cores.branco` sobre fundo
`cores.papel` — 1,03:1, praticamente invisível — delimitado só por uma borda `cores.linha` a 1,38:1.
A borda é o que identifica o componente como controle (WCAG 1.4.11), mas o texto interno a 7,47:1 já
sinaliza que ali há algo. Classificado como cosmético porque a informação não se perde; vale
corrigir junto com A7.

**A22 — COSMÉTICO.** O preenchimento da barra a 2,46:1 sobre o trilho está abaixo dos 3:1 de 1.4.11,
mas em todos os usos (`perfil` XP, `impacto` funil, `beneficios` progresso) o mesmo dado aparece em
número ao lado, e a barra carrega `accessibilityValue`.

---

## 6. `AccessibilityInfo`: onde o app consulta o sistema

**Um arquivo. Duas chamadas. Uma única API.**

| Uso | Onde |
|---|---|
| `announceForAccessibility` (sucesso do resgate, com o código soletrado) | `beneficios.tsx:84` |
| `announceForAccessibility` (falha do resgate) | `beneficios.tsx:93` |
| `isScreenReaderEnabled` | **nenhum** |
| `isReduceMotionEnabled` / `prefersCrossFadeTransitions` | **nenhum** |
| `isBoldTextEnabled` / `isGrayscaleEnabled` / `isInvertColorsEnabled` | **nenhum** |
| listeners de `addEventListener` | **nenhum** |

**A9 — DIFICULTA.** Duas consequências concretas:

1. **Reduce motion é ignorado.** `Esqueleto.tsx:29-45` roda `Animated.loop` infinito de opacidade
   (800 ms para cada lado) enquanto qualquer tela estiver carregando, e `FolhaInferior.tsx:35-40`
   anima 220 ms a cada abertura. Com "reduzir movimento" ligado no sistema, os dois continuam
   animando. O pulso infinito do esqueleto é o mais problemático — é movimento persistente, não
   transição.
2. **O app não sabe se há leitor de tela ativo**, então não pode nem escolher anunciar só quando faz
   diferença nem mover foco depois de abrir uma `FolhaInferior` ou um `DialogoConfirmacao`. Nenhum
   dos oito usos de folha faz gestão de foco.

O padrão certo já existe e está isolado numa tela só: o `useEffect` de `beneficios.tsx:83-97` é
exatamente o que A4 pede em `[id].tsx` e `carteira.tsx`.

---

## 7. O que o mapa em WebView + Leaflet expõe de semântica

### 7.1 O que o nosso código declara

`MapaLeaflet.tsx:148-160` põe no `<WebView>`:

- ✅ `accessibilityLabel="Mapa das missões próximas"`
- ❌ **sem `accessibilityRole`**
- ❌ **sem `accessible`**, sem `accessibilityElementsHidden`, sem `importantForAccessibility`

**Retificação de `mobile-completo.md` §L4.** Aquela auditoria afirma que "o mapa se declara como
imagem com a lista de missões como rota textual equivalente" e cita `MapaLeaflet.tsx:120-123`. As
linhas citadas são um **comentário**, não código: não há `accessibilityRole="image"` no arquivo.
Sem `accessible` nem `role`, o `accessibilityLabel` não fecha a WebView num nó único — no Android o
TalkBack **entra na árvore de acessibilidade do conteúdo web**.

### 7.2 O que há dentro, e o que o Leaflet faz com isso

A página é gerada por `paginaLeaflet()` (`MapaLeaflet.tsx:170-262`). O que a árvore contém:

| Elemento | Semântica exposta | Origem |
|---|---|---|
| `<div id="mapa">` | nenhuma — `div` sem `role` nem rótulo | nosso HTML |
| Tiles do OpenStreetMap | `<img>` gerados pelo Leaflet, um por quadrado da malha | Leaflet |
| Marcador de missão/ponto | `<div>` de 18×18 com `title="<rótulo>"` | `L.divIcon`, `MapaLeaflet.tsx:210-219` |
| Marcador do usuário | `<div>`, `interactive: false`, `keyboard: false` | `MapaLeaflet.tsx:230-240` |
| Atribuição | `attributionControl: true` → link "© OpenStreetMap" | Leaflet |
| Controles de zoom | **inexistentes** (`zoomControl: false`, `MapaLeaflet.tsx:194`) | nosso |

Três consequências, e a distinção entre elas importa:

- **`alt` é descartado.** `MapaLeaflet.tsx:245` passa `{ icon: icone(m), title: m.rotulo, alt: m.rotulo }`.
  O `Marker._initIcon` do Leaflet só aplica `alt` quando `icon.tagName === 'IMG'`; como usamos
  `divIcon`, o elemento é um `<div>` e **o `alt` não vai a lugar nenhum**. Sobra o `title`, que é
  atributo de tooltip e é fonte de nome acessível fraca e inconsistente entre leitores.
- **Não há texto alternativo para o mapa como um todo.** O rótulo da WebView não substitui a árvore,
  porque a árvore não está fechada (7.1).
- **A atribuição do OSM é um link real** dentro da WebView, e é o único elemento com semântica
  confiável ali — quem navega por elementos encontra "© OpenStreetMap" antes de qualquer missão.

### 7.3 Os dois bloqueios

**A3 — BLOQUEIA.** `MapaLeaflet.tsx:176` fixa
`<meta name="viewport" content="... maximum-scale=1, user-scalable=no">` e `:194` desliga
`zoomControl`. Somados: **não existe caminho para ampliar o mapa que não seja a pinça de dois
dedos.** Não há botões `+`/`−`, e o zoom de página está proibido. Para quem não consegue executar
gesto multitoque — a WCAG 2.5.1 existe por isso — o mapa é uma imagem de zoom fixo.

**A2 — BLOQUEIA.** O comentário de `MapaLeaflet.tsx:145-147` diz que "a lista de missões é a rota
acessível para a mesma informação". Para **missões**, é verdade: `(tabs)/index.tsx` mostra as mesmas
missões com `MissaoCard` rotulado. Mas a aba do mapa exibe **duas informações que nenhuma outra tela
do app expõe**:

1. **Pontos de custódia** (`mapa.tsx:97-107`, folha em `:227-249`): apelido, tipo, código, distância
   e ocupação. O backend tem `GET /pontos-custodia`, o app o consome só aqui, e o **único** caminho
   até esses dados é tocar num quadrado de 18 dp no mapa. Para leitor de tela ou limitação motora,
   são inalcançáveis.
2. **Clima** (`mapa.tsx:167-181`): renderizado fora da WebView e portanto legível, mas em três nós
   soltos sem rótulo agregado — "23°", "céu limpo", "sensação 21°".

A alegação de equivalência é verdadeira para a metade que foi verificada e falsa para a outra.

---

## Ordem de correção

Por impacto, e agrupada por onde o conserto acontece — vários achados caem no mesmo arquivo.

**1. `FolhaInferior` ganha `ScrollView` (A6).** Um arquivo, conserta oito telas, e a solução já está
escrita em `perfil.tsx:228`. É o único achado desta lista que hoje torna uma operação de valor
**inexecutável** por configuração do sistema operacional. Aproveitar para o `hitSlop: 13` do
"Fechar" (A16), que está aberto desde a auditoria anterior.

**2. Anunciar o sucesso das operações de valor (A4).** `[id].tsx` (check-in e ações) e `carteira.tsx`
(transferência e busca). Copiar o `useEffect` de `beneficios.tsx:83-97`. Sem isto, a operação central
do produto acontece em silêncio para quem usa leitor de tela.

**3. Erro de formulário vira live region (A5).** Mover o erro do `accessibilityHint` para uma
`<Text accessibilityLiveRegion="polite">` em `CampoTexto.tsx`, e dar `role="alert"` aos avisos de
`login.tsx:73` e `registrar.tsx:92`. O padrão já existe em `criar.tsx:234`.

**4. Rota textual para ponto de custódia (A2).** É trabalho de produto, não de anotação: uma lista
de pontos próximos alcançável fora do mapa. Enquanto não existir, o mapa não tem equivalente
acessível e o comentário de `MapaLeaflet.tsx:145-147` precisa deixar de afirmar que tem.

**5. Zoom do mapa sem pinça (A3).** Ligar `zoomControl: true` e remover `user-scalable=no`. Duas
linhas em `paginaLeaflet()`. Vem depois de (4) porque (4) é a saída para quem não usa o mapa;
este é para quem usa e não consegue ampliar.

**6. Alvo do marcador (A1).** `iconSize` de 18 para 44, ou área transparente ampliada no `divIcon`.
Muda o desenho do mapa, então vale decidir junto com (5).

**7. Contraste: A7, A12, A20.** `Chip` selecionado passa de `verdePrimario` para `verdeEscuro` —
exatamente o que o javadoc de `coresStatus` já fez pelo status `CONCLUIDA`. Junto: `tabBarActiveTintColor`
e `tabBarBadgeStyle`. Um commit no tema resolve os três.

**8. `<Link>` de 22 dp (A16).** `hitSlop` nos dois `<Link>` de `login.tsx:88` e `registrar.tsx:107`,
ou trocá-los por `Botao variante="texto"`, que já nasce com 44.

**9. Severidade e forma (A17, A18, A14).** `titulo` obrigatório no `Aviso` — ou `role` variando por
tom; forma por categoria no marcador do mapa; sinal do extrato dobrado no `accessibilityLabel` do
`SaldoToken`.

**10. Estrutura e ruído (A15, A13, A10, A19, A21).** `accessibilityRole="header"` nos 7 títulos de
seção; `accessibilityRole="radiogroup"` nos grupos de chips; `tabBarAccessibilityLabel` com a
contagem de não lidos; `accessibilityState` no `MissaoCard`; esconder o `IconeToken`.

**11. Reduce motion (A9).** `AccessibilityInfo.isReduceMotionEnabled()` em `Esqueleto` e
`FolhaInferior`. Baixo impacto e fácil, mas depende de decidir o comportamento alternativo.

**12. Falhas silenciosas (A11).** Renderizar `definirConsentimento.error`, `marcarLido.error` e
`exportar.error`. Não é achado de acessibilidade — é defeito de UX que a varredura encontrou de
passagem, e vale registrar como tal.

---

## O que este inventário NÃO cobre

- **Nenhuma passada de TalkBack ou VoiceOver foi executada.** Tudo aqui é leitura de código e
  cálculo. A ordem de leitura real, a gestão de foco em `Modal` e o comportamento do TalkBack ao
  entrar na WebView **precisam ser verificados no aparelho** — é a LACUNA L4, que continua aberta.
- **Nenhuma medição a 200% de fonte foi feita em execução.** A análise de §3 é sobre estilos; o
  transbordo da `FolhaInferior` é dedução a partir de `Animated.View` sem `maxHeight` nem rolagem,
  e deve ser confirmado no emulador com "tamanho da fonte: máximo".
- **iOS não foi considerado além do que o código revela.** Não há Mac nem iPhone no projeto, e o
  `CLAUDE.md` já registra a certificação iOS/VoiceOver como fora de escopo.
- **O contraste foi calculado sobre os tokens, não sobre pixels renderizados.** Sobreposição,
  opacidade (`Botao` inativo a `opacity: 0.45`) e o véu dos modais mudam os valores reais — o botão
  desabilitado, em particular, não foi medido.
- **Não avaliei conteúdo textual**: legibilidade, nível de linguagem, ou se as mensagens de erro
  fazem sentido lidas em voz alta fora de contexto.
