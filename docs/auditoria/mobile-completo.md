# Auditoria — Conclusão do app mobile (telas + endpoints de suporte)

**Data:** 2026-08-09
**Branch:** `develop`
**HEAD:** `02d7caa558ca4dcf1f070441cd2776955f765b55`
**Escopo:** `apps/mobile/` completo + os endpoints nunca auditados que o sustentam
(`/usuarios/me`, `/usuarios/me/dados`, `/usuarios/me/consentimentos`, `DELETE /usuarios/me`,
`/tribos`, `/alertas`, `/pontos-custodia`, `/clima`, `/enderecos/{cep}`).

> Não é `F8.md` de propósito: `docs/PROGRESSO.md` reserva F8 para logística, notificações e
> patrocinador. Nenhum arquivo do projeto foi alterado por esta auditoria — `git status --porcelain`
> devolveu vazio ao final.

---

## Método — o que foi EXECUTADO

Contra o sistema em execução (backend `localhost:8080`, PostgreSQL+PostGIS em `omnitribo-db`):

| # | O que | Ferramenta |
|---|---|---|
| 1 | Criação de missão com `xpRecompensa`/`tokensRecompensa`/`poteTokens` injetados pelo cliente, e leitura do que foi PERSISTIDO | `curl` + `psql` |
| 2 | `valorBrl > 0` na criação | `curl` |
| 3 | `POST /carteira/saques` | `curl` |
| 4 | `GET /usuarios/me/dados` para dois usuários (um com 19 check-ins), com `grep` por segredo | `curl` + `grep`/`jq` |
| 5 | Ciclo completo de `DELETE /usuarios/me`: usuário descartável criado por SQL, senha errada, senha certa, replay, login pós-exclusão, e verificação NO BANCO | `psql` + `curl` |
| 6 | Reconciliação admin depois da exclusão | `curl` |
| 7 | 401 sem token nos 9 endpoints novos; isolamento de caixa de alertas entre alice e bob; `PATCH .../lido` cruzado; ponto de custódia inativo | `curl` + `psql` |
| 8 | 409 `transicao-invalida` real numa missão CONCLUIDA | `curl` |
| 9 | Razão de contraste WCAG (luminância relativa) dos 22 pares texto/fundo que o app usa | script Node |
| 10 | `npm run typecheck`, `npm run lint`, `npm test`, `npm run test:e2e` | Jest/tsc/ESLint |
| 11 | **Teste descartável** (fora da árvore do projeto, em `/tmp/auditoria-mobile/`) provando quem dispara o prompt de permissão | Jest com config em `/tmp` |

Dados que criei foram removidos: usuário `zeta_auditoria`, duas missões de teste, e o `alerta`
`dddddddd-…-0002` foi restaurado para `lido = false`; `ponto_custodia` `VZ-VMA-001` voltou a
`ativo = true`. `select count(*) from usuario` voltou a 6.

---

## Veredito

O núcleo econômico está **certo, e certo pelos motivos certos** — não é acidente de implementação, é
barreira em três camadas (tipo TypeScript literal, teste que captura o corpo, e validação do
servidor). O mesmo vale para saque, LGPD e autorização: tudo o que eu esperava encontrar quebrado
nessas áreas está íntegro, e a evidência está colada abaixo.

Os quatro defeitos estão fora da economia. Dois deles seriam invisíveis na leitura do código: o
prompt de permissão que já foi gasto antes da tela que o justifica existir (e cujo teste passa por
renderizar a tela errada), e a conta anonimizada que continua **escrevendo** no sistema por 15
minutos.

| # | Item | Classe |
|---|---|---|
| 1 | Criar missão — sem valor/recompensa, prévia com debounce, fórmula não reimplementada | **CONFORME** |
| 2 | Nenhuma formatação monetária em lugar nenhum | **CONFORME** |
| 3 | Carteira — TOKEN em destaque, `saldoBrl` oculto com motivo escrito | **CONFORME** |
| 4 | Botão de saque desabilitado + discriminação por `type` + teste do 422 | **CONFORME** |
| 5 | Debounces (500 ms mapa por gesto, 500 ms CEP, 400 ms prévia) | **CONFORME** |
| 6 | Exportação LGPD — sem senha, hash, chave de idempotência ou contraparte | **CONFORME** |
| 7 | Exclusão de conta — anonimização, 403 em senha errada, refresh revogado, reconciliação íntegra | **CONFORME**, com o defeito D2 ao lado |
| 8 | Autorização dos endpoints novos (401, isolamento, 404 do inativo) | **CONFORME** |
| 9 | Detalhe — botão contextual por status × papel, confirmação, 409 revertido | **CONFORME** |
| 10 | Onboarding, extrato, notificações, perfil, check-in | **CONFORME** |
| 11 | Mapa por WebView + Leaflet | **DIVERGÊNCIA ACEITÁVEL** (ADR 0012) |
| 12 | Contador de não lidos na tab bar, não na barra superior | **DIVERGÊNCIA ACEITÁVEL** |
| 13 | **Permissão de localização pedida sem justificativa em tela** | **DEFEITO (D1)** |
| 14 | **Conta anonimizada continua escrevendo por 15 min** | **DEFEITO (D2)** |
| 15 | **Contraste: 11 de 22 pares reprovam em WCAG AA** | **DEFEITO (D3)** |
| 16 | **`nivel` divergente entre `/usuarios/me` e a exportação LGPD** | **DEFEITO (D4)** |
| 17 | Transferência exige digitar o UUID do destinatário | **LACUNA (L1)** |
| 18 | Registro ainda não escolhe tribo, com comentário obsoleto | **LACUNA (L2)** |
| 19 | Mock MSW de `PATCH /alertas/{id}/lido` diverge do contrato real | **LACUNA (L3), menor** |
| 20 | `accessibilityLabel` fora dos componentes; alvo de 42 pt na folha inferior | **LACUNA (L4), menor** |

---

# Bloco por item

## 1. Economia da criação de missão — **CONFORME**

### 1a. O payload não carrega recompensa, e o servidor não a aceitaria mesmo se carregasse

`app/missao/criar.tsx:434-459` monta o corpo. Não há campo de recompensa, e `valorBrl` é literal:

```ts
// src/api/tipos.ts:248
valorBrl: 0;          // tipo literal `0`, não `number` — barreira de COMPILAÇÃO
```

O teste `app/__tests__/criarMissao.test.tsx:66-89` intercepta o corpo real via MSW e afirma a
ausência (`not.toHaveProperty('xpRecompensa')`, `'tokensRecompensa'`, `'poteTokens'`). Rodei a suíte:
passa.

E reproduzi por `curl` o pior caso — um cliente hostil mandando os três campos:

```
POST /api/v1/missoes  {"xpRecompensa":99999,"tokensRecompensa":99999,"poteTokens":5000,...}

resposta:  {"xpRecompensa": 60, "tokensRecompensa": 20, "poteTokens": 0, "valorBrl": 0}

no banco:
 xp_recompensa | tokens_recompensa | pote_tokens | valor_brl | versao_formula
            60 |                20 |           0 |      0.00 |              1
```

Os 99999 foram descartados em silêncio (`fail-on-unknown-properties: false`, decisão registrada em
`services/api/CLAUDE.md`). E `valorBrl` positivo é 400 com o campo apontado:

```json
{"status":400,"type":"https://omnitribo.dev/problemas/requisicao-invalida",
 "errors":[{"campo":"valorBrl","mensagem":"Missão não remunera em BRL nesta versão — a recompensa é em XP e tokens"}]}
```

### 1b. A fórmula NÃO foi reimplementada em TypeScript

`grep -rniE "multiplicador|complexidadeFator|calcularRecompensa|xpBase|tokenBase|fatorDistancia|versaoFormula"`
em `src/` e `app/` devolve **quatro** linhas, todas declaração de tipo ou asserção de teste:

```
src/api/tipos.ts:226:  versaoFormula: number;
src/schemas/index.ts:299:  versaoFormula: z.number(),
src/testes/fixtures.ts:237:  versaoFormula: 1,
src/api/__tests__/ciclo.e2e.test.ts:122: expect(previa.versaoFormula).toBeGreaterThanOrEqual(1);
```

Nenhuma tabela de complexidade, nenhum multiplicador, nenhuma aritmética de token no cliente. O
número que a tela mostra vem de `POST /missoes/previa-recompensa` (`app/missao/criar.tsx:249-253`).

### 1c. Prévia é leitura, e falhar não bloqueia

`app/missao/criar.tsx:248-268`: `previa.data` renderiza `<Text>` + `<SaldoToken>`, sem `TextInput`.
No ramo `previa.isError` a tela mostra "A recompensa será calculada ao publicar" e o botão continua
ativo — travado por `criarMissao.test.tsx:105-131`, que força 500 na prévia e afirma que o `POST
/missoes` acontece assim mesmo.

### 1d. Complexidade derivada vs. declarada

`app/missao/criar.tsx:172-232`: ENTREGA/COLETA renderizam peso e volume e **não** renderizam
complexidade; TRIBO/AJUDA o inverso. A troca de categoria limpa os três campos
(`criar.tsx:143-150`), o que evita o 400 de "declarar junto". Testado em `criarMissao.test.tsx:133-176`.

---

## 2. Formatação monetária — **CONFORME**

`grep` por `Intl.`, `toLocaleString`, `currency`, `R\$`, `BRL` em `src/` e `app/`:

- **Zero** ocorrências de `Intl.NumberFormat` ou `style: 'currency'` em código executável — as
  únicas menções estão no comentário de `src/components/SaldoToken.tsx:16-17`, que as **proíbe**.
- `toLocaleString` aparece duas vezes: `SaldoToken.tsx:30` (`toLocaleString('pt-BR')`, separador de
  milhar sem moeda — 1240 → "1.240") e `src/lib/formatar.ts:48` / `SeletorDataHora.tsx:92`, ambos
  para **data**.
- `R$` aparece quatro vezes, todas em comentário explicando a ausência.
- `saldoBrl`: existe em `src/api/tipos.ts:204` e `src/schemas/index.ts:190` (o DTO é fiel ao
  servidor) e **não é lido por nenhum componente**. A única ocorrência em `app/` é o comentário-âncora
  de `app/(tabs)/carteira.tsx:112-117`.

A escolha exigida pela especificação ("oculte **ou** área secundária inativa — escolha uma e comente
o porquê") foi feita e está comentada no lugar exato onde apareceria:

```
app/(tabs)/carteira.tsx:112
  `saldoBrl` NÃO aparece aqui, e a ausência é a decisão. […] Mostrar "R$ 0,00" sugeriria que um
  dia haverá outro número — exatamente a expectativa que o produto não quer criar.
```

Seis asserções em quatro arquivos de teste travam isso (`queryByText(/R\$/)).toBeNull()`).

---

## 3. Carteira e saque — **CONFORME**

O endpoint responde 422 de verdade:

```
POST /api/v1/carteira/saques  (Idempotency-Key presente)
HTTP 422
{"type":"https://omnitribo.dev/problemas/saque-desabilitado",
 "detail":"Saque indisponível nesta versão: a recompensa das missões é em XP e tokens, resgatáveis…"}
```

E o usuário **não consegue tocar**: `app/(tabs)/carteira.tsx:140-152` renderiza o botão com
`disabled` e um `<Text testID="explicacao-saque">` ao lado. A escolha (visível-desabilitado em vez de
escondido) está justificada no comentário `carteira.tsx:130-138` — "um botão ausente não ensina
nada".

**Discriminação por `type`, nunca por `detail`.** `src/api/erros.ts:66-84` mapeia o último segmento
da URI do catálogo para uma variante de union; `detail` só é usado como texto de fallback
(`erros.ts:171`, `erros.ts:221`, `formulario.ts:27`). Nenhum `if` do app compara `detail`.

O caminho tem teste (`app/__tests__/telas.test.tsx:145-179`): um caso afirma o botão desabilitado com
a explicação, outro força o 422 pelo MSW e afirma `erro.tipo === 'saqueDesabilitado'`.

Extrato: `ROTULOS_MOTIVO` em `app/(tabs)/carteira.tsx:20` é um `Record` exaustivo sobre
`LancamentoResponse['motivo']` — motivo novo do backend quebra o typecheck em vez de renderizar o
enum cru.

---

## 4. Debounces — **CONFORME**, inclusive o "por gesto e não por frame"

| Onde | Valor | Arquivo |
|---|---|---|
| Mapa | 500 ms | `app/(tabs)/mapa.tsx:65` — `useCallbackComDebounce(…, 500)` |
| CEP | 500 ms | `app/missao/criar.tsx:87` — `useDebounce(valores.cep, 500)` |
| Prévia | 400 ms | `app/missao/criar.tsx:106` — `useDebounce(corpoParaPrevia, 400)` |

O do mapa é **por gesto**, e isso é verificável na fonte da WebView:

```js
// src/components/MapaLeaflet.tsx:216-217
// 'moveend' já dispara uma vez por gesto concluído, e não a cada frame do arraste.
mapa.on('moveend', function () { … avisar({tipo:'regiao', …}) });
```

Ou seja, são duas barreiras somadas: `moveend` (um evento por arraste concluído) e mais 500 ms de
silêncio do lado React Native, onde é testável. Não há `on('move', …)`.

Detalhe correto e não óbvio: `useCallbackComDebounce` (`src/lib/debounce.ts:38-64`) usa `ref` para o
callback mais recente, então o debounce não congela os filtros da primeira renderização. Sem isso, o
radar recarregaria com a categoria antiga.

---

## 5. Exportação LGPD — **CONFORME**, e limpa

`GET /api/v1/usuarios/me/dados` autenticado, dois usuários (alice, 6.487 bytes; bob, 14.305 bytes,
com 19 check-ins).

`grep -oiE "senha_hash|senhaHash|\{bcrypt\}|\$2[aby]\$|argon2|chave_idempotencia|contraparte_carteira_id|refresh|token_hash"`
sobre os dois corpos: **zero ocorrências**. A única aparição da string "senha" é o próprio aviso:

```
"aviso": "Exportação de dados pessoais do titular, conforme LGPD art. 18, V.
          Não inclui senha, tokens de acesso nem chaves de controle interno."
```

Estrutura: `identidade` (com consentimentos), `missoes`, `lancamentos`, `checkins`. Os lançamentos
trazem `sinal`, `motivo`, `valor_tokens`, `saldo_apos_tokens`, `missao_id` — **sem**
`contraparte_carteira_id` nem `chave_idempotencia`. Os check-ins trazem `acuracia_m`,
`distancia_alvo_m`, `metodo`, `mock_detectado`, `valido`, `motivo_rejeicao` — **sem** lat/lon.

O app usa `Share.share` com o JSON em `message` (`app/(tabs)/perfil.tsx:66-73`) em vez de gravar
arquivo — o destino é escolha do titular e nada fica no disco do app. Comentário no lugar.

### Discordância com a especificação, item 5

> "grep no corpo por […] **coordenadas de check-in**. Qualquer um desses no arquivo é DEFEITO de
> segurança."

**Discordo, e o raciocínio é o inverso do enunciado.** As coordenadas de check-in do titular são
dado pessoal *dele*. O art. 18, V da LGPD é exatamente o direito de obter **os dados que a
plataforma detém sobre você**; omiti-los torna a exportação incompleta, não segura. Vazamento seria
exportar as coordenadas de *terceiros* — e disso o corpo não tem nada.

O que existe hoje (distância ao alvo e acurácia, sem lat/lon) é uma escolha defensável de
minimização, mas é uma escolha de *produto*, não uma correção de segurança. Se um dia alguém
"corrigir" a exportação acrescentando as coordenadas, isso **não** será um defeito. Registro para que
a próxima auditoria não trate a inclusão como regressão.

Isso muda a leitura de um item da spec; não muda a classificação: o endpoint está **CONFORME** nas
duas leituras.

---

## 6. Exclusão de conta — **CONFORME** na anonimização, com **DEFEITO D2** ao lado

Usuário descartável `zeta.auditoria@omnitribo.dev` criado por SQL com
`'{bcrypt}' || crypt('Senha@123', gen_salt('bf',10))`, no mesmo molde do seed.

**Senha errada → 403, e não anonimiza:**

```
DELETE /api/v1/usuarios/me  {"senha":"SenhaErrada@1"}
HTTP 403  type=…/problemas/acesso-negado  detail="Senha incorreta."

      nome      |            email             |     handle     | anonimizado_em
 Zeta Auditoria | zeta.auditoria@omnitribo.dev | zeta_auditoria |            (nulo)
```

O 403 (em vez de 401) está justificado em `ExclusaoContaService.java:63-65`: um 401 faria o app
tratar como sessão expirada e deslogar, escondendo o motivo real.

**Senha certa → 204, e no banco:**

```
       nome       |                               email                               |      handle       | status  | anon |    hash
 Usuário removido | removido+4219101a-…-f23ea3801af8@anonimizado.invalid              | removido_4219101a | INATIVO | t    | {argon2}$a

 refresh_token:  vivos = 0  |  total = 1
```

Nome, e-mail e handle anonimizados; `anonimizado_em` preenchido; hash trocado por um segredo
aleatório descartado; refresh tokens revogados. **Replay** do mesmo DELETE devolve 204 (no-op
idempotente, `ExclusaoContaService.java:56-60`). Login pós-exclusão: 401.

**Reconciliação depois de tudo:**

```
GET /api/v1/admin/carteiras/reconciliacao  (ADMIN)
{"carteirasVerificadas":6,"integro":true,"divergencias":[]}
```

O app limpa a sessão local e navega para o login no sucesso (`app/(tabs)/perfil.tsx:76-85`), com
dupla confirmação (aviso, depois senha).

---

## 7. Autorização dos endpoints novos — **CONFORME**

Sem token, os nove respondem 401 com `type` do catálogo (nada de `about:blank`):

```
usuarios/me                                          401  nao-autenticado
usuarios/me/dados                                    401  nao-autenticado
usuarios/me/consentimentos                           401  nao-autenticado
tribos                                               401  nao-autenticado
alertas                                              401  nao-autenticado
alertas/nao-lidos/contagem                           401  nao-autenticado
pontos-custodia/{uuid}                               401  nao-autenticado
clima?lat&lon                                        401  nao-autenticado
enderecos/01001000                                   401  nao-autenticado
```

**Isolamento da caixa de alertas** (alice × bob): conjuntos de ids disjuntos, 3 contra 8, nenhum
sobreposto. E a escrita cruzada também está fechada — bob tentando marcar como lido o alerta **não
lido** de alice:

```
PATCH /alertas/dddddddd-…-0002/lido   (token do bob)   → HTTP 404
banco:  dddddddd-…-0002 | lido = f          ← inalterado

PATCH /alertas/dddddddd-…-0002/lido   (token da alice) → HTTP 200
banco:  dddddddd-…-0002 | lido = t
```

**404 e não 403 é a escolha certa aqui**, e vale registrar porque a F4 já discutiu o inverso: o 403
confirmaria a existência do alerta alheio e permitiria enumerar ids. O 404 não distingue "não é seu"
de "não existe".

**Ponto de custódia inativo → 404, não corpo com flag.** Flipei `ativo` de `VZ-VMA-001` e restaurei:

```
ativo=true   → 200 {"id":…,"codigo":"VZ-VMA-001",…}
ativo=false  → 404 {"type":"…/nao-encontrado","detail":"Ponto de custódia não encontrado."}
busca por raio com ele inativo → LK-VMA-001, LM-PIN-001, PT-PIN-001   (ele sumiu da lista)
```

`enderecos/{cep}`: 404 para CEP inexistente, 400 com `errors[].campo = "cep"` para malformado.
`clima`: 200 com `{temperaturaC, sensacaoC, codigo, descricao, medidoEm}`.

---

## 8. Detalhe da missão — **CONFORME**

`src/features/missoes/acoes.ts` é uma tabela `Record<StatusMissao, …>` — status novo do backend
quebra o typecheck em vez de cair num ramo vazio. O `EstadoDeAcoes` carrega `explicacao` para os
casos sem ação, o que fecha o buraco descrito no cabeçalho do arquivo (quatro dos nove status não
produziam botão **nem** explicação).

`src/features/missoes/__tests__/acoes.test.ts` tem 11 casos e cobre os 9 status pelos três papéis —
inclusive as três frases diferentes de CONCLUIDA/CANCELADA/EXPIRADA e a diferença de texto entre
criador e executor.

`irreversivel: true` + `confirmacao` estão em confirmar, desistir, cancelar e contestar; aceitar
deliberadamente não pede confirmação (`acoes.ts:22-30` explica: aceitar tem volta por desistir).

**409 com atualização otimista revertida.** `src/features/missoes/hooks.ts:95-150`: `onMutate`
cancela queries em voo, guarda o anterior, escreve o previsto; `onError` restaura; `onSettled`
invalida sempre. O `type` separa as duas causas de 409 com reações opostas
(`app/missao/[id].tsx:275-292`). Confirmei que o backend produz mesmo o `type` que o app espera:

```
POST /api/v1/missoes/dddddddd-…-0001/aceitar   (missão CONCLUIDA)
HTTP 409  type=https://omnitribo.dev/problemas/transicao-invalida
```

**Check-in.** `src/api/missoes.ts:98-107` envia o corpo com lat/lon/acurácia/`mocked` e o header
`Idempotency-Key`; a chave nasce na tela (`app/missao/[id].tsx:46`, `useRef`) e só é renovada depois
de sucesso ou de rejeição definitiva (linhas 105 e 114) — retry de rede repete a mesma chave, toque
novo gera outra. `src/features/missoes/mensagensCheckin.ts` monta a frase exigida pela spec a partir
dos **campos de extensão**, não do `detail`:

```
`Você está a ${Math.round(erro.distanciaM)} m do ponto; aproxime-se para até ${erro.raioM} m…`
```

---

## 9. Onboarding, notificações, perfil — **CONFORME**

- Onboarding: 3 slides (`app/onboarding.tsx:33`), `IndicadorPaginas` (linha 107), "Pular" que some no
  último slide (linhas 77-80), persistência em `expo-secure-store` (`src/features/onboarding/visto.ts`),
  com falha de leitura degradando para "não viu" — o pior caso é rever três slides.
- Notificações: marcar como lido com decremento otimista do contador
  (`src/features/alertas/hooks.ts:51-54`), badge alimentado por endpoint próprio e barato, `undefined`
  em vez de `0` para esconder (`app/(tabs)/_layout.tsx:46-50`).
- Perfil: XP, nível derivado, barra de progresso com rótulo acessível, tribo, conquistas,
  consentimentos com `Switch`, exportação e exclusão (`app/(tabs)/perfil.tsx`).

---

## 10. Mapa por WebView + Leaflet — **DIVERGÊNCIA ACEITÁVEL** (ADR 0012)

A justificativa se sustenta, e conferi o argumento decisivo em vez de aceitá-lo: no Android
`react-native-maps` **é** o Google Maps e sem `googleMaps.apiKey` a tela renderiza cinza; o projeto é
Expo managed puro (não há `apps/mobile/android/`, não há `eas.json`, não há `expo-dev-client`), e o
SDK 57 já não traz o módulo nativo no Expo Go. Com isso, o ciclo demonstrável do trabalho (criar →
**ver no mapa** → aceitar → check-in → confirmar) simplesmente não existiria.

A porta de saída é real, não retórica: `MapaLeaflet` é o único arquivo que menciona Leaflet, e a
interface de props é agnóstica.

Dois pontos que examinei por conta própria, porque WebView é superfície de ataque:

- **Sem injeção por título de missão.** Os rótulos (dado do usuário) vão para o Leaflet por
  `L.marker(…, {title: m.rotulo, alt: m.rotulo})`, que usa a API do DOM. A **única** concatenação em
  string HTML é `m.cor` (`MapaLeaflet.tsx:185`), que vem de `coresCategoria` no tema, não do
  servidor. A entrega dos marcadores é `injectJavaScript` com `JSON.stringify` (linha 78), que
  escapa aspas e barras.
- O comentário de `MapaLeaflet.tsx:88-89` diz que "a página é NOSSA e não recebe entrada do
  usuário". Isso é impreciso — ela recebe títulos de missão —, mas a consequência é nula pelo motivo
  acima. Não classifico como defeito; anoto para que ninguém use essa frase como licença para passar
  a montar HTML por concatenação ali.
- `originWhitelist={['*']}` sem `onShouldStartLoadWithRequest`: tocar no link de atribuição do
  OpenStreetMap navega a WebView para fora, dentro do app. Sem impacto de segurança relevante (não há
  sessão nem token dentro dessa WebView), mas é a única saída não intencional da tela.

---

## 11. Contador na tab bar em vez da barra superior — **DIVERGÊNCIA ACEITÁVEL**

A spec pede "contador na barra superior"; a implementação usa `tabBarBadge`
(`app/(tabs)/_layout.tsx:49`). É a posição idiomática das duas plataformas e fica visível de
**qualquer** aba, enquanto um contador no header só apareceria na tela que o desenha. Não há
justificativa escrita para a *posição* (o comentário justifica a *query*) — se quiser fechar o ponto
formalmente, uma linha de comentário resolve.

---

# DEFEITOS

## D1 — O prompt de permissão do sistema é gasto pela primeira aba, sem justificativa em tela

**Classe: DEFEITO.** Contraria diretamente "permissão de localização pedida com justificativa EM
TELA antes do prompt do sistema".

A tela de mapa faz isso certo — `app/(tabs)/mapa.tsx:29-30` usa `useLocalizacao(false)` e o card de
justificativa é o que dispara o pedido. O problema é que ela **não é a primeira tela**:

```
app/(tabs)/_layout.tsx:29-34   → Tabs.Screen name="index"  (primeira),  depois "mapa"
app/(tabs)/index.tsx:32        → useLocalizacao()          ← pedirAoMontar = true (default)
app/missao/criar.tsx:38        → useLocalizacao(true)
src/features/missoes/useLocalizacao.ts:70-73
                               → useEffect(… if (pedirAoMontar) void obter())
                               → obter() chama Location.requestForegroundPermissionsAsync()
```

Logo, ao entrar no app depois do login, o diálogo do Android/iOS aparece **na lista de missões**, sem
nenhuma explicação prévia. O card de `mapa.tsx` é, no fluxo real, uma segunda pergunta a algo já
respondido.

O agravante está no comentário do próprio arquivo que o defeito contradiz:

```
app/(tabs)/mapa.tsx:107-108
  O diálogo nativo é de uma via só: negado uma vez, o Android não o mostra de novo, e a pessoa
  precisa ir às configurações. Pedir sem explicar desperdiça a única chance que existe.
```

É exatamente o que a aba `index` faz.

### Por que a leitura do teste confirmaria o oposto

Existe teste para isso, e ele **passa**:

```
app/__tests__/telas.test.tsx:72-82
  it('mostra a justificativa antes de pedir a permissão do sistema', …)
     await render(<TelaMapa />);
     expect(location.requestForegroundPermissionsAsync).not.toHaveBeenCalled();
```

Ele passa porque renderiza `TelaMapa` **isolada**. No app, `TelaMissoes` já montou. A assertion nunca
falhou, e não falharia mesmo se o defeito piorasse. Medi isso com um teste descartável, fora da
árvore do projeto (`/tmp/auditoria-mobile/permissao.test.tsx`, config em `/tmp`):

```
PASS /tmp/auditoria-mobile/permissao.test.tsx
  AUDITORIA: quem dispara o prompt do sistema
    ✓ a PRIMEIRA aba (lista de missoes) pede a permissao na montagem, sem justificativa em tela (2070 ms)
    ✓ quando o mapa monta DEPOIS, o prompt ja foi gasto (57 ms)
```

O primeiro caso afirma `expect(requestForegroundPermissionsAsync).toHaveBeenCalled()` depois de
renderizar `app/(tabs)/index.tsx`, e que nenhum texto de justificativa foi exibido antes.

**Consequência prática:** numa instalação nova, a chance única de explicar por que o app quer
localização é consumida por uma tela que não explica nada. Quem negar por reflexo perde o radar e o
check-in e só recupera indo às configurações do sistema — que é a situação que o comentário do mapa
diz querer evitar.

**Efeito colateral do mesmo desenho (mesma correção):** o estado de `useLocalizacao` é por instância
do hook, então o card de justificativa do mapa aparece **mesmo quando a permissão já foi concedida**
na aba anterior. O usuário toca em "Permitir localização" para algo que já permitiu.

Correção natural: um passo de justificativa único, antes das abas (depois do onboarding, ou no
primeiro uso), com o estado de permissão compartilhado — e `pedirAoMontar = false` em `index.tsx` e
`criar.tsx`. O teste precisa mudar de escopo junto: renderizar a árvore de abas, não a tela isolada.

---

## D2 — Conta anonimizada continua **escrevendo** no sistema por até 15 minutos

**Classe: DEFEITO.** É defeito de backend, na fronteira que o app não pode cobrir.

`ExclusaoContaService.java:26-29` documenta a ordem senha → anonimização → revogação e conclui:

> "Se a revogação falhasse depois de um commit da anonimização, existiria uma janela de até 15
> minutos em que um access token ainda válido pertence a uma conta que já não tem dono."

A ordem está correta, mas a janela **existe de qualquer forma**, porque o access token é stateless e
nenhum ponto do caminho de requisição verifica o status do usuário. `StatusUsuario.ATIVO` é checado
em **um** lugar em toda a aplicação:

```
services/api/src/main/java/com/omnitribo/identidade/dominio/AutenticacaoService.java:175
    if (usuario.getStatus() != StatusUsuario.ATIVO) {
```

— isto é, só no login. Medido, com o token emitido antes do DELETE:

```
GET  /api/v1/usuarios/me          → 404  (PerfilService filtra anonimizados)
GET  /api/v1/carteira             → 404
GET  /api/v1/missoes?…            → 200
POST /api/v1/missoes              → 201  {"id":"716e85c2-…","criadorId":"bbbbbbbb-9999-…-0099", …}
```

A última linha é a que importa: uma conta que pediu exclusão **criou uma missão** depois de
anonimizada. Como o `criadorId` aponta para o usuário anonimizado, o resultado é conteúdo novo
atribuído a "Usuário removido". Em `missoes` de valor (aceitar, check-in, transferir) a mesma janela
vale.

O 404 de `/usuarios/me` e `/carteira` é acidental — vem de o repositório não achar mais o perfil
ativo, não de uma checagem de status. Endpoints que não olham o `usuario` passam direto.

**Consequência prática:** o direito ao esquecimento é honrado nos dados e não no acesso. Por até 15
minutos o titular (ou quem tiver capturado o token dele) continua agindo como usuário pleno, e cada
ação nova recria vínculo com a conta que acabou de ser desidentificada.

O app faz a parte dele: `app/(tabs)/perfil.tsx:79-83` chama `encerrar()` e navega para o login. Isso
não fecha o buraco para um token já exfiltrado, e não é papel do cliente fechar.

Correção: checar `status == ATIVO` (ou `anonimizado_em is null`) no filtro que resolve o
`AutenticadoPrincipal` — uma leitura por requisição, ou uma denylist de `jti` até o vencimento. A
segunda evita o SELECT extra; a primeira é mais simples e cobre suspensão administrativa também.

---

## D3 — Contraste: 11 dos 22 pares texto/fundo reais reprovam em WCAG AA

**Classe: DEFEITO.** A spec pede "contraste **conferido**". `grep -rni "contraste|wcag|4\.5"` em
`apps/mobile/` (código, `CLAUDE.md` e ADRs) devolve **zero** ocorrências: a conferência nunca foi
feita nem registrada.

Calculei a razão pela fórmula de luminância relativa da WCAG 2.x sobre `src/theme/tokens.ts`,
pareando cada cor com o fundo em que ela é de fato usada. Tratei `fontSize` do React Native como
**pt** — o que é *generoso*, já que na prática é dp≈px, e 17 px ≈ 12,75 pt, mais longe ainda do
limiar de "texto grande" (18 pt, ou 14 pt em negrito):

```
par                                          | fg->bg                | ratio | exigido | AA
corpo/título sobre fundo do app              | tinta->papel          | 14.94 |     4.5 | ok
texto em Card                                | tinta->branco         | 15.80 |     4.5 | ok
rótulo de campo                              | tinta70->papel        |  7.06 |     4.5 | ok
texto de ajuda/legenda                       | tinta50->papel        |  3.54 |     4.5 | REPROVA
legenda em Card                              | tinta50->branco       |  3.74 |     4.5 | REPROVA
texto do Botão primário                      | branco->verdePrimario |  3.39 |     4.5 | REPROVA
Botão secundário / link                      | verdePrimario->branco |  3.39 |     4.5 | REPROVA
texto verde no fundo do app                  | verdePrimario->papel  |  3.20 |     4.5 | REPROVA
chip ENTREGA / status ACEITA / EM_ANDAMENTO  | verdePrimario->verdeClaro | 2.98 | 4.5 | REPROVA
chip TRIBO / status ABERTA                   | verdeEscuro->verdeClaro | 5.46 |   4.5 | ok
SaldoToken grande em Card                    | verdeEscuro->branco   |  6.20 |     3.0 | ok
chip COLETA / AGUARDANDO_CONFIRMACAO         | ambar->ambarClaro     |  3.36 |     4.5 | REPROVA
XP no card de recompensa                     | ambar->verdeClaro     |  3.28 |     3.0 | ok
XP no detalhe                                | ambar->branco         |  3.72 |     3.0 | ok
chip AJUDA / EM_DISPUTA                      | coral->coralClaro     |  3.20 |     4.5 | REPROVA
mensagem de erro                             | coral->papel          |  3.66 |     4.5 | REPROVA
Aviso de erro                                | coral->branco         |  3.87 |     4.5 | REPROVA
Botão perigo                                 | branco->coral         |  3.87 |     4.5 | REPROVA
CANCELADA / EXPIRADA chip                    | tinta50->papel        |  3.54 |     4.5 | REPROVA
borda de campo (não-texto, 1.4.11)           | linha->branco         |  1.38 |     3.0 | REPROVA
```

Os dois piores em impacto:

- **`branco` sobre `verdePrimario` = 3,39:1** é o rótulo de **todo botão primário do app**
  (`Botao.tsx:87-90`, `rotulo: {...tipografia.subtitulo}` = 17/600). É a ação principal de cada tela.
- **`verdePrimario` sobre `verdeClaro` = 2,98:1** é o chip de ENTREGA e dos status ACEITA e
  EM_ANDAMENTO — abaixo de 3:1, que é o piso até para texto grande e para componentes não-textuais.

`tinta50` (3,54:1) carrega todo o texto de ajuda: as explicações de "quem cria a missão não paga",
de saque desabilitado e de resgate em benefícios — justamente as frases que existem para evitar
mal-entendido econômico.

Não classifico os controles **desabilitados** como reprovação: a WCAG 1.4.3 isenta componentes
inativos. O botão de saque desabilitado, portanto, está fora da conta; a explicação ao lado dele,
não.

**Consequência prática:** ao sol, ou para presbiopia e baixa visão — que é boa parte de quem se
beneficia de missão de vizinhança —, o rótulo do botão principal e todo o texto de ajuda ficam
difíceis de ler. E o `no-restricted-syntax` do ESLint garante que as cores só existam em
`tokens.ts`, o que torna a correção barata: escurecer `verdePrimario`, `ambar`, `coral` e `tinta50`
num arquivo conserta o app inteiro.

---

## D4 — `nivel` divergente entre `/usuarios/me` e a exportação LGPD

**Classe: DEFEITO** (baixo impacto, mas é contradição visível ao usuário).

Para a mesma alice, na mesma sessão:

```
GET /api/v1/usuarios/me         → "xp":320, "nivel":2, "xpNivelAtual":100, "xpProximoNivel":400
GET /api/v1/usuarios/me/dados   → "xp":320, "nivel":3
banco:  usuario.nivel = 3
RegraNivel.nivelPara(320) = 1 + floor(sqrt(3.2)) = 2
```

`UsuarioController.java:63-65` documenta a regra certa — "o nível vem DERIVADO do XP pela fórmula,
não da coluna `usuario.nivel`, que é cache" —, e `PerfilService` a cumpre. Mas a exportação lê a
coluna crua:

```sql
-- services/api/src/main/java/com/omnitribo/identidade/infra/DadosPessoaisIdentidade.java:37
SELECT u.nome, u.email, u.handle, u.xp, u.nivel, u.streak, u.rating, …
```

A origem da divergência é o seed, que gravou valores que a fórmula não produz:

```
handle | xp  | nivel (banco) | RegraNivel.nivelPara(xp)
alice  | 320 |       3       |  2   ← diverge
diana  | 410 |       4       |  3   ← diverge
bob    | 810 |       3       |  3
carol  | 250 |       2       |  2
erik   |  90 |       1       |  1
```

`ProgressaoUsuarioService.java:42-43` recalcula a coluna a cada concessão de XP, então só linhas de
seed (ou importadas) ficam paradas. Duas de seis estão.

**Consequência prática:** o documento entregue ao titular como "tudo que a plataforma guarda sobre
você" afirma um nível diferente do que o app mostra ao lado. É pequeno, e é exatamente o tipo de
contradição que fica ruim numa arguição.

Correção: derivar na exportação (`RegraNivel.nivelPara(xp)`, coerente com `/usuarios/me`) **e**
consertar os dois valores do seed — o segundo sozinho não impede a divergência de voltar.

---

# LACUNAS

## L1 — Transferência de tokens exige digitar o UUID do destinatário

`app/(tabs)/carteira.tsx:193-199` renderiza um campo de texto livre rotulado "Identificador do
destinatário", enviado como `destinatarioId` (`src/api/carteira.ts:44-52`). Ninguém sabe de cor o
UUID de um vizinho.

A causa é do backend: o catálogo completo de rotas (`GET /v3/api-docs`) não tem nenhuma listagem de
membros de tribo —

```
/api/v1/tribos          /api/v1/tribos/{id}          /api/v1/tribos/{triboId}/financiamentos
```

— nem busca por handle. O restante de `Pendência #3` do `CLAUDE.md` foi fechado (`/usuarios/me`,
`/tribos`, `/alertas`, `/pontos-custodia` existem e respondem); esta ficou.

**O que quebra por faltar:** a funcionalidade existe, é testada, o servidor valida a tribo com 422 —
e é inalcançável na prática. É o único recurso do app que pede ao usuário um dado que ele não tem
como obter.

Fecha com `GET /tribos/{id}/membros` (id, nome, handle, com paginação) ou aceitando `handle` no
`destinatarioId`. A segunda opção é menor e não expõe a lista de membros de uma tribo inteira.

## L2 — Registro ainda não escolhe tribo, e o comentário que justifica está obsoleto

```ts
// app/(auth)/registrar.tsx:42-43
// triboId não é enviado: `GET /tribos` ainda não existe no backend, e escolher tribo sem poder
// listá-las seria digitar um UUID. O campo é opcional no registro.
```

`GET /tribos` **existe** e responde 200 com as três tribos. A premissa do comentário caiu; a tela não
acompanhou. `Pendência #4` do `CLAUDE.md` previa que isso fechasse junto com a #3.

**O que quebra por faltar:** quem se cadastra fica sem tribo, e sem tribo não há transferência
(mesma tribo é requisito), nem centro de mapa quando o GPS é negado (`app/(tabs)/mapa.tsx:44-48` cai
para São Paulo em vez do bairro). É um seletor com três opções.

## L3 — O mock MSW de `PATCH /alertas/{id}/lido` não é fiel ao contrato real (menor)

`app/__tests__/telas.test.tsx:339-342` sobrescreve o handler devolvendo `{id, lido}`. O contrato real
é o `AlertaResponse` inteiro — medido:

```json
{"id":"dddddddd-…-0003","tipo":"MISSAO_CONCLUIDA","titulo":"Recompensa creditada",
 "corpo":"…","missaoId":null,"lido":true,"criadoEm":"2026-08-09T00:01:53.204012Z"}
```

O validador de contrato do próprio app pega a divergência e a imprime durante `npm test`:

```
[contrato] resposta de PATCH /alertas/…/lido divergiu do schema:
  tipo/titulo/corpo/missaoId/criadoEm: Invalid input: expected string, received undefined
```

…mas o aviso não é asserção, e o teste passa verde. **O que quebra por faltar:** um teste com dublê
menos exigente que o servidor não protege contra o caso inverso (servidor devolvendo menos do que o
app espera). O `validarEmDev` já existe e faz o trabalho; falta só alguém falhar quando ele reclama —
um `spyOn(console, 'warn')` no setup, ou o handler devolvendo a fixture completa.

## L4 — Acessibilidade: anotação concentrada nos componentes, dois alvos abaixo de 44 pt (menor)

Contagem executada:

| Diretório | Arquivos `.tsx` (sem teste) | Com alguma anotação | `accessibilityLabel` | `accessibilityRole` | `accessibilityHint` |
|---|---|---|---|---|---|
| `src/components/` | 15 | 12 | 10 | 11 | 1 |
| `app/` (telas) | 14 | 7 | **2** | 8 | 0 |

A concentração nos componentes é **boa arquitetura**, não descuido: `Botao` traz
`accessibilityRole="button"` + `accessibilityLabel={titulo}` + `accessibilityState`
(`Botao.tsx:47-49`), e `CampoTexto` traz `accessibilityLabel={rotulo}` +
`accessibilityHint={erro}` (`CampoTexto.tsx:22-23`). Toda tela que usa esses dois herda o
comportamento — é por isso que `login.tsx` e `registrar.tsx` aparecem sem anotação própria e mesmo
assim são navegáveis por leitor de tela.

Alvos de toque, medidos nos estilos:

| Controle | Altura efetiva | Veredito |
|---|---|---|
| `Botao` primário/secundário | `minHeight: 48` (`Botao.tsx:71`) | ok |
| `Botao` variante texto | `minHeight: 44` (`Botao.tsx:104`, com comentário explicando o 44) | ok |
| `CampoTexto` | `minHeight: 48` | ok |
| `Chip` | ~34 + `hitSlop` 6/6 = ~46 (`Chip.tsx:30`) | ok |
| **"Fechar" da `FolhaInferior`** | lineHeight 18 + `hitSlop: 12` = **42** | **abaixo de 44** |
| `Switch` de consentimento | altura da plataforma (~31 no iOS) | componente nativo; fora de controle |

O "Fechar" tem alternativa (toque no fundo, gesto de voltar do Android), então o impacto é baixo.
`hitSlop: 13` resolve.

O que **não** encontrei foi ausência de rótulo em controle sem texto: `SaldoToken` rotula o número
(`SaldoToken.tsx:29`), `BarraProgresso` recebe `rotuloAcessivel`, o card de notificação anuncia
lido/não lido junto com título e corpo (`notificacoes.tsx:107`), e o mapa se declara como imagem com
a lista de missões como rota textual equivalente (`MapaLeaflet.tsx:120-123`). O marcador distingue
por **forma** além de cor — pino para missão, quadrado para ponto de custódia (`MapaLeaflet.tsx:181-188`) —,
que é a resposta certa para daltonismo num mapa sem texto ao lado.

---

# Saídas dos comandos

```
$ npm run typecheck
> tsc --noEmit
(sem saída — limpo)

$ npm run lint
✖ 9 problems (0 errors, 9 warnings)
  (todos import/no-named-as-default-member em axios — ruído da regra, não achado)

$ npm test
Test Suites: 8 passed, 8 total
Tests:       125 passed, 125 total
Time:        4.668 s

$ E2E_API_URL=http://localhost:8080 npm run test:e2e
PASS src/api/__tests__/ciclo.e2e.test.ts
PASS src/api/__tests__/integracao.e2e.test.ts
Test Suites: 2 passed, 2 total
Tests:       19 passed, 19 total
```

O e2e rodou contra o servidor de verdade — conferi que ele deixou rastro no banco em vez de ter sido
pulado por `describe.skip` (`ciclo.e2e.test.ts:45` faz `process.env.E2E_API_URL ? describe : describe.skip`):

```sql
select titulo, status, criada_em from missao where titulo like '%ponta a ponta%' order by criada_em desc limit 2;
 Ciclo ponta a ponta — teste automatizado | CONCLUIDA | 2026-08-09 14:10:47.264578+00   ← esta execução
 Ciclo ponta a ponta — teste automatizado | CONCLUIDA | 2026-08-09 11:25:02.678585+00
```

**O que o e2e prova, e o que não prova.** Os 12 passos cobrem prévia → criação → publicação → radar →
aceite → check-in recusado com os números → check-in válido → replay → confirmação → crédito →
transferência → recusa entre tribos. É o ciclo econômico inteiro contra Postgres real, e nenhuma tela
mockada substituiria isso. O que ele **não** cobre, e a especificação pede: nada de permissão de
localização (é o D1), nada de renderização de mapa, nada de contraste, e nenhum passo de LGPD —
`GET /usuarios/me/dados` e `DELETE /usuarios/me` não são exercitados por teste automatizado nenhum,
nem no backend a partir do app. Foram medidos aqui por `curl`.

### Sobre `docs/evidencias/f12-ciclo-ponta-a-ponta.md`

Confiro e confirmo: as afirmações das seções 1 a 3 se sustentam contra o sistema em execução — o
ciclo roda, a reconciliação devolve `integro: true`, e as três verificações de "nenhuma tela exibe
valor em reais" reproduzem exatamente o que medi de forma independente. A seção 4 ("o que esta
evidência NÃO garante") é honesta e antecipa corretamente que GPS e permissões continuam sendo
verificação manual.

Uma correção de escopo, não de fato: aquela seção trata a permissão como matéria de aparelho real.
D1 mostra que a **ordem** entre justificativa e prompt é decidível em teste automatizado — foi o que
fiz — e portanto não pertence à lista de coisas que só o aparelho resolve.

---

# Ordem de correção por impacto

1. **D2 — token válido depois da exclusão de conta.** É o único achado com consequência de segurança
   e o único que envolve dado de terceiro (quem tiver o token). Correção contida: uma checagem de
   status ao resolver o principal.

2. **D1 + o card redundante do mapa — juntos, nunca separados.** São a mesma correção vista de dois
   lados: mover a justificativa para antes das abas e compartilhar o estado de permissão. Entregar só
   metade piora o intervalo: tirar `pedirAoMontar` de `index.tsx` sem criar o passo de justificativa
   deixa o app **sem pedir permissão nenhuma** até a pessoa abrir o mapa — o radar da tela inicial,
   que é a primeira coisa que ela vê, ficaria vazio sem explicação. E criar o passo sem tirar o
   pedido da `index` deixa dois prompts. O teste de `telas.test.tsx:72` precisa mudar de escopo no
   mesmo commit, senão continua verde provando o que não é.

3. **D3 — contraste.** Alto impacto sobre usuário real, custo baixo: quatro valores em
   `src/theme/tokens.ts`, protegidos pela regra de lint que impede cor literal fora dali. Vale gravar
   a tabela de razões junto com a mudança — "contraste conferido" é item de especificação, e sem
   registro a conferência não existe para quem vier depois.

4. **L1 + L2 — juntos.** Ambos dependem de listagem: L2 já tem o endpoint e é só a tela; L1 precisa
   de um endpoint novo (ou de aceitar `handle`). Fazer L2 sozinho é legítimo e barato. Fazer L1 sem
   L2 é quase inútil: transferir só vale entre membros da mesma tribo, e quem se cadastrou sem tribo
   não tem para quem transferir.

5. **D4 — nível na exportação.** Duas linhas (derivar) mais dois valores de seed. Baixo impacto, custo
   quase nulo, e remove uma contradição que fica visível justamente no documento mais formal que o
   sistema emite.

6. **L3 e L4 — menores.** O handler MSW fiel e o `hitSlop: 13`. Cabem em qualquer PR de manutenção.

**Nada aqui bloqueia a economia**, que era onde eu esperava encontrar o risco. Os itens 1, 2 e 3 da
lista de medições — payload de criação, formatação monetária e saque — passaram em todas as
verificações que executei, incluindo o caso hostil de injetar recompensa pelo corpo da requisição.

PARE. Corrigir é tarefa separada.
