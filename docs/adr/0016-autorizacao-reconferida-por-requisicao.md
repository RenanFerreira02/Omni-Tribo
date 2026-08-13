# 0016 — Autorização reconferida por requisição, com cache de conta

**Data:** 2026-08-11
**Status:** Aceito

---

## Contexto

Um access token vive 15 minutos e é autocontido: quem o tem prova que autenticou, não que a conta
ainda exista. `StatusUsuario.ATIVO` era verificado em exatamente UM lugar — `AutenticacaoService`, no
login. Duas consequências, ambas medidas:

- **Conta anonimizada continuava escrevendo.** Depois de `DELETE /usuarios/me`, o token emitido antes
  seguia valendo: `POST /api/v1/missoes` respondia **201**, com `criadorId` apontando para o usuário
  já apagado. O comentário em `ExclusaoContaService` tratava essa janela como hipótese de falha da
  revogação — ela existia SEMPRE, porque revogar refresh token não invalida access token já emitido.
- **`papel` era confiado do mesmo jeito.** Rebaixar um ADMIN no banco não tinha efeito por até 15
  minutos, e nesse intervalo ele continuava resolvendo disputa e lendo o saldo de todos os usuários.

Havia ainda um sintoma revelador na própria suíte: cinco testes mintavam JWT válido para um
`UUID.randomUUID()` sem linha em `usuario`, e passavam — porque a autenticação nunca perguntava ao
banco se a conta existia.

---

## Decisão

O `JwtAuthFilter` consulta o estado da conta **a cada requisição**, por uma porta nova
(`identidade/api/ConsultaSessao`), e monta o principal a partir do **BANCO** — não dos claims. É isso
que faz `papel` e `email` serem reconferidos: montá-lo do token devolveria a autoridade do momento da
emissão, e metade do defeito continuaria de pé.

Cache Caffeine com `expireAfterWrite(60s)` e `maximumSize(20_000)`, guardando `Optional`.

**`expireAfterWrite`, nunca `expireAfterAccess`:** o que importa é a IDADE do dado, não o uso. Com
expiração por acesso, um token quente nunca releria e a janela voltaria a ser ilimitada — exatamente
o defeito a fechar.

**O cache guarda a AUSÊNCIA também.** Sem isso, um token bem assinado cujo `sub` não existe força uma
consulta por requisição, e o controle antifraude vira o próprio vetor de carga.

Leitura por **projeção escalar** (`EstadoDaConta`), nunca `findById`: materializar `Usuario` no filtro
o poria no persistence context da requisição, e o `buscarParaAtualizar` de qualquer operação de valor
devolveria a instância em cache **sem reemitir o `FOR UPDATE`** — o lock sumiria em toda requisição
autenticada, sem teste nenhum acusar.

Invalidação sempre `afterCommit` (mesma armadilha de `CacheMissoesProximas`).

**Emenda ao CLAUDE.md.** A regra "não enriqueça `GET /auth/me`, trocaria a checagem barata por uma
consulta com joins" continua valendo e não é contrariada: o que ela proíbe é o JOIN do perfil (tribo,
conquistas, nível). O que entra aqui é uma leitura de cinco colunas por PK, sem join, servida do
cache na esmagadora maioria das vezes. `/auth/me` segue se resolvendo dos claims.

---

## Consequências

**Positivas:**
- Janela de escrita de conta anonimizada: de **15 min para ≤ 60 s**, e **zero** pelos caminhos que
  invalidam. ADMIN rebaixado perde autoridade no mesmo prazo.
- O TTL de 60 s é teto para qualquer caminho que ESQUEÇA de invalidar — inclusive `UPDATE` manual no
  banco.
- Cobre toda rota autenticada, presente e futura: é um filtro, não uma checagem por endpoint que
  alguém esquece de replicar.

**Negativas / trade-offs:**
- Uma leitura por PK por usuário por MINUTO (não por requisição). Com 100 usuários ativos, ~100
  consultas/min — duas ordens de grandeza abaixo do tráfego que as gera.
- Cinco testes precisaram passar a criar usuário de verdade. Não é custo acidental: eles se apoiavam
  no próprio defeito.
- Conta suspensa/rebaixada tem até 60 s de graça. Aceitável; a alternativa (sem cache) multiplicaria
  a carga por requisição.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| Blocklist de `jti` | Exige estado persistente ou distribuído — Redis está fora do MVP por decisão registrada — e morre no restart. Resolve "revogar UM token" (logout de um aparelho) quando o dano real dos dois casos medidos é por CONTA. O `jti` continua sendo emitido para manter a opção aberta, com o comentário corrigido: ele não é uma defesa hoje. |
| Encurtar o TTL do access token | Multiplica o tráfego de `/refresh` — que era o único endpoint público sem rate limit — e ainda deixa a janela aberta, só que menor. |
| Checar no `@PreAuthorize` de cada endpoint | Não cobre rota nova por esquecimento, que é a forma como esse tipo de defesa falha. |
| `expireAfterAccess` em vez de `expireAfterWrite` | Um token em uso constante nunca releria o banco: a janela voltaria a ser ilimitada exatamente para o usuário mais ativo. |
| Materializar `Usuario` em vez de projeção | Poria a entidade no persistence context e faria o `FOR UPDATE` das operações de valor sumir em silêncio. |
