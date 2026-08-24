# 0028 — Busca por handle exato, e por que o status code não é a defesa

**Data:** 2026-08-22
**Status:** Aceito

---

## Contexto

A tela de transferência pedia o **UUID do destinatário como texto**. Funcionava e era inutilizável:
ninguém sabe o próprio UUID, muito menos o do vizinho. Era a Pendência #3.

A saída óbvia — listar os membros da tribo — está **fechada por decisão anterior**, registrada no
javadoc de `identidade/api/TriboController`:

> Não expõe MEMBROS. Listar quem é de qual tribo daria a qualquer usuário autenticado um mapa social
> do bairro — e a transferência de tokens é restrita à mesma tribo, então essa lista também seria uma
> lista de alvos.

Essa decisão fica de pé. O problema era encontrar uma forma de resolver a usabilidade **sem**
reabri-la.

---

## Decisão

**`GET /api/v1/usuarios/busca?handle=` — match exato, case-insensitive, restrito à MESMA TRIBO de
quem pergunta.** Devolve quatro campos: `id`, `handle`, `nome`, `tribo`.

A assimetria com a listagem é o ponto: **quem já sabe o `@` do vizinho o encontra; quem não sabe não
descobre nada.** Um handle é algo que a pessoa te diz — no grupo do bairro, no balcão, pessoalmente.
A busca exata transforma esse conhecimento prévio em usabilidade sem criar um diretório.

Sustentado por `V27__handle_case_insensitive.sql`: `UNIQUE INDEX ... (LOWER(handle))`. O
`uk_usuario_handle` da V2 é case-SENSITIVE, então `alice` e `Alice` podiam coexistir — e a busca
exata teria duas respostas para o que o usuário digita como uma coisa só, ou uma resposta escolhida
pelo planner. Num ledger append-only, transferir para a conta errada vira estorno manual.

---

## O que de fato protege (§2)

Três defesas, **e nenhuma delas é um código de status**:

1. **Restrição à mesma tribo.** O endpoint só responde sobre o grupo a que quem pergunta já pertence.
2. **Match exato.** Sem prefixo, sem similaridade, sem "você quis dizer" — qualquer um dos três
   seria listagem com outro nome, alcançável por força bruta sobre um alfabeto pequeno.
3. **Teto próprio de requisições**, 12/min contra os 300/min do balde geral de leitura.

E vale dimensionar o que o endpoint revela, porque isso calibra o resto: **ele responde sobre a
própria tribo de quem pergunta** — o grupo a que a pessoa já pertence e cujos membros ela encontra no
mercado. O ganho de informação por consulta é pequeno por construção. O teto não existe para impedir
uma descoberta pontual; existe para impedir **colheita em massa**, que é o que transforma "descobri
que a Marlene está aqui" em "tenho a lista de @ do bairro para correlacionar com outras bases".

---

## 404 ou resposta vazia? O empate, e o desempate (§3)

O item pede o motivo, e o motivo começa por recusar o senso comum.

**A auditoria F0–F7 já mostrou que o raciocínio ingênuo sobre vazamento por status está invertido.**
A F4 (§5) registra que a especificação afirmava *"404 vaza existência"* e que a análise correta é a
oposta: quem vaza é o **403**, porque responder "proibido" confirma que o recurso existe. O que
protege não é o número escolhido — é **responder igual** nos casos que precisam ser indistinguíveis.

Aplicando isso aqui, há três casos a considerar:

| Caso | Precisa ser indistinguível de… |
|---|---|
| handle não existe | os outros dois |
| handle existe, outra tribo | os outros dois |
| handle existe, conta inativa/anonimizada | os outros dois |

**E aí a comparação entre 404 e 200-vazio dá empate.** Se os três casos respondem 404, são
indistinguíveis. Se os três respondem `200 {}`, também são. Nenhuma das duas opções vaza mais que a
outra — a propriedade que importa é a uniformidade, não o número. **Escolher 404 "porque é mais
seguro" seria teatro**, e é exatamente o tipo de afirmação que a F4 desmontou.

Como empatam em vazamento, a escolha se decide por outro critério, e o critério é o contrato com o
cliente:

- Isto é uma **busca por chave natural**, não uma consulta a coleção. Não há lista, paginação nem
  filtro — a pergunta tem no máximo uma resposta. `404` é a semântica de "essa chave não resolve".
- O app discrimina erro pelo `type` do RFC 9457, e `naoEncontrado` já está no catálogo. Com 404, a
  tela ganha uma reação de UI distinta sem inventar ambiguidade. Com `200 {}`, o cliente teria de
  distinguir "corpo vazio" de "achou" inspecionando campos — precisamente a adivinhação que o
  catálogo de `type` existe para eliminar.

**O que NÃO decidiu:** a ideia de que 404 esconde algo. Ele não esconde. O sucesso da busca já revela
existência — é para isso que ela serve. O endpoint é desenhado para vazar **exatamente um bit**, para
um membro da mesma tribo, com teto. Fingir o contrário seria pior que não ter escrito este ADR.

Isso está travado por teste: `BuscaHandleTest.outraTriboEInexistenteSaoIndistinguiveis` compara os
dois corpos byte a byte, descontando `traceId` e `instance`. Se alguém um dia "melhorar" a mensagem
de erro para distinguir as causas, o build fecha vermelho.

---

## Confirmação pelo nome, na tela (§4)

O app passou a exigir uma **ação explícita de buscar** — não busca enquanto digita, porque uma
requisição por tecla consumiria o teto em segundos e seria busca por prefixo na prática — e a
transferência só é possível **depois** que o nome e a tribo aparecem na tela.

Editar o `@` derruba o destinatário confirmado. Sem isso, alguém procuraria "marlene", conferiria o
nome, trocaria o texto para "jonas" e transferiria para a Marlene.

**A confirmação pelo NOME é o que justifica a tarefa inteira.** `lancamento` é append-only e a
transferência não tem volta: conferir "Marlene Souza · Tribo Cidade Líder" é a diferença entre um
erro de digitação e um estorno manual. Um UUID na tela não permite conferir nada — é justamente por
isso que a Pendência #3 chamava aquela tela de inutilizável.

---

## Consequências

**Positivas:**

- A Pendência #3 fecha sem reabrir a decisão de privacidade do `TriboController`.
- O campo de UUID **saiu**: `destinatarioId` virou dado interno, preenchido pelo resultado da busca.
- O índice funcional da V27 torna impossível a ambiguidade `alice`/`Alice`, que existia em silêncio
  desde a V2.

**Negativas / trade-offs:**

- **Quem não sabe o `@` do vizinho continua sem conseguir transferir.** É o preço deliberado de não
  ter diretório, e a saída para isso é social (a pessoa te diz o handle) ou um convite por link, que
  não está implementado.
- **O endpoint é um oráculo de existência, e assumimos isso.** Nenhum desenho o evita sem eliminar a
  funcionalidade; o que fizemos foi restringir o alcance (própria tribo), a forma (exato) e a taxa.
- **12/min é um número escolhido, não medido.** É folgado para o fluxo humano — digitar um `@`,
  conferir, transferir — e apertado para varredura, mas não há dado de uso real para calibrá-lo.
- **`handle` vira identificador público de fato.** Ele já era único, mas agora é o que uma pessoa
  passa para outra. Trocar de handle (não implementado) passaria a ter consequência social.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| Listar membros da tribo | Daria a qualquer autenticado um mapa social do bairro — e, como a transferência é restrita à mesma tribo, uma lista de alvos. É a decisão registrada no `TriboController`, e ela não foi reaberta. |
| Busca por prefixo ou por parte do nome | Listagem com outro nome: com poucas letras o atacante enumera a tribo inteira. A ausência de `like` está DENTRO da query, com javadoc, para não voltar numa "melhoria de usabilidade". |
| Similaridade / "você quis dizer" | Pior que prefixo: devolve resultado para quem digitou errado, o que é exatamente a capacidade de descobrir handles sem conhecê-los. |
| `200` com corpo vazio para não encontrado | Empata com 404 em vazamento (§3). Perde no contrato: obrigaria o cliente a distinguir "vazio" de "achou" inspecionando campos, em vez de ramificar pelo `type`. |
| `403` para handle de outra tribo | Reintroduz o oráculo que a F4 descreveu: 403 confirma existência, e comparar 403 contra 404 enumeraria os handles da cidade. |
| Sem teto próprio, usando o balde geral de leitura | 300/min são 18 mil tentativas por hora. O teto É a defesa contra colheita em massa; sem ele as outras duas não bastam. |
| Identidade de quem pergunta vinda da query | Tornaria a restrição de tribo decorativa: bastaria alegar ser de outra tribo para procurar em qualquer uma. Vem do JWT, como toda identidade neste projeto. |
