# 0026 — A transportadora confirma a retirada: contraparte, não autoatendimento

**Data:** 2026-08-22
**Status:** Aceito

---

## Contexto

A missão de retirada nasce do webhook de entrega falida e tem o **usuário-sistema** como criador
(V21). `CONFIRMAR` exige `AtorEsperado.CRIADOR`, e `MissaoStateMachine.validarAutorizacao` resolve
isso como `ator.ehMesmo(missao.getCriadorId())` — **identidade, não papel**. Consequência: nenhum
humano confirma uma missão de retirada. Nem um ADMIN.

Na prática o desfecho sempre foi correto: `EXPIRAR_CONFIRMACAO` conclui **pagando o executor**,
porque o check-in geolocalizado é a evidência que o sistema aceita em todo outro caminho. Mas o
executor esperava `app.missoes.expiracao.prazo-confirmacao` — 72 horas — para receber, e numa
demonstração ao vivo o fluxo principal do produto simplesmente **não termina**. A tese é "uma entrega
que falhou vira missão comunitária remunerada"; sem a última palavra, ela vira "vira missão
comunitária, e o pagamento sai depois de amanhã".

---

## Decisão

**Adotamos um segundo webhook, `POST /api/v1/webhooks/transportadora/confirmacao`**, com a mesma
autenticação HMAC-SHA256 sobre o corpo bruto e a mesma idempotência por
`(transportadora, codigoRastreio)`. Ele aplica `CONFIRMAR` com ator SISTEMA e credita o executor na
hora.

### Por que a transportadora, e não autoconfirmação no check-in

A alternativa óbvia era concluir a missão no próprio check-in quando a origem é SISTEMA. **Ela está
errada, e o motivo não é técnico.**

O check-in prova **PRESENÇA**, não **RECEBIMENTO**. Ele diz que o executor esteve a menos de N metros
de um ponto — não que a encomenda mudou de mãos. Autoconfirmar ali faria o executor confirmar a si
mesmo: a única parte com interesse financeiro direto no crédito seria também a única a atestar que o
trabalho foi feito. Uma confirmação emitida pela parte interessada **não distingue entrega feita de
entrega alegada**, e some justamente a informação que ela deveria carregar.

A transportadora é a **contraparte com interesse oposto**: o patrocinador dela financia o pote, então
é dela o token que sai; e é ela quem responde ao destinatário se o pacote não chegar. Um ator que
paga e que é cobrado tem motivo para não confirmar o que não aconteceu. **É essa oposição que faz a
confirmação significar alguma coisa.**

O projeto já aplica exatamente esse princípio em outro lugar: `AtorEsperado.CANDIDATO` proíbe o
criador de aceitar a própria missão, com o comentário "aceitar a própria missão seria autonegócio: o
criador confirmaria a si mesmo e liberaria o crédito sem contraparte". Autoconfirmar no check-in é a
mesma falha, um passo adiante no ciclo.

### A varredura de prazo continua, e não virou redundância

`EXPIRAR_CONFIRMACAO` permanece intacto. Os dois caminhos têm papéis diferentes:

| | Quem dispara | Quando |
|---|---|---|
| `CONFIRMAR` por webhook | a transportadora | quando o destinatário recebeu |
| `EXPIRAR_CONFIRMACAO` | a varredura | quando a transportadora **não** confirmou em 72 h |

A rede de segurança é o que impede o executor de ficar refém de uma integração que parou de
responder. Remover a varredura porque "agora existe confirmação" transferiria o risco de silêncio da
transportadora para o bolso do vizinho.

---

## O que NÃO precisou mudar (§4)

**Nenhum ajuste de autorização, e nenhuma transição nova.** `AtorMissao.ehMesmo` compara `usuarioId`,
e o criador da missão de retirada **é** `UsuarioSistema.ID` — então
`AtorMissao(UsuarioSistema.ID, SISTEMA)` satisfaz `CRIADOR` por construção. É o mesmo ator que
`abrirMissaoDeRetirada` já usava para PUBLICAR desde a V21.

O conjunto de transições de `StatusMissao` continua com **17** entradas. A transição
`AGUARDANDO_CONFIRMACAO --CONFIRMAR--> CONCLUIDA` já existia; o que mudou foi quem consegue alcançá-la.

**A generalização perigosa não acontece por construção.** A porta resolve o `missaoId` a partir de uma
linha de `entrega_falida` — então ela só alcança missão de retirada. Missão criada por gente continua
exigindo o criador de carne e osso, e isso não depende de disciplina: depende de não existir caminho.

---

## Idempotência em duas camadas (§5)

1. **Cinta** — `entrega_falida.convertida_em` preenchido significa que a encomenda já saiu da
   custódia, carimbo que `BaixaCustodia.darBaixa` põe na conclusão. É o sinal de "já concluiu" que
   `logistica` possui sem cruzar fronteira, e devolve `replay: true` sem chamar `missoes`.
2. **Suspensório** — duas confirmações **simultâneas** passariam as duas pela checagem acima, porque
   nenhuma commitou ainda. Quem as separa é a sondagem da chave de idempotência sob
   `SELECT ... FOR UPDATE`, dentro de `concluirComCredito` — que já sondava antes de validar a
   transição, com o comentário "um retry de POST /confirmar numa missão já CONCLUIDA é a mesma
   operação, não conflito".

Nenhuma das duas basta sozinha: a primeira sem a segunda credita duas vezes sob corrida; a segunda
sem a primeira gasta um lock de escrita para responder a um retry.

---

## Por que 404, e não 200, quando não há missão (§6)

Rastreio desconhecido e entrega que nunca virou missão (ponto lotado, sem patrocínio) respondem
**404**. Isso **diverge do ADR 0021** de propósito, e a diferença é o que se ganha com a resposta:

- No ponto lotado, o 200 existe porque há um **fato novo a gravar** — a encomenda chegou, e a recusa
  precisa ficar registrada para a transportadora saber onde o pacote parou.
- Aqui não há nada a registrar. A recusa da conversão **já está** em `entrega_falida`, e um 200 diria
  "confirmado" para algo que não foi confirmado — pior que um erro, porque a transportadora baixaria
  a encomenda dos sistemas dela.

Missão em estado que não aceita `CONFIRMAR` (ninguém executou, ou foi cancelada) responde **409**,
que é o contrato do projeto para "não cabe neste estado, caberia em outro".

---

## Consequências

**Positivas:**

- O ciclo do produto fecha **no mesmo minuto**, e passou a ser demonstrável: `tools/carrier-mock/`
  agora vai da falha reportada ao crédito, com saldo antes e depois.
- **Sumiu o único `UPDATE` manual da evidência da F14.** O ciclo 4 de
  `tools/evidencias/conservacao-por-categoria.sh` recuava `estado_desde` por SQL e exigia subir o
  servidor com a varredura acelerada, tudo por causa desta lacuna. Agora fecha por HTTP.
- A trilha ganha um ato nomeado: quem ler `missao_evento` vê a justificativa "Transportadora
  confirmou o recebimento pelo destinatário, por webhook", e não confunde com confirmação humana.

**Negativas / trade-offs:**

- **Mais superfície sem JWT.** É o segundo endpoint de escrita autenticado só por HMAC. O risco é
  mitigado por reuso, não por disciplina: o `HmacWebhookFilter` cobre `/api/v1/webhooks/**` por
  prefixo, então o endpoint novo herdou verificação, janela de 5 minutos e teto por transportadora
  sem uma linha de configuração — mas quem acrescentar um webhook fora daquele prefixo perde tudo
  isso em silêncio.
- **A transportadora passa a poder liberar dinheiro.** Ela confirma, o executor é pago. É deliberado
  — é o dela que está sendo pago —, mas amplia o que um segredo HMAC vazado permite: antes, no
  máximo criar missões; agora, também concluí-las. A rotação de segredo continua sendo deploy
  (ADR 0021), e isso ficou mais caro do que era.
- **Confirmação indevida não tem desfazer.** `CONCLUIDA` é terminal e o crédito é append-only; a
  correção seria um estorno decidido por gente. Não há endpoint para isso, e não deve haver sem
  decisão explícita.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| Autoconfirmar no check-in quando a origem é SISTEMA | O check-in prova presença, não recebimento. O executor confirmaria a si mesmo, e a confirmação deixaria de distinguir entrega feita de entrega alegada. Mesmo vício que `AtorEsperado.CANDIDATO` já bloqueia no aceite. |
| Deixar um ADMIN confirmar | Um administrador do Omni-Tribo não tem como saber se o pacote chegou — ele não é contraparte de nada. Seria carimbo, não confirmação, e ainda exigiria alguém de plantão para o produto funcionar. |
| Afrouxar `AtorEsperado.CRIADOR` para aceitar papel SISTEMA em qualquer missão | Generalizaria para missão criada por humano, onde SISTEMA passaria a confirmar no lugar do criador. A porta resolve a missão a partir de `entrega_falida` justamente para que o alcance seja estrutural, não uma regra que alguém precisa lembrar. |
| Evento novo na máquina de estados (ex.: `CONFIRMAR_TRANSPORTADORA`) | Acrescentaria uma 18ª transição para expressar o mesmo fato — AGUARDANDO_CONFIRMACAO → CONCLUIDA com crédito. A diferença é de ATOR, e ator já é dimensão do modelo; duplicar a transição faria `ck_missao_evento_tipo` e todo teste de máquina de estados crescerem sem ganho. |
| Remover a varredura de prazo, já que agora há confirmação | Transferiria o risco de uma integração silenciosa para o bolso do executor. A varredura é a rede de segurança e continua sendo o único caminho quando a transportadora não responde. |
| Responder 200 para rastreio desconhecido, seguindo o ADR 0021 | Lá o 200 existe porque há fato novo a gravar. Aqui não há, e um 200 faria a transportadora baixar dos sistemas dela uma encomenda que ninguém confirmou. |
