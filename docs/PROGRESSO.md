# Progresso — Omni-Tribo

| Fase | Nome                                  | Status       | Auditoria | Data       |
|------|---------------------------------------|--------------|-----------|------------|
| F0   | Fundação do monorepo                  | ✅ Concluído | [F0](auditoria/F0.md) | 2026-08-04 |
| F1   | Infraestrutura local                  | ✅ Concluído | [F1](auditoria/F1.md) | 2026-08-04 |
| F2   | Bootstrap da API                      | ✅ Concluído | [F2](auditoria/F2.md) | 2026-08-05 |
| F3   | Domínio e migrations                  | ✅ Concluído | [F3](auditoria/F3.md) | 2026-08-06 |
| F4   | Autenticação e segurança              | ✅ Concluído | [F4](auditoria/F4.md) | 2026-08-06 |
| F5   | Missões e ciclo de vida               | ✅ Concluído | [F5](auditoria/F5.md) | 2026-08-06 |
| F6   | Geolocalização e check-in             | ✅ Concluído | [F6](auditoria/F6.md) | 2026-08-07 |
| F7   | Carteira e integridade transacional   | ✅ Concluído | [F7](auditoria/F7.md) | 2026-08-07 |
| F8   | Logística, notificações e patrocinador| 🟨 Parcial  | —         | 2026-08-14 |
| F9   | App mobile — autenticação             | ✅ Concluído | [fundação](auditoria/mobile-fundacao.md) | 2026-08-08 |
| F10  | App mobile — missões e check-in       | ✅ Concluído | [fundação](auditoria/mobile-fundacao.md) | 2026-08-08 |
| F11  | App mobile — carteira e perfil        | ✅ Concluído | [fundação](auditoria/mobile-fundacao.md) | 2026-08-08 |
| F12  | App mobile completo (7 telas) + leituras que faltavam | ✅ Concluído | [completo](auditoria/mobile-completo.md) | 2026-08-09 |
| F12b | Testes de carga e endurecimento       | ⬜ Pendente  | —         | —          |
| F12c | Previsão de risco de falha de entrega | ✅ Concluído | [modelo](qualidade/modelo-previsao.md) | 2026-08-15 |
| F13  | Entrega final                         | ✅ Concluído | [evidências](evidencias/) | 2026-08-16 |

> **A numeração acima é a dos COMMITS e das auditorias, e foi corrigida em 2026-08-08.** A tabela
> anterior estava deslocada a partir da F2 (chamava a fase de API de "Identidade e Autenticação") e
> marcava a carteira como pendente com o módulo já mergeado. Duas branches numeraram a mesma fase de
> formas diferentes — o commit da carteira se chama "F7" e a tabela a chamava de "F5". Agora tabela,
> commits e `docs/auditoria/FN.md` usam a mesma numeração.

**Backend verde com 637 testes** (0 falhas, 0 erros, 2 pulados), SpotBugs limpo e os dois gates
JaCoCo passando. **Mobile com 179 testes** Jest/RTL/MSW em 14 suítes, typecheck e lint sem erro.
Medido em 2026-08-16 por `make test` — saída em
[`evidencias/f13-make-test.md`](evidencias/f13-make-test.md).

Os testes de integração contra a API em execução ficam **fora** do `npm test`, de propósito
(`jest.e2e.config.js`); o ciclo ponta a ponta com dois usuários reais está em
[`evidencias/f12-ciclo-ponta-a-ponta.md`](evidencias/f12-ciclo-ponta-a-ponta.md).

**F8 está PARCIAL, e a distinção importa.** O que era a parte grande foi entregue em 2026-08-14: o
módulo **"Fim da Entrega Falida"**, que é a tese do produto. Webhook de transportadora autenticado
por HMAC sobre o corpo bruto (ADR 0021), conversão da entrega falida em missão de retirada ABERTA no
ponto de custódia (ADR 0020), trava de reputação por nível mínimo, notificação por tribo com
consentimento e teto por hora, e baixa da custódia na conclusão. `tools/carrier-mock/enviar.sh`
exercita o caminho feliz e cinco negativos.

O que continua faltando é a carteira de PATROCINADOR — o que fecharia a Pendência #1 e faria ENTREGA
e AJUDA pararem de cunhar token. Com o webhook em pé, essa lacuna ficou mais visível: cada entrega
falida convertida cunha tokens. O caminho está montado — `entrega_falida.valor_ofertado_brl` já
guarda o que a transportadora oferece, e a mecânica de pote existe em `FinanciamentoMissao`.

**As fases de mobile foram auditadas em 2026-08-09**, por dois agentes independentes que mediram
contra o sistema em execução. Quatro defeitos; dois corrigidos no mesmo dia, dois em aberto nas
Pendências do CLAUDE.md.

## Notas de manutenção

- **2026-08-20** — **A carteira de patrocinador fechou a Pendência #1, e o caminho até ela achou
  dois defeitos que ninguém tinha visto.**

  A Pendência #1 dizia que ENTREGA e AJUDA cunhavam token na conclusão, então a conservação
  `SUM(carteiras) + SUM(potes)` valia para duas categorias e não para o sistema. O que a implementação
  mostrou é que a formulação estava incompleta: **a cunhagem não podia ser "removida", só deslocada.**
  Alguém tem de pôr o token no pote. A decisão (ADR 0024) foi tirá-la do FIM do ciclo — implícita, por
  missão, invisível para a reconciliação — e pô-la no COMEÇO, num `APORTE_PATROCINADOR` por endpoint
  ADMIN, auditado e idempotente. O ganho não é "não cunhar mais"; é a emissão virar um número que
  alguém consegue somar.

  **Três armadilhas que o desenho óbvio teria pisado:**

  1. **`abrirMissaoDeRetirada` não passa por `aplicar()`.** Ela chama a máquina de estados direto,
     então `validarPoteSuficienteParaPublicar` NUNCA roda nesse caminho. Virar `pagaTokensDoPote`
     sem financiar dentro da conversão criaria missões ABERTAS com pote vazio: alguém aceitaria,
     entregaria, faria check-in, e a conclusão falharia com 422 **para sempre** — e como missão de
     retirada só conclui pela varredura de prazo, o erro apareceria no job, não numa requisição, com
     o token do executor perdido e a vaga do ponto travada.
  2. **`LancamentoRepository.buscarFinanciamentosDaMissao` filtrava um motivo só.** Um motivo novo
     ficaria invisível para o estorno, e cancelar ou expirar uma missão patrocinada não devolveria
     nada — token preso numa missão morta, com a reconciliação respondendo `integro=true`, porque
     ledger e projeção continuam batendo. Era a Pendência #5 reaparecendo por outra porta.
  3. **A regra por CATEGORIA não conseguia separar as duas ENTREGAs.** Ligar ENTREGA ao pote
     quebraria a ENTREGA criada por humano, que ficaria impublicável — financiamento de ENTREGA é
     recusado e o pote nunca alcançaria a recompensa. Por isso a decisão virou coluna
     (`missao.fonte_pote`), congelada na criação.

  **E duas coisas quebradas que não tinham relação com a tarefa:**

  - **`EntregaFalidaCicloTest.tetoPorHoraCortaOExcesso` estava vermelho em `develop`**, antes de
     qualquer mudança — confirmado rodando a suíte na baseline com o trabalho em `git stash`: 637
     testes, 1 falha. A causa é **dependência do relógio**: o corpo do webhook não informava
     `janelaHoraInicio`, então o controller usava a HORA ATUAL como característica do modelo de
     risco. Em hora de risco ALTO o carve-out do teto sobe de 5 para 8 alertas, os 5 alertas de ruído
     deixam de esgotar a cota, e o teste que esperava 0 recebia 1. Verde de manhã, vermelho à noite.
     Corrigido fixando a hora no fixture — não é relaxar assertion, é remover uma entrada oculta.
  - **`make reset` não funcionava com podman.** O bind mount `./docker/init` não tinha a flag de
     relabel do SELinux, então o container morria com `Permission denied` em
     `/docker-entrypoint-initdb.d/`. Latente por construção: os scripts de init rodam UMA vez, na
     criação do volume, então `make up` num volume já existente sempre funcionou — a falha só aparece
     quando alguém precisa recriar o banco, que é o que toda migration nova exige. Corrigido com
     `:ro,z` no compose.

  Evidência: `./mvnw verify` verde com **651 testes, 0 falhas**, SpotBugs limpo e os dois gates
  JaCoCo passando; `tools/carrier-mock/enviar.sh` com os 6 cenários OK contra o servidor de pé; e a
  conservação medida no banco depois da conversão real — `carteiras + potes` fechando e reconciliação
  com 0 divergências.

- **2026-08-17** — **O `Security Scan` estava vermelho havia dois dias, e ninguém tinha registrado.**

  O relato foi "a security scan falhou no GitHub". O que a API do Actions mostrou foi mais estreito e
  mais interessante que isso: **o Gitleaks passava — 48 execuções, todas verdes.** Quem falhava era o
  job `dependencias`, introduzido em `ca328fc`, reprovado nas **4 execuções desde que existe**.

  **Dois números diagnosticaram sem precisar do log**, que a API pública não entrega sem token
  (`403`). Primeiro: o passo falhava em **25 a 36 segundos** — o Dependency-Check baixa a base da NVD
  e analisa o classpath inteiro, o que leva minutos; meio minuto é o tempo de abortar na aquisição de
  dados. Segundo, e pior: o passo seguinte, *"Upload relatório de vulnerabilidades"*, **concluía com
  sucesso em 0 s**. O `actions/upload-artifact` estava no padrão `if-no-files-found: warn`, então
  passava sem encontrar arquivo nenhum. **O passo que existia para dar visibilidade era o que
  escondia que nenhum relatório havia sido produzido.**

  A causa é a ausência do secret `NVD_API_KEY` — o mesmo `Invalid API Key, length of 0` que a
  verificação de 08-15 já tinha capturado localmente. **A documentação descrevia a causa e nunca o
  efeito:** nenhum arquivo do repositório dizia que o workflow estava vermelho, e a matriz de
  rastreabilidade chamava a falta de "limitação **deste ambiente**", quando o secret também não
  existe no GitHub.

  **O conserto separa os dois jobs por cadência, e a razão não é cosmética.** Segredo vaza no
  instante do commit, então `gitleaks` continua em todo push e PR. CVE novo é publicado pela NVD de
  forma assíncrona ao repositório — varrer a cada push não adianta a descoberta em um dia sequer e
  queima cota de uma chave limitada por taxa. `dependencias` passou a `schedule` semanal +
  `workflow_dispatch`, com o passo guardado por `if: env.NVD_API_KEY != ''` e um `::warning` quando a
  chave falta. **Pular calado seria pior que falhar:** um job verde por não ter varrido nada é
  indistinguível de um verde por não ter achado nada, e o aviso é o que impede a confusão. O
  `if-no-files-found` virou `error`.

  Detalhe de implementação que custou uma consulta à documentação: **o contexto `secrets` não existe
  em `if:`**, nem no nível de job nem no de step. É preciso expor o secret em `env:` no job — `env`,
  esse sim, está disponível no `if:` de step.

  **A varredura de dependências continua sem ter rodado**, e isso não mudou. O workflow deixa de ser
  vermelho sem passar a alegar que varreu algo.

  **A revisão final de afirmação × evidência achou mais seis.** O núcleo quantitativo estava sólido —
  328 arquivos Java, 8.386 linhas no mobile, 44 endpoints, 23 ADRs, 17 transições: todos conferidos
  por comando, todos exatos. O que não estava: o ✅ do Gitleaks na matriz vinha do **YAML**, não de
  uma execução; a frase "o CI do mobile ficou vermelho da F9 até 2026-08-13" não tinha registro
  nenhum — **e estava certa**, nove execuções vermelhas de 08-09 a 08-13, viradas em `5f6fc11`; o
  índice de evidências creditava ao `f13-make-test.md` a prova do SpotBugs e dos gates JaCoCo, mas o
  console colado ali tem só os cabeçalhos dos plugins, e as linhas de resultado são de **outra data**;
  `f6-explain-analyze.md` era a única evidência sem a seção "o que não garante" que a convenção do
  diretório exige; e a nota de 08-13 dizia **12** módulos nativos quando são **22**, todos na versão
  exata do SDK 57 — a contagem errada enfraquecia um argumento que era mais forte do que se dizia.

  As medições de flake de 08-13 (`221 ms → 2110 ms`, `1,8 s` contra `5 min 2 s`, "28 rodadas
  verdes") ficaram, **marcadas como diagnóstico da época sem log retido**. Apagar o histórico de um
  diagnóstico correto é pior que declarar que ele não tem arquivo — mas passá-lo por evidência
  também. Tudo em [`evidencias/f13-ci-github-actions.md`](evidencias/f13-ci-github-actions.md).

- **2026-08-16** — **F13, entrega final: a documentação passa a ser verificável.** Quatro coisas
  saíram desta fase e três delas são correções, não adições.

  **O README foi executado, não revisado.** Com o volume do banco e as chaves RSA destruídos antes,
  segui o próprio README ao pé da letra. Uma instrução não sobreviveu: ele mandava confirmar
  *"healthy"* na saída de `make ps`, e o compose desta máquina imprime apenas `Up 50 seconds` — o
  estado de saúde só aparece pedindo o campo explicitamente. Quem seguisse a instrução concluiria
  que o banco não subiu. Corrigido. O log está em
  [`evidencias/f13-execucao-do-zero.md`](evidencias/f13-execucao-do-zero.md).

  **A contagem de testes estava errada há uma fase.** Este arquivo e o README diziam 457 no backend
  e 128 no mobile; a medição real deu **637 e 179**. Os números não foram atualizados depois da
  rodada de resiliência de 08-15, o que é exatamente o tipo de afirmação sem evidência que a
  varredura final desta fase existia para achar. Agora ambos apontam para o arquivo de saída.

  **A conservação foi remedida do zero, e o resultado é a melhor peça de defesa do projeto.** Dois
  ciclos completos por HTTP: AJUDA cunhou exatamente o valor da recompensa (Δ=+30) e TRIBO financiada
  conservou (Δ=0) — **com a reconciliação respondendo `integro=true` nos dois casos**. É a
  demonstração executada de que reconciliação e conservação são invariantes diferentes e só uma tem
  endpoint. Script versionado em `tools/evidencias/`.

  **Os diagramas foram validados por renderização, não por leitura.** Os sete arquivos Mermaid
  passaram pelo `mermaid-cli` 11.16, e um erro real apareceu aí: `∈ [1,00; 1,50]` dentro de uma
  mensagem de `sequenceDiagram` abre um token que o parser não espera, e o bloco inteiro deixa de
  renderizar. Num documento de entrega, isso apareceria como um espaço em branco na frente da banca.

  Fecham a fase: `CHANGELOG.md`, o índice de `evidencias/`, o comparativo de tecnologia mobile, o
  documento de divergências contra o PETI, o roteiro de demonstração e o `eas.json` — este último
  **configurado e não executado**, o que está declarado onde ele é citado.

- **2026-08-15** — **Resiliência das integrações externas e gates bloqueantes.** Cinco coisas que só
  apareceram ao construir.

  - **O `resilience4j-spring-boot4` não existe, e o `spring-boot3` 2.4.0 é ANTERIOR ao Boot 4.1.** A
    2.4.0 saiu em 2026-03-14 e declara "support for Spring Boot 4 / Spring Cloud 5"; o Boot 4.1.0
    saiu em 2026-06-25. Ou seja, o suporte foi escrito contra a série 4.0.x e a combinação com a 4.1
    não é verificada por ninguém. O Framework 7 tem `RetryTemplate` nativo mas **não** tem circuit
    breaker — daí disjuntor próprio + retry nativo, com zero dependência de runtime nova. ADR 0023.

  - **`RetryTemplate.invoke` e `execute` não são intercambiáveis, e escolher errado não quebra teste
    nenhum.** Só o `invoke(Supplier)` desembrulha o `RetryException` e relança a exceção original
    (verificado no bytecode). Com `execute`, o disjuntor veria `RetryException` em vez de
    `HttpServerErrorException`, classificaria tudo como "não é falha do provedor" e **nunca
    abriria** — em silêncio, com a suíte inteira verde.

  - **O token da sonda de meia-abertura vaza se a sonda não chegar ao provedor.** Recusada pelo
    bulkhead, ou falhando com 4xx, a sonda precisa DEVOLVER o token; senão o disjuntor fica preso em
    meia-abertura para sempre — nunca fecha, nunca reabre, e o sintoma é o provedor voltar com o
    recurso ainda sumido da tela, sem uma linha de erro no log. Por isso há TRÊS desfechos (sucesso,
    falha, neutro) e não dois.

  - **Um `<includes>` do `jacoco:check` que não casa nada passa POR VÁCUO.** O bundle sai vazio e o
    gate aprova sem ter medido. Verificado subindo o mínimo do domínio para 0,99 e confirmando que
    o build reprova citando a razão real: *"instructions covered ratio is 0.92, but expected minimum
    is 0.99"*. O gate é sobre `INSTRUCTION` e não `BRANCH` porque branch está em 74,97%.

  - **Filtro `paths:` no nível do WORKFLOW é incompatível com status obrigatório.** O workflow pulado
    não reporta nada, e o GitHub trava o PR em *"Expected — Waiting for status to be reported"*
    indefinidamente. Job pulado por `if:` reporta sucesso e satisfaz o check; por isso o filtro
    virou um job `mudou` em `api.yml` e `mobile.yml`. Feito ANTES de exigir o status, porque na
    ordem inversa o repositório trava.

  - Achado colateral, corrigido: `GET /auth/me` e `POST /auth/logout` exigem JWT mas eram descritos
    como anônimos no OpenAPI — o `AuthController` era o único sem `@SecurityRequirement`. Encontrado
    por `ContratoOpenApiTest`, que compara a documentação contra a CADEIA DE SEGURANÇA. As
    comparações de caminho, sozinhas, são quase tautológicas: o springdoc deriva os paths do mesmo
    `RequestMappingHandlerMapping` que o teste consulta, então endpoint novo nasce documentado.

- **2026-08-15** — **Modelo de previsão de risco de falha de entrega (F12c).** Quatro coisas que só
  apareceram ao construir, e que valem mais registradas do que o resumo da feature.

  - **`Math.exp` teria tornado o modelo irreprodutível entre máquinas.** A especificação de
    `java.lang.Math` garante erro ≤ 1 ulp e permite intrínsecos diferentes por arquitetura de CPU e
    versão de JVM. O treino faz ~6 milhões de chamadas a `exp` acumuladas em somas, então 1 ulp na
    primeira época se amplifica pelas 2.000 seguintes. O sintoma seria o pior possível: o teste que
    confere os coeficientes publicados passaria na máquina de quem treinou e falharia no CI, sem
    nada ter mudado. `StrictMath` é especificado bit a bit (fdlibm) e resolve por construção. Vale
    para qualquer cálculo futuro cujo resultado seja comparado entre execuções.

  - **`Map.of`/`Map.copyOf` randomizam a ordem de iteração A CADA EXECUÇÃO DA JVM.** Não é "ordem não
    garantida" no sentido teórico: `ImmutableCollections` sorteia um `SALT` na inicialização da
    classe, a partir do relógio, e duas execuções do mesmo comando na mesma máquina iteram em ordens
    diferentes. Se o cálculo do log-odds iterasse o mapa de coeficientes, a soma em ponto flutuante
    mudaria no último bit entre execuções — e um caso na fronteira exata do limiar trocaria de
    classe, produzindo um teste que falha 1 vez em 50 no CI. A regra adotada: o vetor é montado pela
    ordem de `CaracteristicaRisco.values()`, mapas de configuração são lidos por chave e nunca
    iterados, e toda ordenação carrega desempate explícito por `ordinal()`.

  - **O codificador precisou ficar em `src/main` embora só o treino o exercite por completo.** O
    treinador vive em `src/test`; se ele tivesse a própria cópia da codificação, o dia em que alguém
    trocasse a ordem de duas dummies faria o modelo somar o coeficiente de "condomínio" sobre o valor
    de "rural" — os números continuariam plausíveis, o build continuaria verde, e as previsões
    estariam erradas. Com um único `CodificadorEntrega` compartilhado isso é impossível, e
    `ModeloRiscoTreinoTest` ainda compara a inferência de runtime com a do treinador em toda a
    partição de teste, que é a garantia que igualdade de coeficiente não daria.

  - **O limiar de decisão exigiu uma TERCEIRA partição.** A especificação pedia treino/teste e
    otimização de recall. Mas o limiar é um parâmetro ajustado a partir dos dados, igual a qualquer
    coeficiente: varrer 99 candidatos no conjunto de teste e depois reportar o recall desse mesmo
    conjunto é seleção sobre o conjunto de avaliação, e o número publicado sairia otimista. A
    correção custou uma linha (60/20/20 em vez de 80/20) e é o tipo de detalhe que uma banca cobra.

  Efeito colateral registrado: `app.missoes.recompensa.versao` subiu para **3**, porque a fórmula
  passou a aceitar multiplicador de risco. `CalculadoraDeRecompensaTest.semRiscoAV3ReproduzAV2`
  prova que missão criada por usuário continua valendo exatamente o que valia — sem isso, subir a
  versão teria reprecificado o app inteiro em silêncio.

- **2026-08-14** — **Módulo "Fim da Entrega Falida" (F8).** Três coisas que só apareceram porque a
  suíte foi escrita antes de declarar pronto, e que valem mais registradas do que o resumo da
  feature.

  - **`save()` fazia `merge()`, e o vínculo com a missão sumia no commit.** `EntregaFalida` tem
    `@Id` ATRIBUÍDO (`UUID.randomUUID()` no construtor) e não tem `@Version`, então o `isNew()` do
    Spring Data devolve false e `save()` chama `em.merge()` em vez de `em.persist()`. `merge()`
    devolve uma instância NOVA e gerenciada; a original fica DESTACADA. Mutá-la depois disso
    (`vincularMissao`) não é visto pelo dirty checking. O sintoma: a linha era gravada, a missão era
    criada, e `missao_id` ficava nulo para sempre — a baixa da custódia na conclusão não achava nada
    para dar baixa. **Nada falha em tempo de compilação**, e o caminho feliz do webhook respondia
    200. Correção: usar o retorno de `save()`. Vale para qualquer entidade do projeto com id
    atribuído e sem `@Version`.

  - **Centroide de tribo é a métrica errada para "está perto".** A notificação media a distância
    até `centroDaTribo`, e o seed já continha o contraexemplo: a Tribo Pinheiros possui o locker da
    Consolação, ~3,8 km a leste, e o centroide fica a mais de 3 km da própria loja da tribo em
    Pinheiros. Uma encomenda no Leroy Merlin Pinheiros **não notificava ninguém de Pinheiros** e
    notificava a Vila Madalena. Bairro real é espalhado e às vezes côncavo; o centro geométrico de
    uma região em U cai fora dela. Passou a ser a distância MÍNIMA às âncoras da tribo. Ver ADR
    0020; `centroDaTribo` continua existindo para centralizar mapa, que é a pergunta dele.

  - **A recusa por lotação não cabia na invariante de ocupação.** `MigracaoTest` trava
    `ocupacao == pendentes + convertidas-não-concluídas`, e uma entrega recusada gravada com
    `missao_id` nulo contava como pendente — exigindo `ocupacao + 1` num ponto lotado justamente por
    não caber mais nada. A causa era `missao_id IS NULL` significar duas coisas incompatíveis. A V21
    acrescentou `recusada_em`, e a assertion foi atualizada. **Uma coluna `status` seria mais
    legível e não sobreviveu**: a V21 roda ANTES dos seeds (faixa 900+ é a última), nenhum DEFAULT
    distingue convertida de pendente, e corrigir exigiria editar seed já aplicado — que muda o
    checksum e derruba todo banco de dev existente.

  - Efeito colateral do desenho, registrado como Pendência #2 no `CLAUDE.md`: a missão criada pelo
    webhook tem o usuário-sistema como criador, e `CONFIRMAR` exige `AtorEsperado.CRIADOR`. Como
    esse usuário nunca autentica, a missão só sai de `AGUARDANDO_CONFIRMACAO` pela varredura de
    prazo — que conclui pagando o executor, mas depois da espera.

- **2026-08-09** — **O botão "Sacar em reais" saiu da carteira; entrou a vitrine de resgate
  (`app/beneficios.tsx`).**
  - A decisão antiga estava registrada e auditada como CONFORME — botão visível-desabilitado com o
    motivo ao lado, porque "um botão ausente não ensina nada". Ela envelheceu por um motivo simples:
    **o app promete resgate em benefício de parceiro em três lugares** (o card da carteira,
    `SaldoToken` e o onboarding) **e não oferecia porta nenhuma**. O que oferecia era um botão que só
    sabia dizer não. Um aviso explica o que a moeda NÃO é; um catálogo mostra o que ela É — e é a
    tese do produto, além do sumidouro do TOKEN no ADR 0009 §3.
  - **Escopo deliberadamente só de front-end, e o motivo foi medido antes:** grep amplo sobre
    `services/api/src`, `db/migration` e `db/seed` por resgate/cupom/benefício/parceiro devolve **só
    comentário** — zero tabela, zero endpoint, zero motivo `RESGATE` no CHECK de `lancamento.motivo`,
    zero `TipoProblema`. O ADR 0009 §3 decide o sumidouro e **não o atribui a nenhuma fase**; a F8
    prevê o patrocinador como FONTE do pote, não como destino do resgate.
  - **Nada é debitado, e a tela diz isso.** Simular o débito só no cliente produziria um saldo que o
    servidor desmente no primeiro `refetch`, e um número que muda sozinho contamina a confiança na
    carteira inteira. A folha de cada benefício traz um `Aviso` explicando que o resgate é combinado
    no balcão do parceiro e que a baixa automática chega com a carteira de patrocinador.
  - **Uma regra de economia virou teste.** Benefício se expressa em BEM ou em PORCENTAGEM, nunca em
    reais: pelo ADR 0009 §6, um cupom de "R$ 20" fixa uma cotação token→real exatamente onde o
    produto recusa ter uma — e token conversível *é* dinheiro, com KYC junto. A regra estava só em
    prosa; agora reprova em dois pontos (catálogo e tela).
  - Limpeza adjacente: `useSacar()` foi removido de `features/carteira/hooks.ts`. Era órfão e se
    defendia com um comentário **falso** ("existe para a tela exercitar o erro tipado num teste" — o
    teste chama `sacar()` da camada de API direto). Mesma classe de achado que as auditorias já
    haviam registrado duas vezes. `sacar()`, o `type` `saque-desabilitado` e o teste do 422 ficam:
    saiu a UI, não a integração.
  - Verificado por mutação: reintroduzir o botão de saque reprova o caso novo da carteira, e
    anunciar um benefício em "R$" reprova dois testes. Suíte: **153 testes, 13 suítes, 0 falhas**.

- **2026-08-09** — **`DateTimePicker: 'onChange' is deprecated` ao criar missão.** O
  `@react-native-community/datetimepicker` 9.1.0 quebrou `onChange` em três callbacks —
  `onValueChange`, `onDismiss` e `onNeutralButtonPress` — e avisa em `__DEV__` se o antigo for
  passado. A migração melhorou o código, não só calou o aviso: com `onChange`, cancelar chegava como
  "mudou, talvez sem data", e distinguir seleção de desistência dependia de inspecionar o argumento
  (no Android vinha sem data; no iOS, com a data antiga). Agora cada desfecho tem caminho próprio e
  `escolhido` deixou de ser opcional. `onNeutralButtonPress` ficou de fora de propósito: é o botão
  "limpar" do Android, e janela de missão não tem estado "sem valor".

  **O que o episódio revelou é maior que o aviso: o encadeamento data → hora não tinha teste nenhum.**
  É a regra que justifica o componente existir — `aoMudar` dispara UMA vez, com o instante completo,
  porque um disparo por passo faria o formulário validar uma janela com data nova e hora velha e
  piscar "fim antes do início" no meio da escolha. Entraram 4 testes (`SeletorDataHora.test.tsx`),
  todos reprovando contra o código anterior. O primeiro fixture que escrevi usava ISO com `Z` e
  falhava fora de −03, porque o picker entrega `Date` LOCAL; foi corrigido para
  `new Date(ano, mês, dia)` e roda verde em −03, UTC e +14.

- **2026-08-09** — **CI do mobile falhando "de vez em quando": duas causas, nenhuma delas um teste
  errado sobre o código.**
  - **Assertion que esperava o container em vez do conteúdo.** `telas.test.tsx` fazia
    `await findByTestId('lista-alertas')` e, na linha seguinte, `getAllByText(/Recompensa
    creditada/)`. O testID está na `FlatList`, que monta na PRIMEIRA renderização — de propósito,
    para cabeçalho e filtros ficarem visíveis durante o carregamento. Ou seja, o `findBy` resolvia
    de imediato e não esperava nada; o `getAllBy` corria contra uma lista vazia sempre que a
    resposta demorava um tick a mais. Medido na época: 1 falha em 2 rodadas sob carga, 0 em 10 sem
    carga — daí "de vez em quando", e daí falhar mais no runner, que é mais lento. *(Os números
    desta nota e das duas seguintes são do diagnóstico do dia; o log não foi retido e não há
    evidência arquivada para eles. O que está provado, pela API do GitHub, é o efeito: nove
    execuções vermelhas seguidas — ver `evidencias/f13-ci-github-actions.md` §3.)*
  - **Um `gcTime` de 5 minutos segurando o processo.** `render.tsx` zerava o `gcTime` das queries e
    não o das mutations, cujo default é 300 s. Toda mutation exercitada num teste deixava um
    `setTimeout` pendurado, e timer vivo segura o event loop: a suíte de telas rodava em **1,8 s** e
    o processo só terminava **5 min e 2 s** depois. Não aparece com vários workers, porque o jest
    mata o worker à força — aparece exatamente onde não há worker para matar, isto é, no runner de 2
    núcleos, que executa in-band. O sintoma é um job que trava sem nenhum teste vermelho.
  - Depois das duas correções: **28 rodadas seguidas verdes** (20 normais + 8 in-band, todas sob
    `TZ=UTC` como o CI), e o aviso "worker process failed to exit gracefully" desapareceu.

- **2026-08-09** — **Seed de demonstração fora de Pinheiros (`V903__seed_cidade_lider.sql`).** O
  V900 concentra tudo a ~25 km da zona leste, então o radar abria vazio para quem apresenta de lá —
  comportamento correto de um radar geoespacial, e inútil numa banca. O seed novo povoa o entorno do
  CEP 08280-460. Três decisões que valem registro:
  - **Usuários novos, não realocação dos existentes.** Mudar a tribo de alice/bob/carol pareceria
    inofensivo e quebraria assertions de extrato e de alertas em outro módulo.
  - **Recompensas conferidas contra `POST /missoes/previa-recompensa`**, não calculadas de cabeça: as
    8 batem. Valor de seed divergente da fórmula transformaria a prévia em contradição na tela.
  - **O `MigracaoTest` pegou o seed errado antes do commit.** A primeira versão punha ocupação
    decorativa nos pontos de custódia; o teste exige que `ocupacao` iguale o que está fisicamente
    lá (pendentes + convertidas cuja missão não concluiu). A correção foi semear as encomendas que
    faltavam — o que também melhorou a demonstração, porque encomenda pendente é justamente a que
    ainda vai virar missão.
  - Efeito colateral necessário: `TriboControllerTest` afirmava nomes de tribo por ÍNDICE
    (`$[0]`, `$[1]`, `$[2]`). Passou a verificar a ordenação como propriedade — mesma bomba-relógio
    que `MissoesProximasTest` já documentava ao recusar assertion de tamanho em tabela compartilhada.

- **2026-08-09** — **Três sintomas de "não consigo rodar", três causas independentes.** Nenhuma era
  regressão de código: as três eram configuração de execução local, e as três produziam um erro que
  aponta para o lugar errado.
  - **Swagger abria em branco, com 200 no HTML e nada no log do servidor.** A causa era a CSP:
    `default-src 'none'` é a política certa para quem só devolve JSON, e a cadeia principal do
    `SecurityConfig` não tem `securityMatcher`, então ela alcançava também `/swagger-ui/**`. O
    browser recusava o `swagger-ui-bundle.js`, o CSS e o `fetch` de `/v3/api-docs`. Autorização e
    rate limit já liberavam esses paths — a suspeita óbvia era a errada. Correção: cadeia própria
    `@Order(0)` com CSP compatível com SPA, válida só para os três paths do springdoc, mais três
    testes em `CabecalhosSegurancaTest` — inclusive o contrapeso que falha se alguém "consertar"
    relaxando a CSP global.
  - **`ApiApplication.java` não subia pela IDE, e falhava duas vezes seguidas.** O botão *Run* do
    VS Code sobe no perfil default, onde `${DATASOURCE_URL}` não tem valor — os defaults de
    localhost só existem em `application-dev.yml`. Resolvido isso, esbarra na segunda: o
    `JwtService` lê os PEM por caminho de filesystem relativo, e o diretório de trabalho padrão é a
    raiz do repositório. `application.yml` ficou **estrito de propósito** — falhar rápido sem a
    variável é a proteção correta em produção —, e a conveniência virou `.vscode/launch.json`
    versionado, com exceção explícita no `.gitignore` (`.vscode/*` + `!.vscode/launch.json`; o git
    não reinclui arquivo dentro de diretório excluído).
  - **O app quebrava no boot da web, com a mensagem apontando para a linha errada.**
    `expo-secure-store` não tem implementação web — o módulo resolvido é `export default {}`. O
    `getItemAsync` estourava primeiro, o `catch` chamava `encerrar()`, o `deleteItemAsync` estourava
    **dentro do catch** e a segunda exceção substituiu a primeira no relato. Correção em duas
    camadas: `src/lib/armazenamentoSeguro.ts` como ponto único (nativo → keystore, web → `Map`
    efêmero, nada em claro no browser — **ADR 0013**), e o `catch` de `restaurarSessao` com `try`
    próprio, porque caminho de recuperação que produz exceção nova apaga o diagnóstico do problema
    que deveria resolver.

- **2026-08-09** — **Auditoria independente do mobile, e as correções que ela obrigou.**
  - Dois relatórios com evidência EXECUTADA: `docs/auditoria/mobile-fundacao.md` e
    `mobile-completo.md`. Nomeados por conteúdo, e não `FN.md`, porque a numeração de fases já usa
    F8 para "Logística, notificações e patrocinador".
  - **O achado metodológico vale mais que os defeitos: os dois auditores testaram os TESTES.** Um
    mutou a promessa compartilhada de refresh numa cópia em `/tmp` e viu o teste ir de verde a
    `Expected: 1 / Received: 3` — aquele teste tem dentes. O outro provou que o teste de permissão
    do mapa passava *só porque renderizava a tela isolada*: era assertion que nunca falharia.
  - **DEFEITO corrigido — o prompt de permissão era gasto pela aba de missões**, que monta primeiro,
    sem nenhuma justificativa. O card do mapa chegava depois da decisão. O default de
    `useLocalizacao` foi invertido para NÃO pedir ao montar, a justificativa virou componente
    compartilhado, e um segundo infrator apareceu no caminho (`criar.tsx`). Três testes novos.
  - **DEFEITO corrigido — contraste: 11 de 22 pares reprovavam em WCAG AA**, e nunca havia sido
    conferido. Os 12 hex especificados ficaram INTACTOS; entrou uma tabela `textoAcessivel` com as
    mesmas cores escurecidas só onde carregam texto. Efeito colateral bom: TRIBO ganhou
    preenchimento `verdeEscuro`, porque escurecer o texto de ENTREGA tornaria os dois chips
    idênticos — a correção de acessibilidade teria apagado a distinção de categoria.
  - **`transferenciaSchema` estava escrito e nunca importado.** A tela validava à mão, com um
    `return` mudo: quantidade vazia não fazia nada e não dizia nada, e 9999 tokens passavam por
    cima do teto de 500 que o próprio schema declarava.
  - **Dois comentários afirmavam garantias inexistentes** — `erros.test.ts` dizia ficar vermelho se
    uma URI mudasse no backend (não fica; ele lê literais próprios) e `registrar.tsx` dizia que
    `GET /tribos` não existia (existe). Mesma classe do achado da rodada F0→F7.
  - **`expo-dev-client` é desnecessário, e agora está medido:** **22 dependências** do app constam do
    `bundledNativeModules.json` do SDK 57, **todas na versão exata** que o SDK fixa — inclusive
    `react-native-webview`, `react-native-reanimated` e o `@react-native-community/datetimepicker`.
    **O app roda no Expo Go**, sem development build. Reconferido em 2026-08-17 (a nota original
    dizia "12", contagem errada); reproduzível com:

    ```bash
    python3 -c "import json; b=json.load(open('apps/mobile/node_modules/expo/bundledNativeModules.json')); \
    d=json.load(open('apps/mobile/package.json'))['dependencies']; \
    print(sum(1 for k in d if k in b and d[k]==b[k]))"
    ```
  - **Em aberto**, nas Pendências do CLAUDE.md: conta anonimizada escrevendo por 15 min (o mais
    grave), `nivel` divergente entre `/usuarios/me` e a exportação LGPD, e a transferência exigindo
    UUID digitado.

- **2026-08-09** — **App mobile completo e as leituras que faltavam.**
  - **As sete telas pedidas existem**: onboarding, mapa, detalhe com botão contextual, criação de
    missão, carteira com transferência, perfil com LGPD, e central de notificações.
  - **Dez endpoints novos**, e nenhum deles precisou de modelagem: `alerta`, `consentimento`,
    `tribo` e `ponto_custodia` já tinham tabela desde V2–V7. O banco estava à frente do código, como
    o CLAUDE.md sempre afirmou — a fase foi de fiação e contrato.
  - **A mensagem "você está a 180 m; aproxime-se para até 50 m" era impossível.** O 422 do check-in
    só tinha `type` e `detail`, e a regra proíbe parsear `detail`. As três rejeições passaram a
    expor campos de extensão do RFC 9457 (`distanciaM`/`raioM`, `acuraciaM`/`acuraciaMaximaM`), via
    um `getPropriedades()` novo em `DominioException`. Sem isso, o requisito não era implementável
    dentro das regras do projeto.
  - **`react-native-maps` foi DESCARTADO** e o mapa é WebView + Leaflet (ADR 0012). O motivo
    decisivo não é preferência: no Android a biblioteca exige chave do Google Maps, sem a qual o
    mapa renderiza cinza — e o ciclo não seria demonstrável.
  - **Primeira dependência externa do projeto** (ADR 0011): Open-Meteo e ViaCEP, os dois sem chave
    de API. O "100% local" ganhou exceção, com timeout de 2 s, cache e 503 explícito.
  - **Exclusão de conta é ANONIMIZAÇÃO, não DELETE.** Apagar a linha de `usuario` quebraria a FK do
    ledger append-only e a conservação de TOKEN deixaria de fechar.
  - **`notificacoes` deixou de ser vazio** e `DespachanteAlerta` migrou de `compartilhado`, como o
    CLAUDE.md previa — passando a ser injetado pela porta `DespachoAlerta`, porque `compartilhado` é
    isento do ArchUnit como ALVO mas continua sendo ORIGEM.
  - **Armadilha registrada:** rodar `npm run test:e2e` duas vezes no mesmo minuto falha com 429
    `limiteRequisicoes`. Não é defeito — é o bloqueio de 5 logins/min da F4, e os dois arquivos de
    e2e somam quatro logins por execução.

- **2026-08-08** — **App mobile (F9–F11) e ampliação do catálogo de erro.**
  - **A Pendência #4 foi resolvida antes da primeira tela, como ela própria exigia.** O catálogo
    `TipoProblema` tinha uma URI por CLASSE de erro, e as três rejeições de check-in — mock,
    acurácia, distância — chegavam ao app como o mesmo 422, separadas só pelo `detail`, que
    `apps/mobile/CLAUDE.md` proíbe parsear. As três pedem instruções mutuamente inúteis ("desligue o
    mock" ≠ "procure céu aberto" ≠ "aproxime-se"), então ganharam URI própria, junto com
    `saque-desabilitado`. O critério adotado — **uma URI por REAÇÃO DE UI, não por causa** — está no
    **ADR 0010**. Nenhum status HTTP mudou: os quatro continuam 422.
  - **A migration V17 não é detalhe de implementação, é o que torna o contrato honesto.**
    `RegistroCheckinService.replayDe` reconstrói o veredito a partir da linha persistida, sem
    reavaliar nada. Sem `checkin.codigo_rejeicao` gravado, a primeira tentativa responderia
    `checkin-fora-do-raio` e o **replay da mesma chave de idempotência** responderia o 422 genérico —
    dois contratos para a mesma operação, e o retry de rede virando um erro diferente do original.
    `ck_checkin_rejeicao_coerente` impede no banco a linha que reabriria isso. Há teste dedicado.
  - **Três armadilhas do ambiente de teste do Expo**, todas com sintoma que não aponta para a causa,
    registradas em `CLAUDE.md`: jest-expo 57 fixa o ecossistema **jest 29** (o `jest` 30 do npm
    quebra com `clearMocksOnScope is not a function`); **RNTL 14 tornou `render` e `fireEvent`
    assíncronos** (sem `await`, `screen` fica vazio); e o ambiente do jest-expo **não faz rede de
    verdade** — XHR e fetch são dublês —, por isso o teste de integração roda em
    `testEnvironment: 'node'`.
  - **Reanimated saiu do caminho dos componentes.** O esqueleto de carregamento usa o `Animated` do
    próprio React Native: Reanimated 4 roda sobre `react-native-worklets`, que exige o TurboModule
    nativo já no import e derruba em jest qualquer suíte que carregue a tela. Continua no projeto
    para as transições do Expo Router.
  - **`z.guid()` e não `z.uuid()` nos schemas.** O Zod 4 valida a versão do UUID, e os ids do seed
    (`bbbbbbbb-0000-0000-...`) têm nibble de versão zero, de propósito, para leitura no psql. Com
    `uuid()` o validador de contrato gritava contra dado válido — o caminho mais curto para treinar
    quem lê o log a ignorar o aviso.

- **2026-08-08** — **Rodada de auditoria F0→F7 encerrada.** Oito relatórios em `docs/auditoria/`,
  cada fase confrontada com a sua especificação **executando**, não lendo: SQL contra o banco de pé,
  `curl` contra a API em execução, `EXPLAIN ANALYZE`, e a suíte inteira. Saldo: **7 defeitos
  corrigidos**, e as lacunas que restam têm todas dono de fase.
  - **Cinco achados eram invisíveis na leitura do código**, e é a lição que ficou nos agentes:
    o oráculo de tempo no login (~6 ms contra ~68 ms, com um comentário afirmando a defesa que não
    existia); o `REVOKE` inerte porque a aplicação conecta como dono das tabelas; `type` sempre
    `about:blank` nos ~15 handlers herdados do Spring; mojibake em todo 401/403/429 por charset
    ausente; e `/actuator/health` respondendo 401.
  - **Duas fases passaram sem nenhum achado corretivo:** F1 (infraestrutura) e F6 (geolocalização).
    F4 (segurança) teve um único defeito, o oráculo de tempo.
  - **Duas premissas das especificações estavam tecnicamente erradas, e foram refutadas com
    evidência em vez de acomodadas:** (a) "404 vaza existência" — é o inverso, quem vaza é o 403, e
    seguir a letra teria introduzido enumeração de ids; (b) "idempotência por constraint, não por
    `if`" — o `if` do projeto é seguro porque roda depois de `SELECT … FOR UPDATE`, e 100 threads
    produzem exatamente 1 lançamento.
  - **Um falso alarme documentado:** na base de seed o planner escolhe o B-tree de status em vez do
    GiST, e está certo — forçar o caminho geoespacial é 200× mais lento com 12 missões. Registrado
    em `docs/evidencias/f6-explain-analyze.md` para não virar "defeito" na próxima leitura.
  - Correções estruturais da rodada: hook de segredo ativado (estava sem bit de execução, falhando
    em silêncio), catálogo `TipoProblema` com 10 URIs estáveis, cadeia própria do actuator, rate
    limiters com despejo por Caffeine, transposição alice↔bob no seed, e `V901` semeando entregas
    falidas — os dois lados da tese do produto, convertidas e pendentes.

- **2026-08-08** — **Recompensa calculada e congelada pelo servidor.** Build verde com **383
  testes** (eram 351). Fecha o defeito de maior impacto das auditorias F0–F7.
  - **Antes:** o cliente enviava `xpRecompensa` e `tokensRecompensa`, e o único controle era um
    `@Max`. Medido: missão AJUDA sem peso, sem volume e sem destino criada com **5.000 XP e 1.000
    tokens**. Teto sem fórmula = toda missão pode valer o teto.
  - **Depois, mesmo payload:** pedido 1000/5000 → persistido **20 tokens / 60 XP**, com
    `complexidade=LEVE` e `versao_formula=1`. Os campos enviados são descartados em silêncio, como
    `status` e `executorId` já eram.
  - `CalculadoraDeRecompensa` como função pura em `missoes/dominio`, com **24 testes sem Spring** em
    0,3 s: determinismo, faixa (varrendo milhares de combinações), monotonicidade em peso, volume,
    distância e complexidade, teto, fronteiras de derivação, e um **dourado** que trava a calibração
    v1. Verificado que o dourado falha — e só ele — ao mexer num parâmetro sem subir a versão.
  - **Congelamento provado em runtime:** com `tokens-por-kg` de 0.5 → 99 e `versao` 1 → 2, a missão
    criada sob v1 manteve 20 tokens/versão 1, e uma nova sob v2 saltou para 1000.
  - `POST /missoes/previa-recompensa` devolve o cálculo sem criar nada (13 missões antes e depois),
    para o app não duplicar a fórmula — duplicá-la reabriria a divergência por outro caminho.
  - **Complexidade derivada onde há dado:** ENTREGA e COLETA passam a exigir peso e volume e o
    servidor deriva; declarar junto é 400. TRIBO e AJUDA declaram. A assimetria evita que a
    complexidade vire o mesmo arbítrio da recompensa livre, com três degraus.
  - **V16** acrescenta `complexidade`, `versao_formula` e `multiplicador_risco` (reservado, F11).
    Seed recalculado pela fórmula, com a cascata do ledger: 6/6 carteiras reconciliam, circulação
    806 → 428 tokens. Os créditos de "co-participação" viraram frações da recompensa — antes diana
    recebia 100 numa missão que paga 38.
  - **Raio de impacto:** 12 arquivos de teste. Os que fixavam valor de recompensa passaram a
    derivá-lo da resposta de criação — ficaram mais robustos, porque param de quebrar a cada
    recalibração. `MissoesProximasTest` usava `tokens = 0` para escapar da guarda de pote; agora
    financia por SQL, já que o teste é sobre o radar.
  - Achado de passagem: `MissaoFixture` montava missão com `valorBrl = 25.00`, contradizendo o ADR
    0009 — passava porque a fixture não passa pela validação de request.
  - SpotBugs pegou `EI_EXPOSE_REP` nos dois `Map` de `ParametrosRecompensa`. Resolvido com cópia
    defensiva no construtor compacto, e não com supressão: record não congela o conteúdo de coleção,
    e mutabilidade ali é a garantia de auditoria vazando.

- **2026-08-07** — **ADR 0009: economia do cuidado. BRL sai do ciclo de missões.** Build verde com
  **347 testes**. Correção de PREMISSA, não de implementação: o ADR 0004 registrava que o criador
  paga a missão em dinheiro, o que nunca foi o produto. Quem cria não paga; a recompensa é XP +
  TOKEN, resgatável em benefícios do bairro.
  - **O defeito medido antes:** R$ 118,00 viraram R$ 1.618,00 em três ciclos do fluxo feliz, com o
    saldo do criador intacto em R$ 18,00 o tempo todo. E a reconciliação respondia `integro=true`
    corretamente — ela compara ledger com projeção, e o BRL não tinha invariante de conservação
    para violar. Nenhum endpoint de auditoria pegaria isso.
  - **Depois:** criar missão com `valorBrl: 500` → **400** apontando o campo; `POST /carteira/saques`
    → **422** com `type` do catálogo; BRL total do sistema → **0,00**; TOKEN
    (carteiras + potes) → 656, reconciliação íntegra.
  - Mudanças: **V15** troca `ck_missao_economia` para `valor_brl = 0` em toda categoria;
    `CriacaoMissaoVerificador` deixa de depender da categoria; `app.carteira.saque-habilitado`
    (false por padrão, true em teste) com `SaqueDesabilitadoTest` cobrindo o caminho desligado;
    seed convertido de BRL para TOKEN à taxa 1:2, incluindo carteiras, lançamentos e `saldo_apos_*`.
  - **Não removemos** colunas nem o `SaqueService`: é a mecânica que a conversão patrocinada
    reaproveitaria, já testada sob concorrência. Ficam inertes, com a regra de negócio barrando uso.
  - **Deliberadamente NÃO fechamos a conservação do token para ENTREGA/AJUDA.** Exigir pote dessas
    categorias hoje faria a comunidade custear a logística do varejista — o inverso do modelo. O
    financiador certo é o patrocinador, e ele chega na F8. Lacuna documentada em vez de regra errada
    codificada.
  - Efeito colateral do trabalho: `ContainerConfig` subiu `max_connections` de 300 para 500. Cada
    `@TestPropertySource`/`@MockitoSpyBean` cria contexto Spring próprio, com pool de 40 — as duas
    classes novas estouraram o teto, e a falha aparecia como "Failed to load ApplicationContext",
    sem apontar a causa. A aritmética ficou registrada no javadoc.

- **2026-08-07** — **Auditoria F6 + F7 e VALIDAÇÃO PONTA A PONTA contra a API em execução.** As duas
  fases passaram sem correção necessária; o valor desta rodada foi provar que estão amarradas entre
  si, e não apenas verdes isoladamente.
  - **F6** — radar com default 2000 m e máximo 20000 m validados por Bean Validation, PostGIS
    confinado a `ConsultasGeoespaciais` (ADR 0007), `EXPLAIN ANALYZE` provando `Index Scan` em
    `idx_missao_origem` sobre 200 mil linhas em 21 ms, cache Caffeine por geohash com TTL 30 s e
    cinco pontos de invalidação. Todos os testes exigidos existem, com os nomes da própria spec:
    `a_49_metros_de_um_raio_de_50_aceita`, `a_51_metros_de_um_raio_de_50_rejeita`,
    `segunda_busca_identica_nao_toca_o_banco`.
  - **F7** — ledger append-only, conclusão em uma transação, transferência P2P com ordem
    determinística de lock, financiamento como sumidouro, outbox com backoff, saque, extrato e
    reconciliação. Os seis testes exigidos existem, e a justificativa da ordenação de locks explica
    inclusive por que `UUID.compareTo` não coincide com a ordem do tipo `uuid` do PostgreSQL — e por
    que isso é irrelevante, já que a prova exige apenas UMA ordem total consistente.
  - **Execução ponta a ponta, 24 verificações, zero falhas.** Fluxo real via HTTP contra o banco do
    compose: login dos 4 perfis → criar ENTREGA → publicar → aceitar → iniciar → carol barrada com
    403 (anti-IDOR) → check-in fora do raio 422, acurácia 90 m 422, `mocked` 422, válido 200 →
    confirmar → **saldo de bob 25,00 → 65,00, exatamente os R$ 40,00** → reconfirmar é no-op com
    saldo intacto.
  - **Conservação do TOKEN medida de ponta a ponta:** `SUM(carteira.saldo_tokens) +
    SUM(missao.pote_tokens)` era **500** antes e **500** depois do ciclo criar TRIBO → publicar sem
    pote (422) → financiar 20 → publicar (200) → cancelar (estorna). Nada cunhado, nada perdido.
  - Transferência P2P: mesma tribo 201, replay da chave não debita de novo (200 → 170 tokens, uma
    vez só), outra tribo 422, acima do teto 422. Reconciliação admin devolveu
    `{"carteirasVerificadas":6,"integro":true,"divergencias":[]}` e 403 para usuário comum. Outbox:
    1 evento `MissaoConcluida`, 1 publicado, 0 pendentes, com o `alerta` `MISSAO_CONCLUIDA`
    correspondente gravado — a cadeia transacional completa, do commit ao despacho.
  - Desvio de nomenclatura, sem impacto: a spec da F7 pedia `POST /missoes/{id}/concluir`; o
    endpoint chama-se `/confirmar`, que é o nome do evento no diagrama da própria F5. Contrato
    coerente com a máquina de estados.

- **2026-08-07** — **Auditoria F5 (missões e máquina de estados) e despejo nos limitadores.** Build
  verde com **341 testes**.
  - **Rate limiters passaram de `ConcurrentHashMap` para Caffeine com expiração.** Os três mapas de
    bucket não tinham despejo nenhum, e a chave do `BloqueioLoginService` inclui o EMAIL — num
    endpoint público. Um script mandando logins com endereços aleatórios criava uma entrada
    permanente por endereço, sem jamais acertar senha e sem jamais ser bloqueado, porque cada email
    novo é uma chave nova e o contador de falhas nunca acumulava: o antifraude virava vetor de
    exaustão de memória. No `RateLimitFilter` o mesmo valia por IP, que sai de `X-Forwarded-For`
    quando presente — cabeçalho controlado pelo cliente.
    **A troca é comportamento preservado por construção**, e é isso que a torna segura: o
    `refillGreedy` repõe a capacidade inteira a cada minuto, então bucket ocioso há 10 minutos já
    está cheio e recriá-lo devolve exatamente o mesmo estado. Para os buckets,
    `expireAfterAccess(10min)`; para `bloqueios`, `expireAfterWrite` de 2× a maior janela
    configurada — afterWrite porque o que conta é quando a falha foi registrada, e o dobro para que
    expiração jamais liberte alguém de um bloqueio vigente. Os testes de rate limit e de bloqueio
    progressivo, que são o risco real da mudança, seguem verdes.
  - **A F5 passou íntegra na auditoria — nenhuma correção necessária.** Os 9 requisitos conferem:
    transições declaradas no próprio `StatusMissao`, ator autorizado declarado em `EventoMissao`
    (CRIADOR/EXECUTOR/CANDIDATO/ADMIN/SISTEMA), trilha na mesma transação, lock pessimista com a
    escolha justificada em comentário (inclusive por que retry seria pior: o estado já mudou, então
    repetir nunca sucede), DTOs dedicados, regra de economia, `@Scheduled` de expiração com
    `SKIP_LOCKED` e OpenAPI completo.
  - Os seis testes exigidos existem, e o parametrizado é melhor do que o pedido: **99 combinações**
    (9 status × 11 eventos) com a tabela esperada escrita à mão, deliberadamente independente do
    enum — derivá-la de `StatusMissao` tornaria o teste tautológico, verde até se alguém apagasse
    uma transição. Também afirma que transição recusada não deixa mutação parcial. O caso
    ABERTA→CONCLUIDA tem teste nominal próprio, como a spec pedia.
  - Desvio já superado pelo tempo: a spec da F5 mandava deixar `checkin` e `concluir` como stubs
    `UnsupportedOperation` com TODO(F6)/TODO(F7). Ambos estão implementados desde o merge das fases
    seguintes; o handler de `UnsupportedOperationException` no `GlobalExceptionHandler` é hoje
    código inalcançável.

- **2026-08-07** — **Auditoria F4 (autenticação e proteções) contra a especificação.** Build verde
  com **341 testes**. Os 10 requisitos estavam implementados: Argon2id com parâmetros justificados
  (OWASP config C) sob `DelegatingPasswordEncoder`, JWT RS256 com os 7 claims exigidos e chaves fora
  do repositório, refresh opaco de 256 bits guardado como SHA-256 com rotação e revogação de família,
  cadeia stateless com CSRF desabilitado e justificado, `@EnableMethodSecurity` com principal
  próprio, rate limit 5/100/300 com `Retry-After`, bloqueio progressivo 10/15min, os cinco headers,
  aspecto de auditoria e validação com senha mínima de 12 e lista de 148 senhas comuns. Os cinco
  testes pedidos existiam, inclusive o que inspeciona o log.
  - **Um defeito real: oráculo de TEMPO no login.** O código trazia o comentário "usa comparação em
    tempo constante... mesmo quando o usuário não é encontrado (dummy hash)", mas a expressão era
    `usuario != null && passwordEncoder.matches(...)` — o `&&` curto-circuita e o KDF **não rodava**
    para email inexistente. Medido contra a API em execução: **~6 ms contra ~68 ms**, 10x de
    diferença. A mensagem genérica exigida pelo requisito 7 estava correta e era inútil: o relógio
    respondia "esta conta existe". Pior que a ausência da defesa, havia um comentário afirmando que
    ela existia.
  - **Correção:** hash dummy calculado uma vez na construção do bean; o KDF passa a rodar nos dois
    caminhos. Medido depois: ~69 ms contra ~71 ms — o degrau sumiu. Fica o resíduo de usuários do
    seed com `{bcrypt}` contra `{argon2}` das senhas novas, que distingue a IDADE do hash e não a
    existência da conta; está documentado no javadoc.
  - **Teste novo, `EnumeracaoUsuarioTest`, deliberadamente NÃO cronometrado.** Um teste que compara
    tempos seria instável — GC, JIT e runner de CI produzem ruído da mesma ordem do sinal, e o
    limiar necessário para não dar falso positivo não pegaria a regressão. Em vez disso, verifica
    com `@MockitoSpyBean` que `matches` é invocado no caminho do email inexistente: determinístico,
    e falha exatamente se alguém reintroduzir o `&&`.
  - `docs/seguranca/autenticacao.md` corrigida junto: o diagrama de sequência anotava
    `findByEmail [leitura constante-time]`, afirmação que nunca foi verdadeira, e a tabela de
    ataques tratava enumeração de usuários como resolvida só pela mensagem. Agora separa as duas
    dimensões, corpo e tempo, com a medição registrada.
  - SpotBugs pegou `DMI_RANDOM_USED_ONLY_ONCE` na primeira versão da correção (o `SecureRandom` era
    alocado e usado uma única vez no construtor). Resolvido trocando a fonte por `UUID.randomUUID()`
    em vez de suprimir o aviso: a entrada do hash dummy não é segredo, só precisa gastar CPU.

- **2026-08-07** — **Auditoria F3 (modelo de dados) contra a especificação.** Build verde com **338
  testes**. O schema passou íntegro: as 15 tabelas, colunas e tipos batem com o modelo pedido, os IDs
  são UUID da aplicação (zero `@GeneratedValue`), todo temporal é `timestamptz`/`Instant`, dinheiro é
  `numeric(12,2)`/`BigDecimal` e tokens `bigint`. GiST presente em `missao.origem` e
  `ponto_custodia.ponto` (e em `checkin.ponto`, de bônus). Enums: 11 `@Enumerated`, todos STRING,
  nenhum ordinal. `@Version` nas três entidades pedidas. **Zero relacionamento JPA no projeto
  inteiro** — nenhum `@ManyToOne`/`@JoinColumn`, mais restritivo que a regra 7 exigia. Seed com 3
  tribos, 6 usuários, 12 missões, 5 pontos de custódia (a LOJA é "Leroy Merlin Pinheiros"), domínio
  corretamente reescrito para reforma e logística reversa, sem resíduo do protótipo Flutter, com
  peso e volume nas 4 ENTREGA e coordenadas reais de Pinheiros/Vila Madalena. As 6 carteiras
  semeadas reconciliam com o ledger.
  - **Um gap real na regra 4: o `REVOKE` é inerte.** O papel `omnitribo_app` está corretamente
    restrito a `SELECT, INSERT` nas tabelas append-only, mas o datasource conecta como `omnitribo`,
    dono das tabelas, para quem GRANT/REVOKE não valem. Medido: como `omnitribo_app`,
    `UPDATE lancamento` responde `permission denied`; como `omnitribo`, altera as 8 linhas. A
    "defesa em profundidade" que o comentário SQL descreve não está ligada. Registrado nas
    Pendências; fechar exige apontar o datasource para `omnitribo_app` e dar ao Flyway um usuário
    com DDL.
  - **Dois testes reforçados.** O de idempotência afirmava `DataIntegrityViolationException`, a
    superclasse que também cobre NOT NULL, CHECK e FK — derrubar `uk_lancamento_idempotencia`
    deixaria o teste verde desde que o INSERT falhasse por qualquer outro motivo. Passou a
    `DuplicateKeyException` conferindo o nome da constraint. E o `REVOKE` não tinha assertion
    nenhuma: `MigracaoTest` agora trava a matriz de privilégios das quatro tabelas append-only.
  - Desvio de nomenclatura aceito e já documentado: `V9__seed_dev` virou `V900__seed_dev` em
    `db/seed` (ver nota de 2026-08-06). `entrega_falida` não é semeada — a tese do produto ("entrega
    que falhou vira missão") não tem dado de exemplo, embora as 4 ENTREGA apontem para pontos de
    custódia.

- **2026-08-07** — **Auditoria F0–F2 contra a especificação original, e conserto do merge.** Build
  verde com **337 testes**, 0 falhas/erros (eram 333, e antes disso o build não existia). Quatro
  testes novos, todos de defeito real encontrado — nenhum escrito para subir cobertura.
  - **`develop` não compilava.** O merge de `feat/f5-carteira-economia` sobre `feat/f6-geolocalizacao`
    (PR #8 sobre #6) resolveu o conflito mantendo os CORPOS de método de uma branch e o CONSTRUTOR da
    outra: `MissaoService` declarava 5 dependências e usava 9; `ExpiracaoMissoesService` usava
    `estornoFinanciamentoService` sem campo. 10 erros de compilação, CI vermelho, nenhum teste
    rodando. A correção é a união das duas listas — as duas versões estavam intactas em
    `git show feat/f5-carteira-economia:<arquivo>`. **Lição registrada no CLAUDE.md:** merge de duas
    branches de fase que tocam o mesmo serviço exige `./mvnw verify` DEPOIS do merge; as duas versões
    compilam isoladas e só o resultado combinado quebra.
  - **Um conflito de REGRA que o build quebrado escondia.** Com o build restaurado,
    `MissoesProximasTest` falhou: publica missão TRIBO com `tokensRecompensa: 10` e sem pote
    financiado, o que a F5 passou a recusar com 422. A regra da F5 está certa (conservação do TOKEN);
    o teste é anterior a ela. Corrigido o **teste**, não a regra — a fixture passou a criar a missão
    TRIBO com recompensa 0, já que o que ela mede é o filtro do radar por categoria.
  - **`type` do RFC 9457 nunca era preenchido** — a F2 pedia o campo explicitamente e toda resposta
    de erro saía `about:blank`, deixando o cliente apenas com o número do status. Novo catálogo
    `compartilhado/api/TipoProblema` com URIs estáveis. Descoberto no processo que havia **três**
    caminhos produtores de erro, não um: o `GlobalExceptionHandler`, os ~15 handlers herdados do
    `ResponseEntityExceptionHandler` — `POST /missoes` com `{}` devolvia
    `{"detail":"Failed to read request",...}`, sem `type` e sem `traceId` — e os escritores manuais
    de JSON em `SecurityConfig` e `RateLimitFilter`, que rodam na cadeia de filtros. Os herdados
    foram cobertos por um override de `createResponseEntity`, e não por 15 overrides, para que um
    handler novo do Spring já nasça dentro do contrato. `instance` também faltava na resposta de
    validação — a mais frequente da API.
  - **Mojibake em todo 401, 403 e 429.** `setContentType("application/problem+json")` não define
    charset; o servlet caía em ISO-8859-1 e `"Autenticação necessária"` ia para o cliente em Latin-1
    rotulado como JSON, que é UTF-8 por definição (RFC 8259 §8.1). Atinge exatamente os erros que o
    app mais recebe — token expira a cada 15 min. Invisível para teste que só confere status code.
  - **`GET /actuator/health` respondia 401**, contrariando a prova pedida na F2. O
    `anyRequest().authenticated()` da cadeia principal alcançava a porta de gestão, e o
    `show-details: when-authorized` ficava sem sentido por não existir caminho anônimo. Nova
    `actuatorFilterChain` com `@Order(1)`: `health` e `info` anônimos, `metrics` ainda autenticado.
  - **Armadilha operacional descoberta ao subir o app:** com o seed em V900, toda migration nova é
    *out-of-order* num banco de dev já existente, e o boot morre com `Validate failed: Detected
    resolved migration not applied to database: 12` — mensagem que não menciona seed nem ordenação.
    `make reset` é a resposta. Registrado no CLAUDE.md.
  - Verificado contra o Maven Central que **Spring Boot 4.1.0** e **springdoc 3.1.0** seguem sendo as
    releases atuais, e no Docker Hub que **postgis/postgis:16-3.5** é a tag corrente da linha 16.
    Banco recriado do zero por `make reset`: PostGIS 3.5.2 sobre PostgreSQL 16.9, 13 migrations
    aplicadas em ordem.

- **2026-08-07** — **F6 — Geolocalização.** Build verde com **248 testes**, 0 falhas/erros (eram 187).
  Radar de proximidade, check-in geolocalizado com validação 100% servidor, cache Caffeine e trilha
  antifraude append-only.
  - **`GET /api/v1/missoes/proximas`** — `ST_DWithin` + `ST_Distance`, raio default 2000 m e máximo
    20000 m, ordenado por distância crescente, distância em metros no DTO. **Uso do índice GiST
    provado**: `Index Scan` em `idx_missao_origem` sobre 200 mil linhas, com `ANALYZE` e sem tocar
    em `enable_seqscan`. Saída real em `docs/evidencias/f6-explain-analyze.md`.
  - **Uma única classe com PostGIS**: `compartilhado/infra/ConsultasGeoespaciais`. A regra "um repo
    geo por módulo" do ADR 0002 não sobreviveu à segunda consulta — as duas consultas da fase estão
    em módulos diferentes e a regra ArchUnit é direcional, então `ST_*` acabaria em dois arquivos.
    Ver **ADR 0007**. Os stubs `CheckinGeoRepository` e `PontoCustodiaGeoRepository` foram apagados.
  - **Cache Caffeine** com chave por geohash de precisão 7 (~153 m) + raio + categoria + limite, TTL
    30 s, invalidado **depois do commit** (`TransactionSynchronization.afterCommit`) — invalidar
    dentro da transação deixaria uma leitura concorrente repopular com estado pré-commit e a entrada
    obsoleta sobreviveria o TTL inteiro. **Cinco** pontos de invalidação, não dois: `criar`,
    `atualizar` (PATCH move `origem` de missão ABERTA sem mudar status), `aplicar`, `registrarCheckin`
    e `expirarLote` — este último chama a máquina de estados direto, sem passar por `aplicar`.
  - **Check-in**: `V12` acrescenta `chave_idempotencia` (UNIQUE) e `suspeito` a `checkin`. A chave
    guardada é `sha256(usuario|missao|chave_do_cliente)`, não a chave crua — a UNIQUE é global, e a
    chave crua deixaria o cliente que manda `"1"` receber replay do check-in alheio.
  - **A rejeição é gravada E o 422 é devolvido.** A primeira versão fez isso com `REQUIRES_NEW`, e
    estava errada: a transação externa segura `FOR UPDATE` sobre a missão enquanto a interna pede uma
    SEGUNDA conexão, então bastava concorrência ≥ tamanho do pool para travar a aplicação inteira —
    inclusive o login — com 30 s de timeout e 500 para todos. Só apareceu quando
    `CheckinConcorrenteTest` foi escrito, com 50 threads. **A correção não foi aumentar o pool**: o
    serviço passou a devolver a recusa como VALOR (`ResultadoRegistroCheckin`), a transação commita
    nos dois casos e o controller lança o 422 depois do commit. Uma transação, uma conexão, e de
    quebra o caminho aceito ficou atômico — antes havia uma janela em que a linha de check-in existia
    sem a transição correspondente.
  - **Dois bugs achados pelos próprios testes de integração, corrigidos no código e não na
    asserção:** (1) `Duration.toSeconds()` truncava, e dois check-ins a menos de 1 s davam velocidade
    nula — o teleporte **mais** implausível era o único não sinalizado; passou a milissegundos.
    (2) o cálculo em milissegundos expôs estouro de `velocidade_implicita_kmh` (`NUMERIC(10,2)`) num
    deslocamento intercontinental entre duas requisições HTTP: derrubava o check-in com 500 em vez de
    marcá-lo. Satura no máximo da coluna, porque o número é sinal, não medida.
  - **Ordem de checagens do check-in**: 403 → sondagem de idempotência → 409 → gravação. A sondagem
    fica entre o 403 e o 409 porque um replay legítimo chega com a missão já em
    `AGUARDANDO_CONFIRMACAO` e levaria 409; e depois do 403 porque antes dele um não-executor
    receberia dados da missão. Exigiu tornar `MissaoStateMachine.validarAutorizacao` pública.
  - `docs/seguranca/antifraude-geolocalizacao.md` registra o que os controles **não** pegam:
    spoofing com root/emulador é mitigável e não eliminável, `mocked` é reportado pelo cliente,
    presença não é execução, conluio não é detectado, e a cinemática é cega no primeiro check-in de
    cada conta.

- **2026-08-06** — Auditoria do `CLAUDE.md` contra o código, e correção das armadilhas que ela
  revelou. Build verde com **187 testes**, 0 falhas/erros — nenhum teste novo: a leva é de correção
  estrutural, não de comportamento.
  - **Seed fora da faixa de schema.** `V9__seed_dev.sql` e `V10__senha_prefixo_bcrypt.sql` viraram
    um único **`V900__seed_dev.sql`**. Versão de Flyway é sequência global, não por pasta: com o
    seed em V9/V10 no meio da faixa, um `V9__*.sql` novo em `db/migration` derrubaria dev e test
    com *"more than one migration with version 9"*, sem que o erro apontasse para `db/seed`. A
    faixa 900+ garante por construção que o seed é o último. Como consequência ele passou a rodar
    **depois** da V11 e precisou gravar dados em forma final: `'ABERTA'` no lugar de `'DISPONIVEL'`
    e `{bcrypt}` embutido no hash (era isso que o V10 fazia). Efeito colateral assumido: os
    `UPDATE` de renomeação da V11 não afetam mais nenhuma linha — ver ADR 0006, Notas de manutenção.
  - **Divergência Jackson entre main e test eliminada.** `MockMvcTestConfig` declarava um bean de
    `ObjectMapper` do Jackson 2 — justificado por uma hipótese não verificada ("JacksonAutoConfiguration
    pode não ser ativado") — enquanto a aplicação serializa com Jackson 3. A suíte afirmava sobre
    JSON parseado por uma major diferente da que o produz. O bean foi removido e os testes passaram
    a usar `TesteIntegracaoMvcBase.JSON`, um `JsonMapper` construído sem injeção, no mesmo padrão do
    `MissaoService.MAPPER_TRILHA`. Que o bean era dispensável já estava à vista: `TesteIntegracaoBase`
    nunca o importou e sempre fez roundtrip HTTP com JSON sem problema.
  - **`make up` sem `.env`** falhava (exit 1, `env file ... not found`). Alvo de arquivo `.env` no
    Makefile, pré-requisito de todos os targets. Medido no Compose v5.3.1: só `up` e `config`
    quebram — `down`, `logs` e `ps` operam sobre containers já rotulados e não precisam resolver a
    definição do serviço. A guarda elimina o passo manual num clone novo; não existe para decifrar
    mensagem de erro, que é explícita.
  - **Skill `/verificar`** quebrava no passo 2 porque `apps/mobile/` não tem `package.json` (F9+).
    Agora reporta NÃO VERIFICADO em vez de falhar. *(Superado em 2026-08-08: com F9–F11 entregues o
    `package.json` existe, a ressalva virou letra morta e o passo 2 voltou a ser incondicional.)*
  - Armadilha descoberta durante a execução, registrada no CLAUDE.md: **renomear migration exige
    `./mvnw clean`**. O Maven não remove de `target/classes` o arquivo com o nome antigo, o Flyway
    encontra os dois e aplica os dois — o sintoma é `duplicate key value violates unique constraint`,
    sem relação aparente com a renomeação.

- **2026-08-06** — Correção do CI e fechamento das lacunas de segurança da F4. O workflow `api.yml`
  nunca gerava as chaves RSA (`services/api/keys/` é gitignored), então o `@PostConstruct` do
  `JwtService` derrubava o contexto Spring e **todas** as classes de teste de integração falhavam no
  GitHub, embora passassem localmente. O javadoc do `JwtTestConfig` afirmava que `@Primary` protegia
  disso — não protege: `@Primary` só desempata injeção, o bean real continua sendo instanciado.
  Fechado também: `@Auditavel` nas 8 escritas de missão (a anotação existia mas não era usada em
  método nenhum, o aspecto era advice que nunca disparava), `entidade_id` na trilha via
  `RecursoAuditavel`, rate limit em `POST /auth/registrar` (era amplificador de DoS — cada chamada
  custa um hash Argon2id sem nenhum limite), e o `CorrelationIdFilter` registrado no `MockMvc`, que
  não herda filtros de servlet fora da cadeia do Security. Build verde com **187 testes**, 0
  falhas/erros (+11). Novos: `CabecalhosSegurancaTest`, `BloqueioProgressivoTest`,
  `AuditoriaMissaoTest`, `RegistroRateLimitTest`.
- **2026-08-06** — F3+F4 entregues juntas (branch `feat/f4-ciclo-vida-missoes`): máquina de estados
  de missão com 9 estados e 12 transições declaradas no próprio `StatusMissao`, trilha append-only
  gravada na mesma transação, autorização anti-IDOR com ator sempre vindo do JWT, e aceite
  concorrente serializado por lock pessimista. Decisões em
  [`adr/0006-maquina-estados-missao.md`](adr/0006-maquina-estados-missao.md). Build verde com
  **176 testes**, 0 falhas/erros — 118 deles cobrindo a matriz completa de status × evento.
  Três endpoints publicam contrato e respondem 501 até suas fases: `checkin` (F6), `confirmar` e
  `resolver` (F7). Evidência: [`qualidade/verificacao-2026-08-06.md`](qualidade/verificacao-2026-08-06.md).
- **2026-08-05** — Verificação completa pós-F2: build verde (19 testes, 0 falhas/erros). Dois
  *warnings* de build corrigidos — exclusão de `UserDetailsServiceAutoConfiguration` (senha-dev morta
  a cada boot) e extração do `@TestConfiguration` aninhado para top-level (forward-compat Spring
  Framework 7.1). Relatório com evidência: [`qualidade/verificacao-2026-08-05.md`](qualidade/verificacao-2026-08-05.md).
