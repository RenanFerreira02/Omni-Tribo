# 0009 — Economia do cuidado: TOKEN como recompensa, BRL fora do ciclo de missões

**Data:** 2026-08-07  
**Status:** Aceito — substitui a tabela de moedas do [ADR 0004](./0004-tres-moedas.md)

---

## Contexto

O ADR 0004 definiu o BRL como "dinheiro real" e o vinculou às categorias ENTREGA e AJUDA. Essa
definição carregava uma premissa que **nunca foi a do produto**: a de que o criador da missão paga
por ela.

No Omni-Tribo, quem publica uma missão não paga nada. A recompensa é dimensionada pela complexidade
e pelo tipo da missão, e o que o executor recebe é reputação (XP) e moeda comunitária (TOKEN),
resgatável em benefícios do bairro — desconto em comércio parceiro, vale-presente e afins. A tese é
uma **economia do cuidado**, não a circulação de dinheiro entre vizinhos.

A divergência entre a premissa registrada e a premissa real produziu um defeito estrutural, e ele
não é hipotético. Medido contra a API em execução, com dois usuários e três ciclos completos do
fluxo feliz:

```
SUM(carteira.saldo_brl) do sistema ANTES:  R$   118,00
  ciclo 1 → executor: R$  565,00  |  criador: R$ 18,00
  ciclo 2 → executor: R$ 1065,00  |  criador: R$ 18,00
  ciclo 3 → executor: R$ 1565,00  |  criador: R$ 18,00
SUM(carteira.saldo_brl) do sistema DEPOIS: R$ 1618,00
```

R$ 1.500 criados do nada. O saldo do criador não se moveu em nenhum momento — ele não pagou, porque
o produto nunca previu que pagasse. Não havia escrow no publicar, e o único `DEBITO` de BRL do
sistema inteiro é o saque. Ou seja: **BRL só tinha saída, nunca entrada legítima**; todo real que
existia na aplicação foi cunhado numa conclusão de missão.

Dois fatos tornam isso mais grave do que parece:

1. **A reconciliação respondia `integro=true` o tempo todo — e estava certa.** Ela compara o ledger
   com a projeção de saldo, e os dois batiam perfeitamente: cada crédito era um `RECOMPENSA_MISSAO`
   bem formado, com chave de idempotência única. O que faltava não era consistência, era
   **conservação** — e o BRL não tinha invariante de conservação para violar. Nenhum endpoint de
   auditoria pegaria isso, por mais que fosse executado.

2. **O caminho estava fechado por acidente até a F6.** `AGUARDANDO_CONFIRMACAO` só era alcançável
   via check-in, que respondia 501. Quando a F6 implementou o check-in, o defeito passou a ser
   explorável sem que uma única linha do módulo `carteira` tivesse mudado.

O contraste com o TOKEN é o que aponta a saída. O TOKEN tem circuito fechado: membros financiam o
pote debitando a própria carteira, a conclusão paga **do** pote, e cancelar ou expirar estorna aos
financiadores. A invariante `SUM(carteira.saldo_tokens) + SUM(missao.pote_tokens)` foi medida de
ponta a ponta e fecha: **500 antes, 500 depois** de um ciclo criar → financiar → publicar →
cancelar.

---

## Decisão

**Adotamos o TOKEN como moeda de recompensa de todas as categorias de missão, e retiramos o BRL do
ciclo de missões.**

1. **Nenhuma missão remunera em BRL.** `ck_missao_economia` deixa de depender da categoria e passa a
   exigir `valor_brl = 0` para todas (V15). A validação de request devolve 400 apontando o campo,
   para que o cliente não receba um 500 de violação de constraint.

2. **A recompensa é XP + TOKEN, CALCULADA PELO SERVIDOR.** É derivada de categoria, complexidade,
   distância origem→destino, peso, volume e janela — nunca informada pelo cliente. O DTO de criação
   **não expõe** `xpRecompensa` nem `tokensRecompensa`; se vierem no corpo, são ignorados, pela mesma
   disciplina que já descarta `status`, `executorId` e `criadorId`.

   **Por que isso é parte desta decisão, e não detalhe de implementação:** recompensa escolhida pelo
   cliente é o mesmo vetor de abuso do `valor_brl` que este ADR fecha, transposto para o token — e
   pior, porque o token resgata benefício real de parceiro. Medido contra a API: uma missão AJUDA
   trivial, sem peso, sem volume e sem destino, foi criada com 5.000 XP e 1.000 tokens, o teto que a
   validação permite. Com ENTREGA e AJUDA ainda cunhando, isso é emissão sem contrapartida.

   O valor calculado é **congelado na criação** em `xp_recompensa`, `tokens_recompensa` e
   `versao_formula`, e **nunca recalculado na conclusão** — que lê o congelado. Sem a versão da
   fórmula, mudar a tabela de parâmetros amanhã reinterpretaria retroativamente toda missão já
   aceita: quebraria o acordo com quem aceitou, e tornaria impossível auditar se um crédito estava
   certo quando foi feito. É o mesmo princípio do `saldo_apos_*` no ledger, aplicado à origem do
   valor em vez de ao efeito dele.

   `POST /api/v1/missoes/previa-recompensa` devolve o cálculo sem criar nada, para que o app mostre o
   valor antes de publicar **sem duplicar a fórmula no cliente** — o que reabriria a divergência por
   outro caminho.

3. **O sumidouro do TOKEN é o resgate em benefício de parceiro.** É o que faltava: o pote *não* é
   sumidouro — é transferência que volta no cancelamento. Resgate retira token de circulação de
   forma permanente, em troca de algo fora do sistema. Com ele, a invariante vira:

   ```
   SUM(carteiras) + SUM(potes) + SUM(resgatado) == SUM(emitido)
   ```

4. **Quem financia o pote depende da natureza da missão:**
   - **TRIBO** — os próprios membros, debitando a carteira. É a economia do cuidado no sentido
     literal: a comunidade custeia o próprio mutirão. Já implementado.
   - **ENTREGA e COLETA** — o **patrocinador**. Essas são as missões de logística reversa que
     custam dinheiro ao varejista: entrega que falhou significa re-entrega, armazenagem no ponto de
     custódia e risco de perder o cliente. Patrocinar o pote é **mais barato que o custo do
     fracasso**, e é isso que responde à pergunta "quem paga por isso?". A carteira de patrocinador
     é F8.

5. **`saque` fica desligado por configuração** (`app.carteira.saque-habilitado: false`), não
   removido. Com o BRL fora das recompensas não há mais como ganhá-lo, e manter o endpoint aberto
   permitiria retirar saldo herdado do modelo anterior.

6. **Fronteira explícita sobre conversão em moeda corrente:** nesta etapa o TOKEN é resgatável por
   **benefícios de parceiros**, não por dinheiro. Conversão em moeda corrente depende de contrato de
   patrocínio e de enquadramento regulatório, e está fora do escopo. Se o token virasse conversível,
   ele *seria* dinheiro — e voltariam junto KYC, obrigação regulatória e tratamento de fraude com
   consequência financeira real.

---

## Consequências

**Positivas:**

- O defeito não é contornado, **desaparece**: não há o que escrowar quando ninguém deveria pagar.
- O TOKEN ganha o sumidouro que lhe faltava, e a economia passa a ter uma invariante de conservação
  auditável pelo mesmo endpoint de reconciliação que já existe.
- A pergunta difícil de qualquer banca — "quem banca isso?" — vira o argumento mais forte do
  projeto, porque a resposta é um caso de negócio real e não uma suposição.
- Resolve, pelo mesmo movimento, a pendência de ENTREGA/AJUDA cunharem token: a fonte passa a ser o
  pote patrocinado.
- Alinha o modelo ao challenge (Leroy Merlin — Sociedade 5.0 e Logística), em que a missão
  comunitária é um canal de última milha mais barato que a segunda tentativa de entrega.

**Negativas / trade-offs:**

- **Fica uma janela aberta até a F8.** ENTREGA e AJUDA recompensam em TOKEN, e enquanto a carteira
  de patrocinador não existir esses tokens são **cunhados** — a conservação vale para TRIBO e COLETA,
  não para o sistema inteiro. Isso está registrado nas Pendências do CLAUDE.md e **não foi
  contornado de propósito**: exigir pote de membros para ENTREGA faria a comunidade custear a
  logística do varejista, que é o inverso do modelo. Preferimos uma lacuna documentada a uma regra
  errada codificada.
- Saldos em BRL herdados do seed foram convertidos em TOKEN (taxa 1:2, só no seed) para que não
  reste dinheiro sem origem possível na base de demonstração.
- A tela de carteira do app passa a ter o TOKEN como moeda principal; o BRL vira zero e sem
  movimentação.
- `V900` mudou de conteúdo, então **base de dev existente exige `make reset`** — o mesmo requisito
  que qualquer migration nova já impõe por causa da faixa 900.

---

## Alternativas descartadas

| Alternativa | Motivo real da recusa |
|---|---|
| **Escrow do criador no publicar** (débito da carteira de quem publica, espelhando o pote) | Tecnicamente correto e simétrico ao TOKEN, mas resolve o problema errado: codifica exatamente a premissa que descobrimos ser falsa — a de que o criador paga. Além disso não fecharia sozinho, porque sem um ponto de entrada de BRL ninguém teria saldo para publicar, e a economia travaria. |
| **Escrow + endpoint de recarga de BRL** | Fecharia o circuito, mas exigiria que a recarga fosse restrita a ADMIN e auditável — caso contrário seria uma impressora com *menos* passos que a original. Custo alto para sustentar uma moeda que o produto não quer movimentar. |
| **Declarar o BRL fictício e apenas fechar o saque, mantendo-o como recompensa** | Metade da correção. O saldo continuaria crescendo do nada; só ficaria preso dentro do sistema. Uma banca perguntaria por que o número cresce sem lastro, e a resposta seria constrangedora. |
| **Remover as colunas `valor_brl`, `carteira.saldo_brl` e o `SaqueService`** | Descartaria infraestrutura pronta, testada sob concorrência, e que é exatamente a mecânica que a conversão patrocinada reaproveitaria. Remover schema por decisão de produto reversível é trabalho perdido nas duas direções. Ficam inertes, com a regra de negócio impedindo uso indevido. |
| **Tornar o pote obrigatório para todas as categorias já agora** | Fecharia a conservação imediatamente, mas o único financiador disponível hoje é o membro da tribo — o que significaria a comunidade pagando pela logística do varejista. Preferimos a lacuna documentada até a carteira de patrocinador da F8. |
| **Token conversível em BRL desde já** | Tornaria o token dinheiro de fato, trazendo KYC, enquadramento regulatório e fraude com consequência financeira para dentro do escopo de um MVP acadêmico. |
