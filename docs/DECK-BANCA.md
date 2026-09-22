# Deck da apresentação — conteúdo dos slides

Conteúdo, não diagramação. **Dez slides**, nesta ordem, cada um com título, no máximo quatro bullets
e a nota do apresentador. O PDF é montado à mão.

> **Falta colar duas coisas**, e elas estão marcadas no texto para não passarem em branco:
> - `<COLAR-LINK-YOUTUBE>` — slides 1 e 10. O vídeo ainda não foi publicado (o `README.md` declara
>   isso), então não há link a inventar.
> - `<NOME COMPLETO>` — slides 1 e 10, e a **foto** no slide 10. O RM já está preenchido: **555833**.

O slide 1 carrega os dois links por **exigência literal do enunciado** — vídeo no YouTube e
repositório. Não os mova para o fim.

Números neste deck vêm de arquivo versionado, com a fonte ao lado. Contagem de testes e evidência de
build **não entram**: envelhecem a cada PR, e um número errado na tela é pior que nenhum.

---

## Slide 1 · Capa

**Omni-Tribo — uma entrega que falhou vira missão comunitária remunerada**

- 🎥 Vídeo-pitch: `<COLAR-LINK-YOUTUBE>`
- 💻 Repositório: https://github.com/RenanFerreira02/Omni-Tribo
- `<NOME COMPLETO>` — RM 555833 — Sistemas de Informação, FIAP
- Enterprise Challenge · Leroy Merlin — SMART HAS & **AI Logistics Extension**

> **Nota do apresentador.** Os dois links são exigência do enunciado e ficam aqui, legíveis, antes de
> qualquer conteúdo. Diga o nome do produto e a frase da tese; não leia a URL em voz alta. Se o
> projetor cortar as bordas, tenha os links também no slide 10.

---

## Slide 2 · O problema

**Entrega falida é caro duas vezes**

- O entregador não encontra ninguém; o pacote volta ao centro de distribuição
- O varejista paga **re-entrega, armazenagem e o risco de perder o cliente**
- A segunda tentativa repete as mesmas condições que fizeram a primeira falhar
- Do outro lado da mesma rua, tem gente que passaria em frente à loja de qualquer jeito

> **Nota.** Dois lados de um mesmo evento, e é isso que autoriza o resto do deck. Não cite número de
> mercado: não temos dado próprio de operação, e número emprestado é a primeira coisa que uma banca
> pede a fonte.

---

## Slide 3 · A tese, e o ciclo

**O custo do fracasso vira renda de vizinho**

- A encomenda fica num **ponto de custódia** do bairro — locker, portaria, comércio parceiro
- A entrega frustrada nasce como **missão de retirada aberta**, com a recompensa já financiada
- O vizinho aceita, faz **check-in geolocalizado** e leva ao destinatário
- A **transportadora confirma** o recebimento; só então o executor é creditado

> **Nota.** Este é o fio do vídeo, na mesma ordem. Sublinhe a última linha: quem confirma é a
> transportadora, não o executor — check-in prova presença, não recebimento. É a regra que o
> protótipo descartado violava, creditando no aceite.

---

## Slide 4 · AI Logistics Extension — o que ela decide

**Prever se a próxima tentativa também vai falhar**

- Entra no instante em que a transportadora reporta a falha, **antes** de a missão nascer
- 14 sinais do caso: tentativas anteriores, janela de horário, tipo de endereço, histórico do CEP,
  peso, volume e **clima consultado ao vivo**
- Sai em três coisas: **quanto a missão paga**, a prioridade do alerta e o **aviso acionável** na
  tela de quem vai executar
- Modelo interpretável: cada previsão diz **quais fatores mais pesaram nela**

> **Nota.** Aqui está o diferencial do desafio, e é o slide que o vídeo mostra funcionando. Diga que
> o aviso **diz o que fazer** — "combine o horário antes de ir" —, não só o que temer: a orientação é
> o que efetivamente reduz a chance de a segunda tentativa falhar. Fonte:
> `docs/AI-LOGISTICS-EXTENSION.md`.

---

## Slide 5 · Como a previsão vira dinheiro

**Missão mais difícil paga mais — dentro de um teto**

- `multiplicador = 1,00 + 0,50 × probabilidade`, sempre em **[1,00; 1,50]**
- Entra na **base** da recompensa, junto da complexidade — nunca sobre o total
- **Piso 1,00:** risco nunca reduz recompensa, o que inverteria a tese
- Fica **congelado na missão**, junto da versão da fórmula

> **Nota.** Linear de propósito: "o dobro do risco paga o dobro do adicional" se explica numa frase,
> e qualquer curva exigiria defender o formato dela. Se perguntarem por que não multiplicar o total:
> escalaria também distância, peso e volume, e a recompensa explodiria justamente no caso extremo. O
> congelamento é a resposta para *"este crédito estava certo quando foi feito?"*. Fonte: ADR 0022.

---

## Slide 6 · A economia

**Três moedas, e uma invariante que é medida**

- **XP** reputação, não transferível · **TOKEN** moeda do bairro · **BRL** fora do ciclo de missões
- **Quem cria a missão não paga.** A recompensa é calculada pelo servidor e congelada na criação
- O token **entra** por aporte do patrocinador e **sai de circulação** no resgate de um benefício
- Publicar exige pote cobrindo a recompensa; cancelar ou expirar **estorna** a quem financiou
- Missão comunitária pode valer **só XP**: aí não há pote, publica na hora e token nenhum se move

> **Nota.** Se houver uma pergunta sobre inflação de moeda, é aqui: a emissão explícita tem um ponto
> só, auditado e idempotente, e o resgate é sumidouro real — debita sem creditar ninguém. A terceira
> ponta, a entrega criada por um vizinho, está no slide 9 como escopo declarado. Fontes: ADR 0009,
> 0024, 0027.
>
> **Se perguntarem pela missão de só XP** (ADR 0035): ela **não é uma quarta ponta** — não emite nem
> queima, não participa da invariante. É diferente de "muda a soma em zero", e o teste mede a
> diferença: Δ=0 **e** nenhum lançamento, porque só o Δ passaria igual se a missão cunhasse e
> queimasse o mesmo valor.
>
> **E se perguntarem quem financia o pote comunitário:** outros membros da tribo, nunca o criador —
> e desde o ADR 0037 isso é recusado pelo servidor com 422, não só afirmado. O pitch de 5 min mostra
> o pote do PATROCINADOR, que é o caminho da entrega falida; o financiamento entre vizinhos é o
> mesmo mecanismo com outro pagador, e `tools/evidencias/conservacao-por-categoria.sh` o exercita nas
> três categorias comunitárias.

---

## Slide 7 · Arquitetura

**Monólito modular, com a fronteira verificada por teste**

- Spring Boot 4.1 · Java 21 · PostgreSQL + **PostGIS** · Flyway · app **Expo / React Native**
- Oito módulos; um módulo só fala com outro por **porta pública ou evento**, e o **ArchUnit reprova o
  resto**
- Proximidade é resolvida no banco pelo PostGIS a cada consulta — distância nunca é armazenada
- Operação de valor sob lock pessimista, ledger **só-inclusão**: correção é estorno, nunca UPDATE

> **Nota.** Um time, um deploy, uma transação — e a fronteira já pronta para extrair serviço, com
> ordem definida no diagrama de arquitetura-alvo, marcado como não implementado. A regra de módulo
> não é convenção de revisão: é teste que quebra o build. Fontes: ADR 0001, 0007, 0008.

---

## Slide 8 · O que foi medido

**Número que ninguém mediu não entra**

- Carga local, três cenários: **14.967 requisições, zero 5xx, zero deadlock**; o radar não degrada
  até **74,6 req/s** (`docs/evidencias/f21-carga.md`)
- `EXPLAIN ANALYZE` real provando uso do índice GiST — saída do planejador, não afirmação
- Carteira sob **100 threads**, com deadlock cruzado e rollback verificados
- Teste de mutação nos dois domínios que guardam dinheiro e máquina de estados, **sem gate**: o valor
  está nos sobreviventes comentados

> **Nota.** O ponto deste slide é o método, não o número: cada linha tem comando, saída e uma seção
> "o que isto **não** garante". A medição é de uma máquina — bancada distribuída e SLO contratual
> estão declarados fora de escopo.

---

## Slide 9 · Escopo declarado

**O que ficou fora, e por quê**

- **Dados do modelo de risco são sintéticos** — 5.000 entregas geradas, correlações injetadas por
  nós. Validar com dado real é o próximo passo (ADR 0022)
- A **última emissão de token** é a entrega criada por um vizinho no app: emite porque ali não existe
  transportadora a debitar, e cobrar da tribo inverteria o modelo (**ADR 0024 §8**)
- Fora do MVP por decisão registrada: pagamento real e KYC, push remoto, cotação token→real
- Quatro instrumentos de diagnóstico são **consulta ativa**: eles mostram o problema, não avisam

> **Nota.** Este slide é ativo, não defensivo: um modelo honesto sobre dados sintéticos é defensável,
> e um apresentado como treinado em dado real desmonta na primeira pergunta. Cada item tem ADR com a
> alternativa descartada e o motivo. Se perguntarem *"sua acurácia não é menor que a de um chute?"* —
> é, e o **Brier é 17,4% melhor** que o do chute constante; acurácia é a métrica errada em dado
> desbalanceado (`docs/qualidade/modelo-previsao.md`).

---

## Slide 10 · Equipe e entrega

**`<NOME COMPLETO>` — RM 555833**

- *(foto do integrante)* · Sistemas de Informação — FIAP
- **FIAP NEXT, 24/10: sim, desejo expor o projeto.**
- 🎥 `<COLAR-LINK-YOUTUBE>` · 💻 https://github.com/RenanFerreira02/Omni-Tribo
- 34 decisões registradas em `docs/adr/`, com a alternativa descartada em cada uma

> **Nota.** Encerre com a resposta do FIAP NEXT dita em voz alta — é exigência do enunciado e não
> pode ficar só no slide. Frase de fecho: *"o projeto mediu, encontrou o próprio erro e o corrigiu —
> e o que continua aberto está escrito, com o número medido do lado."* Fonte da contagem de ADRs:
> `ls docs/adr/*.md | grep -v TEMPLATE | wc -l` → 34.
