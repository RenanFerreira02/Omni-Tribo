# Roteiro do vídeo-pitch — 5 minutos

**Este documento não substitui [`ROTEIRO-DEMO.md`](ROTEIRO-DEMO.md).** Os dois existem porque
atendem plateias diferentes: o de 10 minutos é a **arguição da banca**, onde mostrar o próprio erro
medido é o diferencial, e roda no terminal de propósito — `curl`, webhook, reconciliação. Este é o
**pitch gravado** da Atividade 4, e obedece a restrições que o outro não tem:

| Restrição do enunciado | Como este roteiro a cumpre |
|---|---|
| máximo 5 minutos | tabela abaixo, 20 blocos de 15 s, fechando em **5:00** |
| ≥ 3 min de demonstração funcional | faixa **1:00–4:15** = **3:15** de app em uso |
| AI Logistics Extension evidente na demo | bloco de **45 s** parado na tela de detalhe (1:45–2:30) |
| evitar código e detalhe de implementação | **nenhum bloco tem terminal em quadro** — ver "Como isso é possível" |
| produto concluído; limitação como escopo | um bloco de 15 s, escopo declarado, sem a palavra "defeito" |
| equipe (foto, nome, RM) e resposta FIAP NEXT | bloco de fecho, 4:45–5:00 |

Fio condutor, o mesmo do roteiro de 10 minutos porque o seed já o suporta: **tribo Cidade Líder**,
executor **`renan@omnitribo.dev`** (`db/seed/V903__seed_cidade_lider.sql`), benefícios dos parceiros
do bairro (`V906`).

---

## Como isso é possível sem terminal em quadro

Dois atos do ciclo são da **transportadora**, não do app: o reporte da entrega falida e a confirmação
de recebimento que credita o executor. Nenhum dos dois tem gatilho na interface — e isso é desenho,
não lacuna: o criador da missão de retirada é o usuário-sistema, e nenhum humano confirma, nem ADMIN
(ADR 0026).

`tools/demo/pitch-armar.sh` faz os dois **fora de quadro**, em duas fases:

1. reporta a entrega falida antes de você começar a gravar, e imprime o id da missão;
2. fica observando a missão e posta a confirmação no instante em que o **check-in acontece na tela**.

Você toca só no celular. O terminal existe, mas nunca é gravado — e o que ele faz é exatamente o que
uma transportadora integrada faria sozinha.

---

## Cronometragem

Frase-chave é **texto literal para falar**, não tópico. Blocos de 15 s.

| Tempo | Na tela | O que é dito |
|---|---|---|
| 0:00 | Slide de capa: nome do produto, RM, os dois links | "Omni-Tribo. Uma entrega que falhou vira missão comunitária remunerada — e é isso que eu vou mostrar funcionando nos próximos quatro minutos." |
| 0:15 | Slide: o problema, dois lados | "Quando o entregador não encontra ninguém, o pacote volta. O varejista paga re-entrega, armazenagem e o risco de perder o cliente. É caro, e acontece todo dia." |
| 0:30 | Slide: a tese | "Do outro lado da mesma rua tem gente que passaria em frente à loja de qualquer jeito. A nossa tese é que **a segunda tentativa de entrega é mais cara que uma missão de bairro** — então o custo do fracasso vira renda de vizinho." |
| 0:45 | App na mão, navegando até a aba **Avisos** | "Eu moro na Cidade Líder e uso o app como vizinho. Acabou de falhar uma entrega aqui perto." |
| 1:00 | **Avisos**: puxar para atualizar, o alerta novo entra no topo | "O aviso chega na caixa de entrada: **entrega difícil esperando na minha região** — o pacote está no locker aqui do lado, e quem levar recebe 71 tokens. Quem é avisado é quem está por perto, tem nível para aceitar e consentiu ser notificado." |
| 1:15 | Aba Missões (radar): a missão nova na lista, com a distância | "Vou no radar do bairro: a entrega frustrada já é uma missão aberta, a poucos metros de mim. Essa distância é calculada no banco a cada consulta — não é um campo que alguém digitou." |
| 1:30 | Toque no cartão → Detalhe da missão: recompensa em XP e token, complexidade | "Aqui está o que eu ganho, e o valor já vem **congelado**: o servidor calculou na hora em que a missão nasceu. A complexidade é derivada do peso e do volume reais da encomenda." |
| 1:45 | Detalhe, **parado**: o aviso de risco no alto | "E tem uma coisa que essa tela sabe e o entregador não sabia: **esta é uma entrega difícil.**" |
| 2:00 | Dedo apontando o aviso, texto inteiro visível | "Quando a transportadora avisa que falhou, o sistema estima a chance de a próxima tentativa falhar também — e diz ao vizinho o que fazer para dar certo desta vez: combinar o horário antes de ir." |
| 2:15 | Rolagem curta até a linha do multiplicador, no bloco de recompensa | "E a estimativa mexe no bolso: a linha **'inclui 1.45× por risco de falha na entrega'** é o adicional que essa missão paga por ser mais difícil. Missão difícil paga mais, sempre dentro de um teto." |
| 2:30 | Botão Aceitar → Iniciar; o endereço completo aparece | "Eu aceito. Só agora o endereço completo aparece — antes disso, quem não participa vê só o bairro." |
| 2:45 | Botão Check-in, diálogo de confirmação | "Chego ao locker e faço o check-in. A posição vem do GPS do aparelho e é validada no servidor." |
| 3:00 | Estado muda para "aguardando confirmação" | "Quem valida a distância é o servidor, não o app — e **presença não é recebimento**: quem confirma que a encomenda chegou ao destinatário é a transportadora, não eu. Eu não posso me pagar." |
| 3:15 | Aba Carteira, puxar para atualizar | "A transportadora confirmou que a encomenda chegou. Olha o saldo." |
| 3:30 | Carteira: saldo novo e a linha no extrato | "Recompensa creditada, com a linha no extrato. Esse histórico é só-inclusão: correção aqui é estorno, nunca apagar linha." |
| 3:45 | Botão "Resgatar benefícios" → catálogo do bairro | "E o token vale algo de verdade no bairro: café na padaria da praça, remendo na bicicletaria, desconto na feira. Parceiros reais do quarteirão." |
| 4:00 | Folha do benefício → código de retirada e saldo restante | "Resgatei. O código de retirada é o que eu mostro no balcão — e esse token **sai de circulação**, não volta para ninguém. Fecha o ciclo: nasceu do patrocínio da transportadora e terminou num pão na chapa." |
| 4:15 | Slide: escopo declarado | "Uma decisão de escopo ficou declarada: a última emissão de token do sistema é a entrega criada por um vizinho no próprio app, e ela emite porque ali não existe transportadora a debitar. Está registrada no ADR 0024, com a consulta que a lista — não é surpresa, é limite escolhido." |
| 4:30 | Print do painel de impacto, em slide | "O produto está concluído e mede a si mesmo: quantas entregas viraram missão, quantas foram concluídas, quanto de token circula. O custo evitado aparece como **premissa declarada**, com a mesma conta em mais e menos cinquenta por cento." |
| 4:45 | Slide de equipe: foto, nome completo, RM, links, FIAP NEXT | "Sou `<NOME COMPLETO>`, RM 555833, Sistemas de Informação. O código, os testes e as decisões estão no repositório do slide. **E sim: desejo expor o projeto no FIAP NEXT, em 24 de outubro.** Obrigado." |

**Fecho em 5:00.** O último bloco termina em 5:00 — é 20 × 15 s, e a conferência mecânica está na
seção "Como conferir".

---

## A demonstração funcional: 1:00 → 4:15

São **13 blocos = 3:15**, acima do mínimo de 3:00, e **todos numa tela do app**:

| Faixa | Tela do app | O que o espectador vê acontecer |
|---|---|---|
| 1:00–1:15 | `Avisos` | o alerta da entrega falida chegando |
| 1:15–1:30 | `Missões` (radar) | a missão de retirada nascida, com a distância |
| 1:30–1:45 | `Detalhe da missão` | recompensa congelada, complexidade derivada |
| 1:45–2:30 | `Detalhe da missão` | **a AI Logistics Extension — 45 s** |
| 2:30–2:45 | `Detalhe da missão` | aceitar, iniciar, endereço revelado |
| 2:45–3:15 | `Detalhe da missão` | check-in geolocalizado e a mudança de estado |
| 3:15–3:45 | `Carteira` | crédito e extrato |
| 3:45–4:15 | `Benefícios` | catálogo, resgate e código de retirada |

Nenhuma linha acima precisa de terminal. As duas ações da transportadora acontecem fora de quadro,
disparadas por `tools/demo/pitch-armar.sh` — a primeira antes de a gravação começar, a segunda
observando a missão e reagindo ao seu check-in.

**Uma armadilha de navegação:** **não toque no alerta** para ir à missão. O toque no aviso marca como
lido e **abre a missão direto**, pulando o radar — e o radar é a tela que mostra a proximidade
resolvida no banco, que é justamente o que o bloco 1:15 tem para dizer. Vá à aba Missões pela barra
de baixo e toque no cartão da missão.

---

## O bloco da AI Logistics Extension — 1:45 a 2:30 (45 s)

**Pare de navegar.** Os 45 segundos inteiros acontecem na tela de detalhe da missão, com duas coisas
visíveis, nesta ordem — é a ordem em que elas aparecem na tela, e ela é deliberada: o aviso fica
**antes** do bloco "Onde", porque quem decide se aceita precisa ler o alerta antes do endereço.

**1. O aviso, no alto.** O texto vem pronto do servidor — o app não compõe a frase. Com os valores
padrão de `pitch-armar.sh` (três tentativas anteriores, 19 h, portaria de condomínio) a faixa sai
**ALTO**, e o aviso é este:

> **Entrega com histórico de falha** — tom de atenção
> "Entregas neste endereço costumam falhar. Combine o horário com o destinatário antes de ir — é o
> que mais aumenta a chance de dar certo desta vez."

Se a faixa cair para **MEDIO**, o título e o texto mudam sozinhos, e a fala continua servindo:

> **Entrega com histórico irregular** — tom informativo
> "Esta entrega tem histórico irregular. Vale confirmar se há alguém para receber antes de sair."

**2. A linha do adicional, dentro do bloco de recompensa:**

```
Inclui 1.45× por risco de falha na entrega
```

### A frase de valor, numa respiração

> "Quando a transportadora avisa que falhou, o sistema estima a chance de a próxima tentativa falhar
> também — e paga mais por essa missão, porque ela é mais difícil. É o mesmo aviso que diz ao vizinho
> o que fazer para dar certo desta vez: combinar o horário antes de ir."

Ela evita "modelo", "regressão logística", "feature" e "acurácia" de propósito. Num pitch de 5
minutos, o jargão consome o tempo e não transfere valor: quem assiste precisa entender que **a
previsão mexe em dinheiro e em comportamento**, não como ela é calculada. A conta, os 14 sinais, os
coeficientes e os limiares estão em [`AI-LOGISTICS-EXTENSION.md`](AI-LOGISTICS-EXTENSION.md) e são o
que você defende na arguição, não aqui.

### Três coisas que NÃO se diz neste bloco

- **"1,45×" não é um número para decorar.** Leia o que a tela mostrar. O multiplicador é derivado da
  probabilidade, limitado a `[1,00; 1,50]`, e um dos insumos é o **clima consultado ao vivo** — duas
  gravações no mesmo dia podem sair diferentes. O `1.45×` foi **medido em 2026-09-14** com os valores
  padrão do script, num ciclo completo por HTTP: faixa `ALTO`, recompensa **71 tokens e 213 XP**, e o
  crédito na confirmação foi exatamente 71.
- **A tela escreve `1.45×`, com ponto** — é `toFixed(2)`, em `app/(app)/missao/[id].tsx:253`. Se você
  falar "um vírgula quarenta e cinco", ótimo; só não prometa uma vírgula na imagem.
- **Não afirme que o modelo prevê falhas no mundo real.** Os dados de treino são **sintéticos**, e
  isso está declarado em todo lugar do projeto. Se alguém perguntar, a resposta honesta é o
  diferencial: o mecanismo completo funciona ponta a ponta, e a validação com dado real é o próximo
  passo declarado (ADR 0022).

---

## O escopo declarado — 4:15 a 4:30

O roteiro de 10 minutos tem um bloco chamado *"A economia, e o defeito que a auditoria achou"*. **Ele
está certo lá e errado aqui.** Na arguição, contar que o projeto encontrou a própria impressora de
dinheiro e a fechou é a maior evidência de rigor que existe. Num pitch de 5 minutos, sem tempo para o
antes-e-depois, "defeito" é a última palavra que o espectador guarda — e a atividade pede o oposto:
produto concluído, com limitação apresentada como escopo.

Duas frases, e nenhuma delas mente:

> "Uma decisão de escopo ficou declarada: a última emissão de token do sistema é a entrega criada por
> um vizinho no próprio app, e ela emite porque ali não existe transportadora a debitar — quem pede
> não paga, e cobrar da tribo inverteria o modelo.
> Está registrada no **ADR 0024 §8**, com a consulta que a lista, e é candidata a decisão própria."

O que sustenta a honestidade dessas duas frases, se alguém puxar o fio: o ADR 0024 §8 diz literalmente
*"ENTREGA criada por humano também continua cunhando, pelo problema simétrico do varejista"*; a
emissão é **consultável** (`SELECT … WHERE fonte_pote = 'CUNHAGEM'`); e o adendo de
[`auditoria/entrega-final.md`](auditoria/entrega-final.md) traz o ciclo medido. Nada disso entra no
vídeo — entra na resposta.

---

## Por que o painel de impacto entra como print, e não ao vivo — 4:30

O painel é **restrito a ADMIN**: o endpoint é `GET /api/v1/admin/impacto`, e no app o botão "Painel
de impacto" só aparece para conta administradora — aberto por um morador, a tela responde *"só contas
administradoras enxergam os indicadores de impacto"*. O executor da demonstração é `renan`, um
vizinho, e trocar de conta em quadro custaria um login ao vivo justamente onde o bloqueio de cinco
tentativas por minuto mora.

Então o painel entra como **print, capturado fora da gravação** — e é por isso que ele está no
checklist:

1. logue como `admin@omnitribo.dev` num navegador ou num segundo aparelho;
2. abra o painel e tire o print **depois** de gravar o ciclo, para que os números incluam o resgate
   que você acabou de fazer;
3. aponte três coisas no slide: o funil `recebidas → convertidas → concluidas`, a
   `medianaAteCheckinSegundos`, e `custoEvitado` com a `premissaCustoReentregaBrl` ao lado.

**Diga em voz alta que o custo evitado é premissa, não medição.** A resposta traz `baseBrl` e as
variações `menos50Brl` / `mais50Brl` exatamente para que o número não seja lido como apuração — e
"re-entrega evitada" é a missão concluída renomeada, não uma segunda medição.

---

## O que NÃO entra nos 5 minutos, e onde responder se perguntarem

> **Nota sobre o financiamento comunitário (2026-09-22).** Este pitch mostra o pote do
> **PATROCINADOR** — é o caminho da entrega falida, e ele é o ato principal. O pote financiado por
> vizinhos, que é a mesma mecânica com outro pagador, **não aparece em quadro**, e desde o ADR 0035
> ele também deixou de aparecer sozinho no uso normal do app: AJUDA nasce valendo só XP e publica na
> hora. Se a banca perguntar "e a economia entre vizinhos?", a resposta rápida é o script
> `tools/evidencias/conservacao-por-categoria.sh`, que exercita TRIBO, COLETA e AJUDA financiadas e
> imprime Δ=0 em cada uma. **Nenhum ato do roteiro precisou mudar** — o pitch nunca criou missão
> pelo app.

Todo assunto cortado tem endereço. A coluna do meio é o bloco de
[`ROTEIRO-DEMO.md`](ROTEIRO-DEMO.md) que o cobre ao vivo.

| Cortado do pitch | Bloco do roteiro de 10 min | Documento |
|---|---|---|
| De onde o token vem — o aporte do patrocinador, único ponto de emissão explícito | **1:00–2:00** | [ADR 0024](adr/0024-carteira-de-patrocinador.md) |
| O pote COMUNITÁRIO — quem financia missão de vizinho, e por que nunca o criador | **6:00–8:00** | [ADR 0025](adr/0025-ajuda-paga-do-pote.md) · [ADR 0037](adr/0037-quem-cria-a-missao-nao-paga-vira-regra.md) |
| Missão comunitária que vale **só XP**, e por que ela não é uma quarta ponta da invariante | 6:00–8:00 | [ADR 0035](adr/0035-missao-comunitaria-sem-recompensa-em-token.md) |
| HMAC sobre o corpo bruto, os doze cenários do webhook, ponto lotado como 200 | **2:00–4:00** | [ADR 0021](adr/0021-verificacao-de-webhook-de-transportadora.md) |
| Por que o resgate não tem volta, e o código de retirada não ser credencial | 4:00–5:00 | [ADR 0027](adr/0027-resgate-queima-token.md) |
| Provedor externo fora do ar: 503, cache → disjuntor → bulkhead → retry | **5:00–6:00** | [ADR 0023](adr/0023-resiliencia-de-integracoes-externas.md) |
| Conservação nas quatro categorias, e a história do defeito econômico | **6:00–8:00** | [EVOLUCAO-ARQUITETURAL.md](EVOLUCAO-ARQUITETURAL.md) · [ADR 0009](adr/0009-economia-do-cuidado-token-como-recompensa.md) |
| Reconciliação × conservação: por que `integro=true` não bastava | 6:00–8:00 | [ADR 0032](adr/0032-diagnostico-de-pote-imobilizado.md) |
| Painel de impacto completo, e o custo evitado como premissa ±50% | 8:00–9:00 | [ADR 0029](adr/0029-painel-de-impacto-e-a-premissa-declarada.md) |
| Teste de carga, `EXPLAIN ANALYZE`, mutação, 100 threads na carteira | **9:00–10:00** | [f21-carga](evidencias/f21-carga.md) · [f6-explain-analyze](evidencias/f6-explain-analyze.md) · [mutacao](qualidade/mutacao.md) · [integridade-transacional](qualidade/integridade-transacional.md) |
| Os 14 sinais do modelo, limiar 0,19, matriz de confusão, Brier | 9:00–10:00 | [AI-LOGISTICS-EXTENSION.md](AI-LOGISTICS-EXTENSION.md) · [modelo-previsao](qualidade/modelo-previsao.md) |
| Monólito modular, fronteira entre módulos verificada por ArchUnit | — (pergunta provável) | [ADR 0001](adr/0001-monolito-modular.md) · [arquitetura-alvo](diagramas/arquitetura-alvo.md) |
| Antifraude do check-in: o que ele **não** pega | — (pergunta provável) | [antifraude-geolocalizacao](seguranca/antifraude-geolocalizacao.md) |
| Por que não há push remoto, dark mode, i18n, cotação token→real | — (pergunta provável) | `CLAUDE.md`, "Fora de escopo, decidido" |

A tabela "Perguntas prováveis, e onde a resposta está" do roteiro de 10 minutos continua valendo
inteira — inclusive *"sua acurácia não é menor que a de um chute?"*, que é a pergunta mais provável
depois deste vídeo.

---

## De onde você grava decide se o check-in passa

**A origem da missão de retirada é a coordenada do ponto de custódia**, e o servidor exige o aparelho
a menos de **200 m** dela (`app.missoes.entrega-falida.raio-checkin-m`). A distância é medida pelo
PostGIS, no servidor — o app não tem voto. Os pontos do seed vivem na zona leste de São Paulo, então
gravar de qualquer outro lugar reprova o check-in com a mensagem de
`AvaliacaoAntifraude.MOTIVO_DISTANCIA`:

```
Você está a <N> m da origem da missão; o raio permitido é 200 m.
```

O `<N>` é a distância real medida no servidor — e ela não precisa ser grande para reprovar. Num caso
medido em 2026-09-15, o ponto de gravação estava a **210 m** do LOCKER Cidade Líder: dez metros
além do raio, com o aparelho praticamente encostado no ponto do seed.

Duas coisas que NÃO resolvem, e é melhor saber antes de tentar:

- **Nenhuma variável do `pitch-armar.sh`.** Quem manda a posição é o GPS do aparelho; o script só
  assiste. (Ele *tinha* `CHECKIN_LAT`/`CHECKIN_LON` copiadas do `carrier-mock`, onde o próprio script
  faz o check-in por HTTP. Eram variáveis mortas e foram removidas.)
- **App de mock de GPS.** `AvaliacaoAntifraude.avaliar` rejeita antes de qualquer outra checagem
  quando `mocked` é true, com motivo `LOCALIZACAO_SIMULADA`. É a primeira cláusula do método.

### A solução: traga a missão para você — uma vez, e só uma vez

```bash
bash tools/demo/ponto-aqui.sh <LAT> <LON> "Padaria da esquina"
```

Cria um ponto de custódia nas suas coordenadas, dentro da **Tribo Cidade Líder** — a mesma do
`renan`. No Google Maps, o clique com o botão direito no local dá o par `lat, lon` para colar,
**nessa ordem**.

Prefira um **comércio ou esquina** a menos de 200 m de onde você vai gravar, em vez da sua porta: o
modelo do produto é custódia comercial (ADR 0020), e o apelido do ponto aparece no app como "onde a
encomenda está".

Depois disso, **arme sem variável nenhuma**:

```bash
bash tools/demo/pitch-armar.sh
```

Ele lê `tools/demo/.env.ponto` — arquivo local, gitignored por `.gitignore:25` (`.env.*`), onde as
coordenadas ficam. **Elas nunca entram no git**, e é por isso que este ponto não é um seed da faixa
900: endereço de quem grava não é fixture pública.

E ele **recria o ponto sozinho** quando ele não está mais no banco, o que acontece depois de todo
`make demo`.

> **A ordem importa, e ela não é óbvia: o backend antes do ponto.** `make demo` recria o volume, e
> quem cria o schema é o **Flyway, no boot do backend** — o alvo não sobe backend nenhum, de
> propósito. Então logo depois de um `make demo` o banco tem só as tabelas de sistema do PostGIS, e
> criar o ponto ali devolve `ERROR: relation "ponto_custodia" does not exist` — mensagem que não diz
> em lugar nenhum que o que falta é subir o backend. Os dois scripts hoje detectam isso e dizem
> exatamente o que fazer; e o `make demo`, que chegou a tentar recriar o ponto ali, agora só **avisa**
> que ele morreu com o volume.

A sequência que funciona, e é a do checklist:

```
make demo  →  sobe o backend (Flyway migra)  →  pitch-armar.sh (recria o ponto e arma)
```

> **Isto já falhou, e vale saber por quê.** O `pitch-armar.sh` tinha um padrão silencioso: sem
> `PONTO_CUSTODIA`, ele usava o LOCKER Cidade Líder. Então esquecer o `ponto-aqui.sh`, rodar
> `make demo` depois dele, ou esquecer de colar a variável que ele imprimia davam **todos** o mesmo
> sintoma — missão criada, alerta entregue, radar mostrando, e o check-in reprovado por distância já
> com a câmera ligada. O padrão foi removido: hoje o script **se recusa a armar** e diz o que fazer.

A fase 1 passou a imprimir a coordenada contra a qual o servidor vai medir o seu check-in, lida do
banco:

```
  OK     desfecho=CONVERTIDA  missão d4b40782-…
         o check-in exige o APARELHO em: -23.54500, -46.63900  (raio 200 m)
```

**Confira essa linha antes de pegar o telefone.** Se ela mostrar `-23.55650, -46.46850`, a missão
nasceu no locker do seed e o check-in vai reprovar.

Três coisas que continuam funcionando ao mudar o ponto, conferidas no código antes de eu afirmar:

| O quê | Por quê |
|---|---|
| o **alerta** do bloco 1:00 | o fan-out procura tribos por **distância mínima a qualquer ponto da tribo** (`SQL_TRIBOS_NO_RAIO`, ADR 0020), não até um centro. O ponto novo é um ponto da tribo, então a distância é **0 m** e a Cidade Líder entra no raio de onde quer que você esteja |
| **aceitar** a missão | não há checagem de tribo em `MissaoService` nem na máquina de estados |
| o **catálogo e o resgate** | a tela filtra pela tribo do usuário (`beneficios.tsx:66-68`), não por proximidade — os parceiros da Cidade Líder continuam listados |

### O que eu NÃO recomendo

Afrouxar `raio-checkin-m` no perfil de dev. Funciona, e nenhum teste dourado guarda esse número —
mas o roteiro diz em voz alta, no bloco 3:00, que *"quem valida a distância é o servidor"*. Gravar
isso com um raio de 50 km torna a frase verdadeira e vazia ao mesmo tempo, e é o tipo de coisa que
uma pergunta da banca desmonta.

---

## Checklist de gravação

### Na véspera, ou de manhã

- [ ] `make demo` com o backend **parado** — banco do zero, sem lixo de ensaio. Ensaio gasta vaga do
      ponto de custódia, saldo do patrocinador e token no resgate.
- [ ] `cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` num terminal
- [ ] `cd apps/mobile && npm start` noutro
- [ ] `bash tools/demo/checar-ambiente.sh` — **todos os ✓** (cobre ping, banco, chaves, JDK, Node e a
      8080 vista da LAN, que é o que o celular usa)
- [ ] **se você não vai gravar em pé no locker do seed**, crie o ponto onde você está — **uma vez, e
      só uma vez**:
      ```bash
      bash tools/demo/ponto-aqui.sh <LAT> <LON> "Padaria da esquina"
      ```
      Depois disso `make demo` e o `pitch-armar.sh` o recriam sozinhos. Ver "De onde você grava".

### Uma vez, no app, antes de gravar

- [ ] **Faça login como `renan@omnitribo.dev`** (`Senha@123`) e **deixe logado**.
- [ ] **Perfil → Privacidade: ligue `NOTIFICACAO` e `LOCALIZACAO`.** Isto não é capricho, é o passo
      que **já falhou numa verificação**: o seed não dá consentimento nenhum ao `renan`
      (`V903__seed_cidade_lider.sql` não insere linha em `consentimento`), o fan-out **exige os
      dois**, e sem eles o alerta simplesmente não chega — a caixa de entrada grava sem novidade e o
      bloco 1:00–1:15 morre. Medido em 2026-09-14: mesmo webhook, duas vezes; sem consentimento a
      caixa não recebeu nada, com os dois ligados o alerta entrou no topo. Ver "A medição".
- [ ] Confira que a aba **Avisos** já tem o aviso antigo do seed ("Nova missão a 170 m de você").
      É o contraste: o alerta novo entra **acima** dele, e a lista visivelmente muda.
- [ ] Deixe um navegador logado como `admin@omnitribo.dev` para o print do painel de impacto do bloco
      4:30 — o print sai **depois** da gravação, para incluir o resgate. Ver a seção do 4:30.

### Nos 60 segundos antes de apertar REC

- [ ] telefone no modo não perturbe, bateria acima de 50%, brilho alto
- [ ] **NÃO faça login ao vivo.** O bloqueio antifraude é de **5 tentativas por minuto**: um erro de
      digitação custa 60 segundos de silêncio, com a câmera ligada.
- [ ] app aberto na aba **Perfil** (ou Carteira) — qualquer aba que **não** seja Avisos, para
      que a primeira coisa gravada seja você navegando até lá
- [ ] rode, fora de quadro:
      ```bash
      bash tools/demo/pitch-armar.sh
      ```
      **Leia a linha "o check-in exige o APARELHO em"** que ele imprime, e confira que é onde você
      está. Se o script recusar, ele diz o comando que falta.
- [ ] espere a fase 1 imprimir `OK  desfecho=CONVERTIDA` e **conte ~15 segundos** — a outbox varre a
      cada 10 s, e o alerta só existe na caixa de entrada depois dessa varredura
- [ ] **deixe o script rodando.** A fase 2 fica observando a missão e confirma sozinha no instante em
      que você faz o check-in na tela. Fechar o terminal mata a confirmação, e o bloco 3:15 grava uma
      carteira que não mudou.

### Durante

- [ ] fale a frase-chave da tabela; ela está escrita para caber em 15 s
- [ ] no bloco 1:45–2:30, **pare o dedo**: nada de rolar a tela enquanto explica o risco
- [ ] leia da tela o multiplicador que ela mostrar — não o `1.45×` deste documento
- [ ] no check-in, esteja de fato dentro dos 200 m da origem, ou o servidor reprova (é ele que
      valida, não o app)

### Se algo der errado no meio

| Sintoma | Causa provável | Saída |
|---|---|---|
| alerta não chega em Avisos | consentimento faltando, ou a outbox ainda não varreu | espere 10 s e puxe de novo; se não vier, vá direto para a aba Missões — a missão existe |
| a missão não aparece na aba Missões | modo "Perto" sem permissão de localização | toque em "Todas" |
| check-in reprovado por distância | a missão nasceu em outro ponto | confira a linha "o check-in exige o APARELHO em" que a fase 1 imprimiu; se não for onde você está, rode `tools/demo/ponto-aqui.sh` e arme de novo |
| check-in reprovado por localização simulada | mock de GPS no aparelho | não há contorno: o servidor rejeita `mocked=true`. Use `ponto-aqui.sh` |
| carteira não creditou | a fase 2 do script morreu | confira o terminal fora de quadro; `TEMPO ESGOTADO` diz em que estado a missão parou |
| script diz `RECUSADA` | ponto de custódia lotado pelo ensaio | `make demo` de novo, com o backend parado |
| script diz `SEM_PATROCINIO` | patrocinador sem saldo para o pote | `make demo` de novo |
| `relation "ponto_custodia" does not exist` | banco recriado e backend ainda não subiu — o Flyway migra no boot | suba o backend e rode de novo; nada a consertar |
| script diz que o backend não respondeu | idem, ou porta 8080 ocupada | suba o backend; `ss -lptn 'sport = :8080'` mostra quem está lá |

---

## A medição — 2026-09-14

O ciclo do pitch foi executado inteiro por HTTP contra o backend de pé, com o
`tools/demo/pitch-armar.sh` nos valores padrão, para que nenhum número deste roteiro seja suposição.
As ações do app (aceitar, iniciar, check-in) foram feitas pelos mesmos endpoints que as telas chamam.

```
[1/2] reportando a entrega falida  rastreio BRPITCH1789412566
  OK     desfecho=CONVERTIDA  missão e85a1850-b67b-4ec4-b045-63034030b881
[2/2] aguardando o check-in do executor  (até 180s)
         ABERTA  0s … 21s
  OK     confirmação enviada  HTTP 200  creditados: 71
```

O que a tela de detalhe tinha para mostrar, no mesmo ciclo:

```json
{ "titulo": "Retirar e entregar: 1 caixa de piso laminado 1,5 m²",
  "status": "AGUARDANDO_CONFIRMACAO",
  "tokensRecompensa": 71, "xpRecompensa": 213,
  "multiplicadorRisco": 1.45, "faixaRisco": "ALTO",
  "avisoRisco": "Entregas neste endereço costumam falhar. Combine o horário com o destinatário
                 antes de ir — é o que mais aumenta a chance de dar certo desta vez." }
```

E o alerta que a caixa de entrada recebeu, depois de os dois consentimentos serem ligados:

```json
{ "tipo": "ENTREGA_FALIDA_DISPONIVEL",
  "titulo": "Entrega difícil esperando na sua região",
  "corpo": "Uma entrega falhou e o pacote está em LOCKER Cidade Líder. Leve ao destinatário e
            receba 71 tokens mais XP. Entregas nesse endereço costumam falhar — combine o horário
            antes de ir." }
```

**O que esta medição NÃO prova:** que a tela renderiza — ela mostra o que o servidor tinha para a
tela renderizar. A gravação em si é a verificação visual, e é sua. Os 71 tokens e o `1.45×` também
não são fixos: com outro clima, outro CEP ou outros valores de contexto, mudam — a regra do roteiro é
ler da tela.

---

## Como conferir que este roteiro cabe em 5:00

Três comandos, e a saída literal deles nesta versão do arquivo:

```bash
# 1. total de blocos: 20 × 15 s = 300 s = 5:00
grep -oE '^\| [0-9]:[0-9]{2} \|' docs/ROTEIRO-PITCH-5MIN.md | tr -d '| ' | tr '\n' ' '
# 0:00 0:15 0:30 0:45 1:00 1:15 1:30 1:45 2:00 2:15 2:30 2:45 3:00 3:15 3:30 3:45 4:00 4:15 4:30 4:45
# → 20 blocos contíguos, de 15 em 15 s; o último começa em 4:45 e fecha em 5:00

# 2. demonstração funcional: de 1:00 a 4:00 inclusive, fechando em 4:15
sed -n '/^| 1:00 |/,/^| 4:00 |/p' docs/ROTEIRO-PITCH-5MIN.md | grep -cE '^\| [0-9]:[0-9]{2} \|'
# 13    → 13 × 15 s = 195 s = 3:15, acima do mínimo de 3:00

# 3. nenhum bloco da cronometragem exige terminal
sed -n '/^## Cronometragem/,/^## A demonstração/p' docs/ROTEIRO-PITCH-5MIN.md \
  | grep -cE 'curl|bash tools|make |psql'
# 0     (e código de saída 1, que é o `grep` dizendo que não achou nada)
```

No comando 3, o `0` é a resposta certa: qualquer número maior significa que alguém pôs comando de
terminal dentro da faixa gravada, e a restrição da atividade caiu.
