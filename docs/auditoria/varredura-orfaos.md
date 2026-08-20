# Varredura de órfãos, comentários falsos e reservas — `services/api` e `apps/mobile`

**Data:** 2026-08-20 · **Modo:** auditoria — **nenhum arquivo do projeto foi alterado**
(`git status --porcelain` acusa apenas `CLAUDE.md`, modificado por outra tarefa desta sessão, e este
relatório novo).

Motivação: o projeto já produziu três achados da mesma classe — `useSacar()` órfão defendido por um
comentário falso, `transferenciaSchema` escrito e nunca importado, e dois comentários afirmando
garantias inexistentes (`erros.test.ts`, `registrar.tsx`). Esta varredura pergunta o que sobrou.

---

## Como foi medido

Nada aqui vem de leitura de código sozinha. As quatro fontes:

| # | Instrumento | O que produz |
|---|---|---|
| 1 | Referência textual por símbolo: para cada tipo/método declarado, `grep -rlw` no resto de `main/` e, separadamente, em `test/` | separa "morto" de "vivo só em teste" |
| 2 | **JaCoCo do último `verify`** (`services/api/target/site/jacoco/jacoco.xml`, 2026-08-17 21:03; INSTRUCTION 91,9%, BRANCH 75,1% — bate com o que o `CLAUDE.md` registra, logo é execução cheia e não parcial) | quais métodos **nunca executaram**, que é evidência de execução e não de leitura |
| 3 | Varredura de chaves `app.*` do `application.yml` (100 folhas) contra `@Value`/records de parâmetros | configuração órfã |
| 4 | Conferência de cada comentário que AFIRMA garantia (`tempo constante`, `impede`, `garante`, `não existe`, `é atômico`, `at-least-once`, `há teste`) contra o código adjacente — 103 ocorrências em Java, 40 no mobile | comentário falso |

O cruzamento de (1) com (2) é o que separa achado de ruído: um método pode não ter referência
textual e ainda assim rodar (bean do Spring, handler de exceção), e pode ter referência e nunca
rodar.

---

## Sumário

| Classe | Quantidade |
|---|---|
| **COMENTÁRIO FALSO** | 9 |
| **ÓRFÃO** | 8 |
| **RESERVADO-INTENCIONAL** | 8 |
| **FALSO POSITIVO** (afirmação conferida e verdadeira, ou símbolo vivo por via não textual) | 9 grupos |
| Fora das quatro classes, surgido da medição | 4 |

O achado mais grave não é um símbolo morto: é **um mecanismo de intervenção afirmado em três
arquivos e que não tem instrumento nenhum** (§1.1).

---

## 1. COMENTÁRIO FALSO

### 1.1 A outbox descarta evento em silêncio, e três lugares dizem o contrário — **risco alto**

- `services/api/src/main/java/com/omnitribo/compartilhado/api/PublicadorEventos.java:25`
  — *"um processo separado a entrega **com retry até conseguir**"*
- `services/api/src/main/java/com/omnitribo/notificacoes/dominio/DespachanteAlertaService.java:24`
  — *"a garantia de entrega **at-least-once** não muda"*
- `services/api/src/main/resources/application.yml:194`
  — *"depois disso o evento sai do lote e **espera intervenção**"*

O que o código faz: `OutboxRepository.buscarPendentesParaPublicar` filtra por
`o.tentativas < :maximoTentativas` (`OutboxRepository.java:37`), com `app.outbox.maximo-tentativas:
5`. Depois da quinta falha o evento **nunca mais é lido por ninguém**. O próprio teste sela o
comportamento: `DrenadorOutboxServiceTest.eventoQueEsgotouAsTentativasSaiDoLote:118-126`.

Por que é comentário falso e não decisão discutível:

1. **"até conseguir" é falso** — a entrega é limitada a 5 tentativas.
2. **"at-least-once" é falso no caso que importa** — at-least-once significa *ao menos uma*; um
   evento esgotado tem **zero** entregas.
3. **"espera intervenção" descreve um instrumento que não existe.** Varri o repositório: não há
   consulta por `tentativas >= maximo`, não há endpoint de administração de outbox, não há métrica,
   não há job de relatório. `OutboxRepository` tem **uma única** query, a do lote. A única pista é
   um `log.warn` na tentativa que falhou (`DrenadorOutboxService.java:84-90`) — que ninguém coleta,
   porque Prometheus/Grafana foram cortados do MVP de propósito.

Consequência concreta: um `MissaoConcluida` cujo payload o despachante não consiga tratar falha 5
vezes e some. O executor teve o token creditado e **nunca é avisado**, e não existe lugar onde esse
fato apareça. É o mesmo formato do defeito econômico que o `EVOLUCAO-ARQUITETURAL.md` narra —
consistência intacta, fato perdido, e o endpoint que existiria para achá-lo respondendo verde.

> Correção mínima é de **comentário**: dizer "até 5 tentativas, e depois o evento é abandonado sem
> aviso". Correção real é decidir o instrumento (uma query, como a de §1.2, ou um contador). Não
> decida por mim: muda o contrato de entrega.

### 1.2 `PontoCustodiaService` afirma que nada movimenta `ocupacao` — **risco alto**

`services/api/src/main/java/com/omnitribo/logistica/dominio/PontoCustodiaService.java:20-24`:

> *"nada movimenta `ocupacao` **ainda**. O fluxo de entrega que a incrementaria é da F8 e **não
> existe** — a coluna vem do seed e fica parada. A entidade também não tem mais
> `incrementarOcupacao`."*

As três cláusulas estão erradas hoje:

| Afirmação | Refutação | Evidência de execução |
|---|---|---|
| "nada movimenta `ocupacao`" | `PontoCustodia.java:120` (`ocupacao++`) e `:133` (`ocupacao--`) | JaCoCo: `registrarEntrada` 10 de 21 instruções cobertas, `registrarSaida` **10 de 10** |
| "o fluxo é da F8 e não existe" | F8 está implementada: `EntregaFalidaService` (webhook) chama a entrada, `BaixaCustodiaService` chama a saída | idem |
| "não tem mais `incrementarOcupacao`" | verdade só no NOME: o método existe como `registrarEntrada` (`PontoCustodia.java:116`) | idem |

O agravante é que `PontoCustodia.java:104-107` aponta de volta para este javadoc como se ele
continuasse valendo (*"O javadoc de `PontoCustodiaService` guardou o lugar desta chamada […] e
continua valendo"*). Metade dele vale — a parte "não deve virar endpoint". A outra metade descreve
um sistema que não existe mais, e é a metade que fala de **lock e concorrência**: quem ler isto
acreditando pode concluir que não há caminho de escrita concorrente sobre `ocupacao` e mexer no
`FOR UPDATE` de `PontoCustodiaRepository.java:17`.

### 1.3 `AutenticadoPrincipal.deClaims` — javadoc nomeia um chamador que não existe — **risco médio**

`services/api/src/main/java/com/omnitribo/identidade/api/AutenticadoPrincipal.java:13-17` diz
*"Usado pelo JwtAuthFilter (compartilhado/infra/)"*.

O `JwtAuthFilter` não o usa desde o ADR 0016: ele obtém o principal pronto de `ConsultaSessao`
(`JwtAuthFilter.java:60-74`) e só chama `principal.autoridade()`. `grep -rn deClaims` acha **apenas
a declaração**. JaCoCo: `deClaims` com **0 de 8 instruções** — nunca executou. Ver §2.2.

O risco é direcional: quem lê o javadoc e precisa montar um principal é levado a reconstruí-lo a
partir dos claims do JWT, que é exatamente o caminho que o ADR 0016 fechou para que papel e
anonimização fossem reconferidos a cada requisição.

### 1.4 `MissaoRepository.potesImobilizados` — a visibilidade afirmada não existe — **risco alto**

`services/api/src/main/java/com/omnitribo/missoes/infra/MissaoRepository.java:174-181`:

> *"Reconciliação compara ledger com projeção […] quem quebra é a CONSERVAÇÃO […] **Esta consulta
> existe para dar visibilidade a essa diferença**."*

Nada a chama. `grep -rn potesImobilizados` em `main/` e `test/` devolve só a declaração
(`:192`). E o **ADR 0015, seção Consequências, linha 71**, registra como consequência aceita:
*"`MissaoRepository.potesImobilizados` dá visibilidade ao dinheiro que a reconciliação não acha."*

Ou seja: um ADR afirma uma capacidade operacional que o sistema não tem. É o formato exato do
`useSacar()` — o artefato existe, o comentário o defende, e o efeito prometido é zero. Ver §2.1.

### 1.5 `EntregaFalidaService.porRastreio` diz existir "para teste", e nenhum teste o usa — **risco baixo**

`services/api/src/main/java/com/omnitribo/logistica/dominio/EntregaFalidaService.java:242-244`:
*"Só para leitura em teste e diagnóstico."* Zero referências em `test/`, zero em `main/`, e JaCoCo
confirma **0 de 6 instruções**. "Diagnóstico" também não se sustenta: não há endpoint nem console
que o alcance. Ver §2.3.

### 1.6 `BASE_TESTE` diz ser "a base usada nos manipuladores", e não é — **risco baixo**

`apps/mobile/src/testes/servidor.ts:15-16` documenta a constante como *"Base usada nos
manipuladores"*. Os manipuladores declaram a **sua própria** cópia privada do mesmo literal:
`apps/mobile/src/testes/manipuladores.ts:20` (`const BASE = 'http://api.teste/api/v1'`). A
exportada não é importada em lugar nenhum. Duas fontes para o mesmo valor, e a que o comentário
descreve como canônica é a morta — trocar a URL de teste em `servidor.ts` não teria efeito nenhum e
o erro apareceria como MSW não interceptando.

### 1.7 `missaoDeTerceiro` afirma proteger a suíte, e nenhum teste a usa — **risco médio**

`apps/mobile/src/testes/fixtures.ts:84-88`:

> *"Existe como fixture SEPARADA […] **Sem esta fixture, a suíte inteira passava verde contra um
> contrato que o servidor não devolve mais**."*

A fixture (`:89`) não é importada por nenhum teste — conferido em `src/**` e `app/**`, incluindo
`__tests__`. A proteção descrita é precisamente o que **continua não existindo**: nenhum teste
exercita a forma recortada (`cep`/`logradouro` nulos, coordenada com 3 casas) que
`GET /missoes`, `/missoes/proximas` e o detalhe de missão alheia devolvem. Esta é a irmã gêmea do
`useSacar()`: o comentário não descreve o código, descreve a intenção de quem o escreveu.

### 1.8 Javadoc órfão em `DespachanteAlertaService` — **risco baixo**

`notificacoes/dominio/DespachanteAlertaService.java:216-222` é o javadoc de `gravarPontoLotado`
(*"Alerta GLOBAL — `usuario_id` nulo…"*), mas o método está em `:258`. Entre os dois há **um segundo
bloco javadoc** (`:224-231`) que documenta `prioridadeDe`. Dois javadocs consecutivos: a ferramenta
associa só o de baixo, e quem lê o arquivo encontra a explicação do alerta global colada em cima de
uma função de mapear faixa de risco. Nada quebra; a documentação simplesmente não está onde diz que
está.

### 1.9 ADR 0012 afirma "não há `eas.json`" — **risco baixo, e é registro histórico**

`docs/adr/0012-mapa-por-webview-e-leaflet.md:20-21` sustenta a decisão do mapa em, entre outros
fatos, *"Não há `eas.json`, não há `expo-dev-client`"*. O arquivo passou a existir na F13
(`apps/mobile/eas.json`), documentado em `apps/mobile/README.md:127` e declarado como **configurado
e não executado** em `docs/qualidade/verificacao-2026-08-16.md:96`.

Classifico como falso **com ressalva**: ADR é registro datado, e reescrever contexto de ADR apaga a
razão da decisão. A correção adequada é uma nota de rodapé ("em 2026-08-16 passou a existir um
`eas.json`; a decisão não muda porque a demonstração segue no Expo Go"), não uma edição do texto.
O mesmo vale para `docs/auditoria/mobile-completo.md:407`, que é uma auditoria datada — ali não
mexeria em nada.

---

## 2. ÓRFÃO

Símbolo exportado/público sem nenhum consumidor. Onde havia relatório de cobertura, a coluna
"JaCoCo" traz a medição do último `verify`.

| # | Símbolo | Local | Consumidores | JaCoCo |
|---|---|---|---|---|
| 2.1 | `MissaoRepository.potesImobilizados` | `missoes/infra/MissaoRepository.java:192` | nenhum (nem teste) | — (proxy de repositório) |
| 2.2 | `AutenticadoPrincipal.deClaims` | `identidade/api/AutenticadoPrincipal.java:18` | nenhum | **0/8 instr.** |
| 2.3 | `EntregaFalidaService.porRastreio` | `logistica/dominio/EntregaFalidaService.java:244` | nenhum | **0/6 instr.** |
| 2.4 | `EntregaFalida.foiConvertida()` | `logistica/dominio/EntregaFalida.java:242` | nenhum | **0/7 instr.** |
| 2.5 | `listarTribos()` | `apps/mobile/src/api/lugares.ts:20` | nenhum (nem teste) | — |
| 2.6 | `missaoDeTerceiro()` | `apps/mobile/src/testes/fixtures.ts:89` | nenhum | — |
| 2.7 | `BASE_TESTE` | `apps/mobile/src/testes/servidor.ts:16` | nenhum | — |
| 2.8 | 20 arquivos `.gitkeep` em diretórios já povoados | `services/api/src/main/java/com/omnitribo/*/{api,dominio,infra}/.gitkeep` (19) e `tools/dataset/.gitkeep` | — | — |

Notas sobre três deles:

- **2.4** `foiConvertida()` é o único dos três predicados de `EntregaFalida` sem chamador —
  `foiRecusada()` e `saiuDaCustodia()` são usados. Sugere que a decisão "já virou missão?" passou a
  ser tomada por outro caminho e o predicado ficou.
- **2.5** `listarTribos()` é cliente de um endpoint que existe e funciona (`GET /api/v1/tribos`), e
  o app nunca o chama. Vale registrar que **isto não resolve a Pendência #3** do `CLAUDE.md` (a
  transferência que exige digitar UUID): aquilo pede listar *membros* de uma tribo, e este endpoint
  lista *tribos*. São coisas diferentes; o órfão aqui é só um cliente sem tela.
- **2.8** Dos 21 `.gitkeep` do repositório, **um** ainda faz o seu trabalho: `tools/seed/.gitkeep`,
  no único diretório de fato vazio. Os outros 20 estão em diretórios com 1 a 24 arquivos.

---

## 3. RESERVADO-INTENCIONAL

Vazio ou sem consumidor **e declarado como tal no próprio lugar**. Nenhum destes é achado; estão
aqui para que a próxima varredura não os reabra.

| Item | Onde a reserva está declarada |
|---|---|
| `tools/seed/` vazio + alvo `make seed` que não executa nada | `Makefile:50`, `CLAUDE.md` §Comandos |
| `StatusUsuario.SUSPENSO` e `.BANIDO` | `identidade/dominio/StatusUsuario.java:20-21` e `:28-30` — *"RESERVADO. Nada atribui"* |
| `jti` no access token, sem blocklist | `compartilhado/infra/JwtService.java:56-61` — *"ATENÇÃO: nada o verifica hoje […] é uma opção mantida aberta, não uma defesa"* |
| `SaqueService` + `sacar()` no mobile (usado só por teste do 422) | `carteira/dominio/SaqueService.java:56`; `apps/mobile/src/features/carteira/hooks.ts:49-56`; `app/(tabs)/carteira.tsx:163-164` |
| `CacheMissoesProximas.invalidarAgora()` e `tamanho()` | `missoes/infra/CacheMissoesProximas.java:70` e `:74` — *"Existe para o arrange de teste, não para produção"* |
| `_limparRotacaoEmVoo()` | `apps/mobile/src/api/cliente.ts:130` — *"Só para teste"*; único consumidor é `src/api/__tests__/refresh.test.ts:17` |
| `dispositivo.push_token` e `dispositivo.plataforma` | `V2__identidade.sql:59-66`; inércia declarada no `CLAUDE.md` (§Escopo) |
| `apps/mobile/eas.json` | `docs/qualidade/verificacao-2026-08-16.md:96` — *"configurado e não executado"* |

Os dois seams de teste em código de produção (`invalidarAgora`, `_limparRotacaoEmVoo`) merecem uma
observação, não uma correção: o javadoc de `PublicadorEventos.java:36-39` argumenta que provar
atomicidade *"exigiria um seam de produção — que é exatamente o que não se deve fazer"*. A doutrina
declarada e a prática divergem em dois pontos pequenos e documentados. É defensável em banca desde
que você saiba que a pergunta existe.

---

## 4. FALSO POSITIVO

Conferido e **verdadeiro**, ou símbolo vivo por via que o `grep` não vê. Registro para não gastar a
próxima varredura de novo com eles.

1. **`HmacWebhookFilter.java:58`, "a comparação é em tempo constante"** — verdadeiro:
   `MessageDigest.isEqual` em `:181`. E a afirmação vizinha *"toda falha é o mesmo 401"* também se
   sustenta: `recusar()` (`:203-219`) escreve sempre o mesmo `detail` e o mesmo `TipoProblema`; o
   motivo distinto vai só para o `log.warn`.
2. **`SecurityConfig.java:225-227`**, X-Frame-Options DENY e nosniff — as chamadas existem
   (`.frameOptions(f -> f.deny())`, `.contentTypeOptions`).
3. **`SecurityConfig.java:126`**, *"`when-authorized` garante que o anônimo veja apenas
   `{"status":"UP"}`"* — `application.yml:45` confirma `show-details: when-authorized`.
4. **`catalogo.ts:26`, "Há teste sobre isso"** — existe e é específico:
   `src/features/beneficios/__tests__/catalogo.test.ts:34-40` reprova qualquer `R$`/`reais` em título
   ou descrição.
5. **`fixtures.ts:42`, "há teste garantindo que ignora"** (o `valorBrl: 0`) — existe:
   `app/__tests__/missoes.test.tsx:126-138`, e a asserção é a negativa, como o comentário diz.
6. **15 classes Java sem referência textual** — `PingController`, `ReconciliacaoController`,
   `PrevisaoFalhaController`, os cinco `DadosPessoais*` (plugins da porta `DadosPessoaisDoUsuario`,
   injetados como lista), `PublicadorOutbox`, `DrenadorOutboxJob`, `ProvisionamentoCarteiraService`,
   `ConsultaConsentimentoService`, `ProgressaoUsuarioService`, `FinanciamentoCarteiraService`,
   `ClienteOpenMeteo` e as `*Config`. Todas vivas: são beans resolvidos por interface, por anotação
   ou por agendamento.
7. **Exports do mobile "sem uso"** que são usados no próprio arquivo — `ehErroApi`
   (`erros.ts:161`), `chavesAlertas`, `chavesLugares`, `EstadoDeAcoes`, e os aliases de tipo de
   `api/tipos.ts`. Excesso de `export`, não código morto.
8. **As 100 chaves `app.*` do `application.yml`** — todas têm consumidor (`@Value` ou record de
   parâmetros). Nenhuma configuração órfã.
9. **Tela de benefícios não promete resgate** — a acusação óbvia (catálogo sem backend) não se
   sustenta: `app/(app)/beneficios.tsx:154-158` mostra um `Aviso` dizendo que nada é descontado
   agora e por quê. É o oposto do padrão que esta varredura procura.

---

## 5. Fora das quatro classes — surgiu da medição de cobertura

Não são órfãos nem comentários falsos, e não cabem na classificação pedida. Registro porque a
mesma medição os entregou e porque o `CLAUDE.md` diz que **todo endpoint nasce com teste de caminho
feliz e de erro**.

| # | Achado | Evidência |
|---|---|---|
| 5.1 | **A implementação real de JWT nunca roda na suíte.** `JwtTestConfig` substitui o bean por uma subclasse que reimplementa `emitirAccessToken` e `validar` com chaves geradas em memória | JaCoCo: `JwtService.emitirAccessToken` (`:53`) **0/43** e `JwtService.validar` (`:84`) **0/24**. Um erro no `issuer`, no `audience`, no TTL de produção ou na leitura do PEM passa a suíte inteira verde |
| 5.2 | **`POST /auth/logout` não tem teste** | `AuthController.logout` (`:62`) 0/13 e `AutenticacaoService.logout` (`:250`) 0/26 |
| 5.3 | **A ação `contestar` não tem teste** — é uma das 17 transições da máquina de estados | `MissaoController.contestar` (`:295`) 0/10 e `MissaoService.contestar` (`:584`) 0/8 |
| 5.4 | **O caminho "ponto lotado → alerta global" nunca é exercido**, embora `V904__seed_entrega_falida_fixtures.sql` exista justamente para dar fixture aos caminhos do webhook | `DespachanteAlertaService.gravarPontoLotado` (`:259`) 0/35 |

Também com 0% e sem teste, em ordem decrescente de tamanho: `GlobalExceptionHandler.handleIntegridade`
(`:240`), `GlobalExceptionHandler.sqlState` (`:261`), `GlobalExceptionHandler.handleConflitoConcorrencia`
(`:206`), `HmacWebhookFilter.responder429` (`:224`), `SecurityConfig.handler403` (`:286`),
`EdicaoMissaoVerificador.violacao` (`:56`), `Coordenadas.arredondar` (`:66`), `UsuarioSistema.ehSistema`
(`:30`). Os três primeiros são os handlers que transformam violação de integridade e conflito de
concorrência em ProblemDetail — o caminho de erro de operações de valor.

---

## 6. Ordem de correção por risco

Não removi nada. A ordem é por **dano se ninguém mexer**, não por esforço.

| # | Item | Ação | Por que nesta posição |
|---|---|---|---|
| 1 | §1.1 — outbox descarta em silêncio, e 3 arquivos prometem o contrário | **Corrigir o texto primeiro** (`PublicadorEventos.java:25`, `DespachanteAlertaService.java:24`, `application.yml:194`); decidir o instrumento depois — precisa da sua decisão, muda o contrato de entrega | É o único achado com perda de FATO possível hoje. E a promessa é o que impede alguém de procurar o problema |
| 2 | §1.4 / §2.1 — `potesImobilizados` órfão + ADR 0015 afirmando visibilidade | **Ligar, não remover**: é uma linha de serviço + um `GET` de ADMIN ao lado da reconciliação. Se não for ligar, corrigir a consequência do ADR 0015 | Token imobilizado é dinheiro da comunidade, e o ADR afirma que existe vigilância sobre ele |
| 3 | §1.2 — `PontoCustodiaService.java:20-24` | Reescrever o parágrafo: dizer que a escrita existe, quem chama, e sob qual lock | Comentário falso sobre concorrência é o que produz a próxima regressão de lock |
| 4 | §5.1 — implementação real de JWT sem cobertura | Um teste que exercite `JwtService` real com PEM de teste (emitir → validar → expirado → assinatura errada) | Não é órfão, é o núcleo da autenticação rodando sem rede |
| 5 | §1.3 / §2.2 — `deClaims` | Remover método e javadoc, ou anotar que o caminho é histórico (ADR 0016) | Convida a desfazer a reconferência por requisição |
| 6 | §5.2 e §5.3 — `logout` e `contestar` sem teste | Teste feliz + erro para cada | Regra explícita do `CLAUDE.md` sendo violada em dois endpoints publicados |
| 7 | §1.7 / §2.6 — `missaoDeTerceiro` | **Usar** num teste do recorte de endereço (é o que a docstring promete), ou apagar fixture e docstring juntas | Enquanto existir, sugere uma cobertura que não há |
| 8 | §1.5 / §2.3, §2.4 — `porRastreio`, `foiConvertida` | Remover | Superfície pública morta em módulo de valor |
| 9 | §2.5 — `listarTribos` | Remover, ou ligar na tela de tribo | Cliente HTTP sem chamador |
| 10 | §1.6 / §2.7 — `BASE_TESTE` duplicado | Fazer `manipuladores.ts` importar de `servidor.ts`, ou apagar a exportada | Duas fontes para a mesma URL de teste |
| 11 | §1.8 — javadoc órfão | Mover o bloco para junto de `gravarPontoLotado:258` | Custo nulo |
| 12 | §2.8 — 20 `.gitkeep` | Remover os 20; preservar `tools/seed/.gitkeep` | Ruído |
| 13 | §1.9 — ADR 0012 e `eas.json` | Nota de rodapé datada, **sem** reescrever o contexto | ADR é registro histórico; apagar contexto é pior que a desatualização |

---

## 7. O que esta varredura NÃO cobre

- **Símbolo alcançado só por reflexão, string ou convenção** — nome de bean em string, `@Query` por
  nome derivado, rota de arquivo do Expo Router. Tratei roteamento por arquivo como vivo por
  construção; não fiz o mesmo esforço para reflexão em bibliotecas de terceiros.
- **Código morto DENTRO de método vivo.** A medição por método não vê um `if` que nunca é
  verdadeiro. BRANCH está em 75,1% — há ramo não exercitado que esta varredura não enumera.
- **Cobertura ≠ asserção.** Um método "coberto" pode ter sido executado sem que nada verificasse o
  resultado. Onde afirmei "tem teste" (§4), fui ler a asserção; onde disse "coberto", é só execução.
- **O relatório JaCoCo é de 2026-08-17**, três dias antes desta varredura. Nenhum commit de código
  entrou depois (só `CLAUDE.md`), então vale — mas é medição herdada, não medição de hoje.
- **Só `services/api` e `apps/mobile`.** `tools/`, `docker/` e `.github/` entraram apenas onde a
  pergunta era "isto é referenciado por alguém?".
- **Não conferi os 103 comentários de garantia um a um.** Conferi os 24 que afirmavam algo
  verificável sobre concorrência, transação, segurança ou existência de teste — que é onde os três
  achados anteriores do projeto moravam. Sobra cauda.
