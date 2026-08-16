# Roteiro de demonstração — 10 minutos

**Regra número um: nada é instalado, clonado ou compilado durante a demonstração.** Tudo abaixo
pressupõe o preparo da seção final já feito. Se o tempo apertar, corte o bloco 6 — ele é o único
opcional.

**O único bloco que depende de rede externa é o 5.** Todos os outros rodam contra `localhost`. Cada
bloco tem plano B.

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

**Deixe abertos:** os dois terminais, um terceiro terminal livre na raiz do projeto, o navegador em
`http://localhost:8080/swagger-ui.html`, e o app já **logado como `alice@omnitribo.dev`**
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

## 1:00–3:00 · O ciclo da missão, no app

No aparelho/emulador, já logado:

1. **Radar** — a aba de missões mostra as missões próximas, com **distância calculada pelo PostGIS**,
   nunca pelo cliente.
2. **Aceitar** uma missão → **Iniciar**.
3. **Check-in** — mostre a tela de check-in.

> "A distância é validada no servidor. Se eu forjar a coordenada no aparelho, o servidor recalcula
> por PostGIS e recusa. O que o controle **não** pega — emulador com root, conluio, presença que não
> é execução — está escrito em `docs/seguranca/antifraude-geolocalizacao.md`, e essa honestidade é
> parte da entrega."

4. **Confirmar** (como criador) → a carteira do executor credita.

> "`CONCLUIDA` é o **único** estado que credita. Aceitar não credita — era exatamente o que o
> protótipo descartado fazia errado."

**Plano B — emulador travou:** `npm run web` no terminal 2 e demonstre no navegador. Funciona, com
uma ressalva que vale dizer em voz alta: *"na web nada é persistido, porque o browser não tem
keystore — recarregar desloga, e isso é decisão, não defeito (ADR 0013)."*

**Plano B — radar vazio:** o seed `V903` popula a zona leste (CEP 08280-460). Se o dispositivo
estiver com localização de outro lugar, use a lista em vez do radar.

---

## 3:00–5:00 · A entrega falida vira missão — o coração do projeto

No terminal livre:

```bash
bash tools/carrier-mock/enviar.sh
```

Seis cenários em poucos segundos. Comente **três** enquanto rolam:

| Cenário | O que dizer |
|---|---|
| caminho feliz → 200 | "a transportadora anuncia a falha; nasce uma missão de retirada, publicada no ponto de custódia" |
| **ponto lotado → 200 RECUSADA** | "não é 4xx de propósito: devolver erro faria a transportadora reenviar em laço contra um ponto que continuará lotado. Recusar é desfecho de negócio, e fica registrado" |
| assinatura inválida → 401 | "HMAC sobre o **corpo bruto**, com o carimbo de tempo dentro do material assinado. As quatro causas de 401 são indistinguíveis — dizer qual metade o atacante acertou seria ajudá-lo" |

Depois mostre o que ficou gravado:

```bash
make psql
```
```sql
SELECT codigo_rastreio, risco_faixa, risco_probabilidade,
       risco_multiplicador, risco_versao_modelo
  FROM entrega_falida WHERE recusada_em IS NULL
 ORDER BY recebido_em DESC LIMIT 1;
```

> "O score do modelo de risco fica **congelado na linha**, junto com a versão do modelo. Daqui a seis
> meses ainda é possível explicar por que esta missão pagou o que pagou."

**Plano B:** o script é 100% local — só precisa do backend de pé. Se ele falhar, o backend caiu;
suba de novo. Não há dependência externa aqui.

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

Ele roda dois ciclos completos e imprime, ao final:

```
AJUDA  Δ=30  recompensa=30      ← cunhou do nada
TRIBO  Δ=0   recompensa=38      ← conservou
reconciliação final: {"integro":true,"divergencias":0}
```

O roteiro de fala, em três tempos:

1. **"Uma auditoria deste projeto encontrou uma impressora de dinheiro."** Três ciclos levaram o BRL
   do sistema de R$ 118 para R$ 1.618, e o saldo do criador não se moveu — ele nunca pagou.
2. **"E o endpoint de integridade dizia que estava tudo certo — corretamente."** A reconciliação
   compara saldo com o histórico da carteira. Cunhar escreve **os dois lados**, então a igualdade
   continua verdadeira. Ela responde a outra pergunta.
3. **"A distinção que aprendemos: reconciliação não é conservação."** Uma tem endpoint; a outra não.
   **Uma invariante que ninguém mede não está garantida.**

Feche admitindo o que continua aberto:

> "ENTREGA e AJUDA ainda cunham, porque a carteira de patrocinador não existe. Não é esquecimento:
> exigir pote para ENTREGA faria vizinhos custearem a logística do varejista, que é o inverso do
> modelo. Preferimos uma lacuna documentada a uma regra errada codificada — e o teto de 1,5× no
> multiplicador de risco existe justamente por causa dela."

**Plano B:** se o script falhar, os mesmos números estão em
[`evidencias/f13-conservacao-por-categoria.md`](evidencias/f13-conservacao-por-categoria.md), já
executados. Abra o arquivo.

---

## 8:00–10:00 · Qualidade: por que acreditar nos números

Mostre, sem rodar (o `verify` leva ~1 min e não cabe aqui):

| Abra | Diga |
|---|---|
| [`evidencias/f13-make-test.md`](evidencias/f13-make-test.md) | "637 testes no backend, 179 no mobile. O `verify` também barra por SpotBugs e por **dois gates de cobertura** — 80% global e 85% no domínio" |
| [`evidencias/f6-explain-analyze.md`](evidencias/f6-explain-analyze.md) | "`EXPLAIN ANALYZE` real provando uso do índice GiST — não é 'usamos índice', é a saída do planejador" |
| [`qualidade/integridade-transacional.md`](qualidade/integridade-transacional.md) | "100 threads, deadlock cruzado, rollback. E a seção **'o que esta fase NÃO garante'**" |
| [`qualidade/modelo-previsao.md`](qualidade/modelo-previsao.md) | "o modelo de risco abre declarando que os dados são **sintéticos**" |
| [`EVOLUCAO-ARQUITETURAL.md`](EVOLUCAO-ARQUITETURAL.md) | "dez auditorias; **cinco dos sete defeitos eram invisíveis lendo o código**" |

Frase de encerramento:

> "O projeto não acertou de primeira. Ele mediu, encontrou o próprio erro e o corrigiu — e o que
> continua aberto está escrito, com o número medido do lado."

**Plano B — sem projetor:** os arquivos são Markdown e leem no GitHub pelo celular.

---

## Perguntas prováveis, e onde a resposta está

| Pergunta | Resposta curta | Documento |
|---|---|---|
| "Por que monólito e não microsserviços?" | Um time, um deploy, uma transação. A fronteira está pronta para extrair, e há ordem definida | [ADR 0001](adr/0001-monolito-modular.md) · [arquitetura-alvo](diagramas/arquitetura-alvo.md) |
| "A acurácia do seu modelo não é menor que um chute?" | A resposta preparada está no documento, com matriz de confusão e a discussão falso positivo × falso negativo | [modelo-previsao.md](qualidade/modelo-previsao.md) |
| "Cadê os 50 metros do brief?" | Divergimos, por três razões medidas — inclusive porque "está em casa" não é observável sem rastreamento contínuo | [ADR 0020](adr/0020-ponto-de-custodia-comercial-e-proximidade-por-tribo.md) · [divergências](DIVERGENCIAS-DOCUMENTACAO.md) |
| "Isso escala?" | Não como está, e o desenho de como escalaria está separado e marcado como não implementado | [arquitetura-alvo](diagramas/arquitetura-alvo.md) |
| "Por que React Native e não nativo?" | Custo de demonstrar. E o que a escolha cobrou está listado | [comparativo](COMPARATIVO-TECNOLOGIAS.md) |
| "Como sei que o crédito de seis meses atrás estava certo?" | `versao_formula` e multiplicador ficam congelados na missão; há teste que falha se a calibração mudar sem subir a versão | [ADR 0009](adr/0009-economia-do-cuidado-token-como-recompensa.md) |

---

## Checklist de 30 segundos, antes de começar

- [ ] `curl http://localhost:8080/api/v1/ping` responde `pong`
- [ ] app aberto e **já logado**
- [ ] terminal livre na raiz do projeto
- [ ] Swagger aberto numa aba
- [ ] `make reset` feito **hoje** (banco limpo, sem lixo de ensaio)
- [ ] telefone no modo não perturbe
