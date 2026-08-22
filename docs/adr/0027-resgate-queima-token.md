# 0027 — O resgate QUEIMA token: a economia vira ciclo, não estoque

**Data:** 2026-08-22
**Status:** Aceito

---

## Contexto

O ADR 0009 §3 decidiu que o resgate em benefício de parceiro é o **sumidouro** do TOKEN — e não
atribuiu a decisão a nenhuma fase. Medido antes desta implementação: `grep` por
`resgate|cupom|beneficio|parceiro` em `services/api`, `db/migration` e `db/seed` devolvia **só
comentário**. Nenhuma tabela, nenhum endpoint, e `RESGATE` não existia no CHECK de
`lancamento.motivo`. `apps/mobile/app/beneficios.tsx` era vitrine servida por um catálogo hardcoded.

Sem sumidouro, a economia tinha **entrada e circulação, mas nenhuma saída**:

| Operação | Efeito na oferta |
|---|---|
| `APORTE_PATROCINADOR` (ADR 0024) | **cresce** — credita sem contraparte |
| `FINANCIAMENTO_*`, `RECOMPENSA_MISSAO`, `TRANSFERENCIA_*`, `ESTORNO` | **constante** — movem de lugar |
| *(nada)* | **encolhe** |

Uma moeda que só pode crescer não é moeda comunitária: é um placar. E o argumento de defesa oral do
projeto — "a soma é conservada" — descrevia um estoque fechado que nunca foi o desenho pretendido.

---

## Decisão

**Adotamos `POST /api/v1/resgates` como o sumidouro, e o lançamento dele QUEIMA.**

O débito é registrado com `MotivoLancamento.RESGATE`, **sem `contraparte_carteira_id` e sem
`missao_id`**. Nenhuma linha credita ninguém. É exatamente isso que o separa de uma transferência,
onde as duas pernas somam zero.

Com ele, a economia fecha como **ciclo**:

```
  aporte do patrocinador  →  pote da missão  →  carteira do executor  →  resgate
       EMITE                    move               move                   QUEIMA
```

### A mudança de enunciado, que é o ponto (§2)

A invariante que o projeto vinha afirmando —
`SUM(carteira.saldo_tokens) + SUM(missao.pote_tokens)` constante — **deixa de valer para o sistema**.
Ela passa a ser:

> **Constante dentro do CICLO DE MISSÕES.** Sobe no `APORTE_PATROCINADOR`, desce no `RESGATE`, e não
> muda em mais lugar nenhum.

Isso **não é regressão**. É a diferença entre um estoque fechado e uma economia com entrada e saída,
e as duas pontas são endpoints explícitos, auditados e idempotentes — não efeitos colaterais.

`ConservacaoTokensTest` e `FinanciamentoControllerTest` continuam válidos sem alteração: eles medem
ciclos de missão, onde nada é emitido nem queimado.

### A reconciliação continua verde durante a queima, e isso é o argumento (§3)

`GET /admin/carteiras/reconciliacao` responde `integro=true` antes, durante e depois de um resgate —
verificado na evidência. Não é falha: a queima escreve os **dois** lados, o lançamento e a projeção,
como qualquer operação legítima.

É a terceira vez que o projeto tropeça na mesma distinção, e por isso vale registrar de novo:
**reconciliação (ledger × projeção) e conservação (soma do sistema) são invariantes diferentes**, e a
primeira passa enquanto a segunda muda. Foi assim com o estorno na expiração, com a cunhagem de
ENTREGA, e é assim aqui — com a diferença de que agora a mudança é intencional.

---

## Benefício nunca se expressa em reais (§4)

`beneficio.tipo` é `BEM` ou `PERCENTUAL`. Não existe um terceiro valor, e não pode existir.

Um benefício anunciado como "R$ 10 de desconto por 30 tokens" publica uma **cotação implícita**: quem
lê descobre quantos tokens valem dez reais, e a partir daí o catálogo inteiro é uma tabela de câmbio.
O ADR 0009 §6 recusa a cotação token→real porque **token conversível é dinheiro**, com KYC e
enquadramento regulatório junto — declarado fora de escopo.

`PERCENTUAL` é seguro porque é **proporção, não valor absoluto**: "20% na revisão" não diz quanto
custa a revisão.

A regra tem **duas camadas**, no padrão do projeto:

1. `@Pattern` em `CadastrarBeneficioRequest` → **400** com o campo apontado;
2. `ck_beneficio_sem_reais` (V24) → barreira final, para que um `INSERT` direto não contorne a borda.

A fronteira de palavra (`\y` no POSIX do PostgreSQL, `\b` no Java) é deliberada: sem ela, "realmente"
seria reprovado, e a regra viraria ruído que alguém desligaria.

---

## O código de retirada NÃO é credencial (§5)

Oito caracteres de um alfabeto sem `0/O` e sem `1/I/L`, gerados por `SecureRandom`.

**Não há HMAC nem assinatura aqui de propósito.** Quem autoriza a baixa de um resgate é um ADMIN,
pelo id — o código serve para o humano do balcão casar o papel na mão do cliente com a linha na tela.
Inventar criptografia para um identificador de balcão lhe daria aparência de credencial, e alguém
acabaria confiando nisso para autorizar alguma coisa.

`SecureRandom` mesmo assim é higiene, não segurança do resgate: um `Random` comum é previsível a
partir de poucas saídas, e um código adivinhável convidaria a tentar a sorte no balcão.

O alfabeto sem ambiguidade visual custa dois símbolos de espaço amostral (sobram 31⁸ ≈ 2,5×10¹²
combinações) e compra a coisa que mais falha nesse cenário: alguém lendo em voz alta para outro
alguém digitar, num balcão movimentado.

---

## Sem caminho de volta (§6)

`PATCH /admin/resgates/{id}` faz **só** `PENDENTE → UTILIZADO`, e é idempotente.

Reverter um resgate significaria **ressuscitar token já queimado**, isto é, emitir moeda fora do
aporte do patrocinador — exatamente o que o ADR 0024 concentrou num ponto único e auditado. Se um dia
for preciso desfazer um resgate (parceiro fechou, benefício não entregue), isso merece ADR próprio,
motivo de lançamento próprio e decisão explícita sobre de onde o token ressuscitado vem.

---

## Consequências

**Positivas:**

- A economia passa a ter as três operações que uma moeda precisa: emissão, circulação e destruição —
  todas em endpoints explícitos.
- `ResgateBeneficioTest` é o primeiro teste do repositório que afirma que a circulação **diminui**.
  Todos os outros afirmam que ela não muda.
- A tese do produto fecha: o token que o vizinho ganhou entregando uma encomenda vira café na padaria
  da esquina, e o comércio local entra no ciclo.
- O catálogo geoespacial reusa `ConsultasGeoespaciais` inteiro — nenhum `ST_*` novo fora daquela
  classe, e a distância continua derivada a cada consulta.

**Negativas / trade-offs:**

- **A afirmação de conservação ficou mais longa de explicar.** "A soma é constante" virava uma frase;
  agora são duas, com as exceções nomeadas. É o preço de a economia ser real.
- **A paginação do catálogo por proximidade é feita em memória**, sobre um teto de 50 parceiros. Uma
  consulta paginada por SQL teria de reproduzir a ordenação por distância dentro do próprio SQL, e aí
  a regra "todo `ST_*` numa classe só" (ADR 0007) cairia. O teto mantém a lista pequena por
  construção, mas é um limite que ninguém mediu contra carga.
- **Não há endpoint para cadastrar PARCEIRO.** Benefícios nascem por `POST /admin/beneficios`
  apontando para um parceiro existente, e parceiros vêm do seed. Em produção seria preciso mais um
  endpoint; ficou fora porque não foi pedido, e está registrado aqui em vez de descoberto depois.
- **O app não mudou.** `beneficios.tsx` continua servido pelo catálogo hardcoded — ligar a vitrine à
  API é trabalho seguinte.
- **Parceiro não é usuário do sistema**: não autentica, não tem carteira, não tem app. A baixa é feita
  por um ADMIN. Dar credencial a cada comércio do bairro seria um onboarding inteiro, e o balcão real
  funciona com alguém do Omni-Tribo confirmando.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| Resgate como TRANSFERÊNCIA para uma carteira do parceiro | Não seria sumidouro: o token continuaria na soma do sistema, só que numa carteira que nada gasta. A oferta seguiria crescendo, e o "sumidouro" seria um depósito com outro nome. Pior, criaria um titular com saldo que ninguém sabe o que fazer — e a pergunta "o parceiro pode transferir de volta?" não tem resposta boa. |
| `beneficio.tipo = VALOR` com preço em reais | Publica uma cotação token→real implícita: o catálogo inteiro vira tabela de câmbio. Token conversível é dinheiro, com KYC junto (ADR 0009 §6). |
| Código de retirada com HMAC ou assinatura | Daria aparência de credencial a um identificador de balcão, e alguém acabaria usando isso para autorizar. Quem autoriza é o ADMIN, pelo id. |
| Permitir reverter um resgate (`UTILIZADO → PENDENTE`, ou cancelamento com crédito) | Ressuscitaria token queimado — emissão fora do ponto único que o ADR 0024 estabeleceu. Merece ADR próprio se um dia for necessário. |
| Filtro de catálogo combinando proximidade E tribo | Dois critérios de pertencimento sobre o mesmo conjunto: o resultado é indistinguível do mais restritivo, e ninguém saberia qual dos dois recortou. |
| Paginar o catálogo geoespacial por SQL | Exigiria `ST_Distance` na cláusula de ordenação de uma query do módulo `carteira`, quebrando a regra de que todo `ST_*` vive em `ConsultasGeoespaciais` (ADR 0007). |
| Um nono módulo (`beneficios`) | Os oito são fixos e criar módulo é decisão de ADR (precedente: `integracoes`, ADR 0011). `carteira` já é dona das operações de valor e abriga `SaqueService`, a saída em BRL desligada — o resgate é a saída em TOKEN, e o ledger fica junto do seu sumidouro. |
