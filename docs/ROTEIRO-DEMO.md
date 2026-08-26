# Roteiro de demonstração — 10 minutos

**Regra número um: nada é instalado, clonado ou compilado durante a demonstração.** Tudo abaixo
pressupõe o preparo da seção final já feito. Se o tempo apertar, corte o bloco 7 — ele é o único
opcional.

**O único bloco que depende de rede externa é o 5.** Todos os outros rodam contra `localhost`. Cada
bloco tem plano B.

O fio condutor é **um ciclo econômico completo, com uma pessoa só**: o patrocinador aporta → uma
entrega falha → nasce a missão → o vizinho faz check-in → a transportadora confirma e ele é creditado
→ ele resgata um benefício no bairro, e o token é queimado. Tudo na zona leste, tribo Cidade Líder,
com `renan@omnitribo.dev`.

---

## Antes de entrar na sala (15 min)

```bash
cd Omni-Tribo
bash tools/gerar-chaves-dev.sh          # idempotente: não faz nada se as chaves existem
make reset                              # banco limpo, seed reconstruído no boot

# terminal 1 — deixe rodando
cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# terminal 2 — deixe rodando
cd apps/mobile && npm start

# confirme, e só entre na sala depois de ver o pong:
curl -s http://localhost:8080/api/v1/ping
```

Guarde o token de ADMIN no terceiro terminal — os blocos 2 e 7 usam:

```bash
ADMIN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@omnitribo.dev","senha":"Senha@123"}' | jq -r .accessToken)

RENAN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"renan@omnitribo.dev","senha":"Senha@123"}' | jq -r .accessToken)
```

**Deixe abertos:** os dois terminais, um terceiro terminal livre na raiz do projeto, o navegador em
`http://localhost:8080/swagger-ui.html`, e o app já **logado como `renan@omnitribo.dev`**
(senha `Senha@123`).

> **Não faça login na frente da banca sem necessidade.** O bloqueio antifraude é de 5 tentativas por
> minuto: um erro de digitação no telefone custa 60 segundos de silêncio constrangedor.

---

## 0:00–1:00 · O problema, e por que os dois lados se resolvem juntos

Sem tela. Duas frases:

> "Entrega falida custa cara: o entregador não encontra ninguém, o pacote volta, e o varejista paga
> re-entrega, armazenagem e o risco de perder o cliente. Do outro lado da mesma rua, existe gente
> que passaria em frente a uma loja de qualquer jeito."
>
> "A tese do projeto é que **a segunda tentativa de entrega é mais cara que uma missão de bairro**.
> Então o custo do fracasso vira renda comunitária — é o mesmo evento resolvendo os dois problemas."

**Plano B:** nenhum. Não depende de nada.

---

## 1:00–2:00 · De onde o token vem — o aporte

Comece pela ponta que quase nenhum projeto mostra: **a emissão**.

```bash
curl -s -X POST http://localhost:8080/api/v1/admin/patrocinadores/77777777-0000-0000-0000-000000000950/aportes \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: demo-$(date +%s)" \
  -d '{"tokens":500}' | jq
```

```json
{ "patrocinadorId": "77777777-0000-0000-0000-000000000950",
  "lancamentoId": "37581294-8280-41da-b1fd-fe4398300cd6",
  "saldoTokens": 5500, "replay": false }
```

> "Este é o **único ponto de emissão de token do sistema inteiro**. Endpoint de ADMIN, auditado,
> idempotente. Antes ele não existia: a recompensa de ENTREGA e AJUDA era cunhada na conclusão, uma
> missão por vez, e ninguém conseguia somar quanto tinha sido emitido. A cunhagem não desapareceu —
> ela mudou de lugar, e é isso que a torna defensável."

**Plano B:** se o `curl` falhar, o backend caiu — é local. Suba de novo. Se o `Idempotency-Key`
repetir, a resposta vem com `"replay": true` e **nada é emitido**: mostre isso, é a idempotência
funcionando.

---

## 2:00–4:00 · A entrega falida vira missão, e o vizinho é pago

O coração do projeto, num comando:

```bash
EXECUTOR=renan@omnitribo.dev \
PONTO_CUSTODIA=cccccccc-0000-0000-0000-000000000902 \
CHECKIN_LAT=-23.55650 CHECKIN_LON=-46.46850 \
DESTINO_LAT=-23.55737 DESTINO_LON=-46.46987 \
bash tools/carrier-mock/enviar.sh
```

**Doze cenários** em poucos segundos, incluindo o ciclo completo. Comente **três** enquanto rolam:

| Cenário | O que dizer |
|---|---|
| caminho feliz → 200 | "a transportadora anuncia a falha; nasce uma missão de retirada, publicada no ponto de custódia, com o pote já financiado pela transportadora" |
| **ponto lotado → 200 RECUSADA** | "não é 4xx de propósito: devolver erro faria a transportadora reenviar em laço contra um ponto que continuará lotado. Recusar é desfecho de negócio, e fica registrado" |
| assinatura inválida → 401 | "HMAC sobre o **corpo bruto**, com o carimbo de tempo dentro do material assinado. As quatro causas de 401 são indistinguíveis — dizer qual metade o atacante acertou seria ajudá-lo" |

O bloco que fecha o argumento é o ciclo completo, e ele imprime o número sozinho:

```
        executor: renan@omnitribo.dev  saldo ANTES: 124 tokens
  ..  aceitar                                ACEITA
  ..  iniciar                                EM_ANDAMENTO
  ..  check-in                               AGUARDANDO_CONFIRMACAO
  OK   confirmação → executor creditado   HTTP 200
        saldo DEPOIS: 190 tokens  (creditados: 66)
  OK   saldo subiu exatamente a recompensa: +66
```

> "**Quem confirma é a transportadora, não o executor.** O check-in prova presença, não recebimento —
> confirmar ali faria o executor confirmar a si mesmo. E `CONCLUIDA` é o **único** estado que credita:
> aceitar não credita, que era exatamente o que o protótipo descartado fazia errado."

**Plano B:** o script é 100% local — só precisa do backend de pé. Se falhar, o backend caiu.
**Se o check-in reprovar por distância**, as quatro variáveis de coordenada acima estão erradas para
o ponto escolhido: a missão exige check-in a menos de 200 m da origem.

---

## 4:00–5:00 · Onde o token morre — o resgate

A outra ponta. Primeiro a vitrine:

```bash
curl -s "http://localhost:8080/api/v1/beneficios?triboId=aaaaaaaa-0000-0000-0000-000000000901" \
  -H "Authorization: Bearer $RENAN" | jq -r '.conteudo[] | "\(.custoTokens) tokens · \(.titulo) (\(.parceiroNome))"'
```

```
10 tokens · Um remendo de câmara de ar (Bicicletaria do Zé)
15 tokens · Um café coado e um pão na chapa (Padaria Pão da Praça)
25 tokens · Uma fornada de pão francês (500 g) (Padaria Pão da Praça)
30 tokens · 15% de desconto na feira da semana (Mercearia Dona Neusa)
40 tokens · 20% de desconto na revisão da bicicleta (Bicicletaria do Zé)
```

> "Nenhum benefício se anuncia em reais, e isso é barrado em duas camadas — a borda responde 400 e o
> banco tem `CHECK`. Preço em moeda corrente publicaria uma cotação token→real, que o ADR 0009 recusa
> explicitamente: token conversível *é* dinheiro, com KYC junto."

E o resgate:

```bash
curl -s -X POST http://localhost:8080/api/v1/resgates \
  -H "Authorization: Bearer $RENAN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: demo-resgate-$(date +%s)" \
  -d '{"beneficioId":"33333333-0000-0000-0000-000000000960"}' | jq
```

```json
{ "custoTokens": 15, "codigoRetirada": "NURE8YPY", "status": "PENDENTE",
  "saldoTokensRestante": 175, "replay": false }
```

> "**É aqui que o token é queimado.** O lançamento debita com motivo `RESGATE` e **não credita
> ninguém** — sem contraparte, sem missão. É o que o separa de uma transferência, onde as duas pernas
> somam zero. E o código de retirada **não é credencial**: quem autoriza a baixa é o ADMIN, pelo id."

**Plano B:** se o saldo não der, resgate o remendo de câmara de ar (10 tokens). Se o catálogo vier
vazio, o `triboId` está errado — é o da Tribo Cidade Líder, onde estão os parceiros do seed.

---

## 5:00–6:00 · Resiliência: o bloco que depende de rede

```bash
curl -s "http://localhost:8080/api/v1/enderecos/01310100" | jq
curl -s "http://localhost:8080/api/v1/clima?lat=-23.564&lon=-46.6934" | jq
```

> "O app nunca fala com ViaCEP ou Open-Meteo direto. Passa pela nossa fronteira, atrás de
> **cache → disjuntor → bulkhead → retry**. O retry roda **por dentro** do disjuntor, para que uma
> rajada de tentativas conte como uma única falha."

### Plano B — e ele é melhor que o plano A

**Se a rede da sala falhar, demonstre a falha de propósito.** Desligue o Wi-Fi e repita o `curl`:

```json
{ "type": "https://omnitribo.dev/problemas/servico-externo-indisponivel", "status": 503 }
```

> "É o comportamento projetado: 503 com um `type` estável, e a reação de UI é **esconder** o recurso
> — o app não mostra erro de clima, ele simplesmente não mostra clima. Provedor externo fora do ar
> nunca vira 5xx no registro de uma entrega falida, senão a transportadora reenviaria em laço."

**Ensaie este plano B.** Ele responde à pergunta "e se cair?" com uma demonstração em vez de uma
promessa.

---

## 6:00–8:00 · A economia, e o defeito que a auditoria achou

Este é o bloco que diferencia o projeto. No terminal livre:

```bash
bash tools/evidencias/conservacao-por-categoria.sh
```

Ele roda os quatro ciclos completos, mais um quinto sem patrocínio, e imprime ao final:

```
TRIBO    Δ=0  recompensa=38
COLETA   Δ=0  recompensa=35
AJUDA    Δ=0  recompensa=30
ENTREGA  Δ=0  recompensa=66  (pote pago pelo patrocinador)
conservação: baseline=10845  final=10845
reconciliação final: {"integro":true,"divergencias":0}
```

O roteiro de fala, em três tempos:

1. **"Uma auditoria deste projeto encontrou uma impressora de dinheiro."** Concluir ENTREGA ou AJUDA
   criava token do nada. Antes disso, o mesmo padrão no BRL levou o sistema de R$ 118 para R$ 1.618
   em três ciclos, e o saldo do criador não se moveu — ele nunca pagou.
2. **"E o endpoint de integridade dizia que estava tudo certo — corretamente."** A reconciliação
   compara saldo com o histórico da carteira. Cunhar escreve **os dois lados**, então a igualdade
   continua verdadeira. Ela responde a outra pergunta.
3. **"A distinção que aprendemos: reconciliação não é conservação."** Uma tem endpoint; a outra não.
   **Uma invariante que ninguém mede não está garantida.**

Feche mostrando que ela fechou, e o que sobrou:

> "Hoje as quatro categorias conservam. A emissão virou um ponto só — o aporte que vocês viram no
> começo — e o resgate virou o sumidouro. A soma não é constante: ela **sobe no aporte e desce no
> resgate**, e não muda em mais lugar nenhum. O que ainda cunha é ENTREGA criada por um humano, que
> não tem transportadora para debitar — e isso está declarado na linha da missão, não escondido."

**Plano B:** se o script falhar, os mesmos números estão em
[`evidencias/f14-conservacao-quatro-categorias.md`](evidencias/f14-conservacao-quatro-categorias.md),
já executados. Abra o arquivo.

---

## 8:00–9:00 · O painel que fecha o ciclo *(opcional)*

```bash
curl -s http://localhost:8080/api/v1/admin/impacto -H "Authorization: Bearer $ADMIN" | jq .tokens
```

```json
{ "aportados": 10500, "emCarteiras": 11108, "emPotes": 222,
  "emCirculacao": 11330, "resgatados": 15 }
```

> "`aportados` e `resgatados` são exatamente as duas pontas que acabamos de percorrer ao vivo — e
> `resgatados` era zero há cinco minutos. O painel agrega tudo na hora, sem tabela de agregação e sem
> cache: uma segunda fonte de verdade para números que existem para serem conferidos seria pior que a
> consulta a mais."

Vale dizer em voz alta o que o painel **não** é:

> "O custo evitado é uma **premissa declarada**, não uma medição — por isso a resposta ecoa o valor
> usado e traz a mesma conta com ele em ±50%. E 're-entrega evitada' é a missão concluída renomeada,
> não uma segunda medição."

**Plano B:** corte este bloco. É o único opcional.

---

## 9:00–10:00 · Qualidade: por que acreditar nos números

Mostre, sem rodar (o `verify` leva ~1 min e não cabe aqui):

| Abra | Diga |
|---|---|
| [`evidencias/f21-carga.md`](evidencias/f21-carga.md) | "14.967 requisições, **zero 5xx**. O radar não tem joelho até 74,6 req/s. E o achado não é a latência: é que o alerta de ponto lotado escreve 631 linhas idênticas sem teto — está registrado como pendência, não corrigido às pressas" |
| [`evidencias/f6-explain-analyze.md`](evidencias/f6-explain-analyze.md) | "`EXPLAIN ANALYZE` real provando uso do índice GiST — não é 'usamos índice', é a saída do planejador" |
| [`qualidade/integridade-transacional.md`](qualidade/integridade-transacional.md) | "100 threads, deadlock cruzado, rollback. E a seção **'o que esta fase NÃO garante'**" |
| [`qualidade/modelo-previsao.md`](qualidade/modelo-previsao.md) | "o modelo abre declarando que os dados são **sintéticos** — e o diagrama de confiabilidade responde 'é melhor que um chute?' com Brier: 17,4% do erro eliminado" |
| [`qualidade/mutacao.md`](qualidade/mutacao.md) | "teste de mutação sem gate: o número vai para o relatório, não para a porta. O valor está nos sobreviventes — quatro fronteiras de saldo sem teste no valor exato" |
| [`EVOLUCAO-ARQUITETURAL.md`](EVOLUCAO-ARQUITETURAL.md) | "**cinco dos sete defeitos da rodada F0→F7 eram invisíveis lendo o código**" |

Frase de encerramento:

> "O projeto não acertou de primeira. Ele mediu, encontrou o próprio erro e o corrigiu — e o que
> continua aberto está escrito, com o número medido do lado."

**Plano B — sem projetor:** os arquivos são Markdown e leem no GitHub pelo celular.

---

## Perguntas prováveis, e onde a resposta está

| Pergunta | Resposta curta | Documento |
|---|---|---|
| "Por que monólito e não microsserviços?" | Um time, um deploy, uma transação. A fronteira está pronta para extrair, e há ordem definida | [ADR 0001](adr/0001-monolito-modular.md) · [arquitetura-alvo](diagramas/arquitetura-alvo.md) |
| "A acurácia do seu modelo não é menor que um chute?" | É — e o Brier é 17,4% melhor que o do chute constante. Acurácia é a métrica errada em dado desbalanceado, e o documento mostra as duas | [modelo-previsao.md](qualidade/modelo-previsao.md) |
| "Quem garante que o token não é inflacionado?" | A emissão tem um ponto só, auditado; a conservação foi medida nas quatro categorias com Δ=0 | [ADR 0024](adr/0024-carteira-de-patrocinador.md) · [f14](evidencias/f14-conservacao-quatro-categorias.md) |
| "Cadê os 50 metros do brief?" | Divergimos, por três razões medidas — inclusive porque "está em casa" não é observável sem rastreamento contínuo | [ADR 0020](adr/0020-ponto-de-custodia-comercial-e-proximidade-por-tribo.md) · [divergências](DIVERGENCIAS-DOCUMENTACAO.md) |
| "Isso escala?" | Não como está, e o desenho de como escalaria está separado e marcado como não implementado. A carga medida é de uma máquina, 5 min por cenário | [arquitetura-alvo](diagramas/arquitetura-alvo.md) · [f21-carga](evidencias/f21-carga.md) |
| "Por que React Native e não nativo?" | Custo de demonstrar. E o que a escolha cobrou está listado | [comparativo](COMPARATIVO-TECNOLOGIAS.md) |
| "Como sei que o crédito de seis meses atrás estava certo?" | `versao_formula` e multiplicador ficam congelados na missão; há teste que falha se a calibração mudar sem subir a versão | [ADR 0009](adr/0009-economia-do-cuidado-token-como-recompensa.md) |

---

## Checklist de 30 segundos, antes de começar

- [ ] `curl http://localhost:8080/api/v1/ping` responde `pong`
- [ ] `$ADMIN` e `$RENAN` exportados no terminal livre
- [ ] app aberto e **já logado** como `renan@omnitribo.dev`
- [ ] terminal livre na raiz do projeto
- [ ] Swagger aberto numa aba
- [ ] `make reset` feito **hoje** (banco limpo, sem lixo de ensaio)
- [ ] telefone no modo não perturbe

> **Se você ensaiou, rode `make reset` de novo antes da apresentação.** O ensaio gasta o saldo do
> patrocinador, ocupa vagas do ponto de custódia e queima tokens no resgate — e o bloco 7 fica com
> `resgatados` diferente de zero antes de você resgatar ao vivo, que é justamente o efeito.
