# Varredura de comentários falsos e órfãos — continuação da de 2026-08-20

**Data:** 2026-09-09 · **Branch:** `fix/varredura-comentarios-falsos-e-orfaos`
**Diferença para a varredura anterior:** esta CORRIGE, não só relata. A de 2026-08-20
(`varredura-orfaos.md`) rodou em modo auditoria e não alterou arquivo do projeto.

Motivação: revisão geral de código pedida sobre o repositório inteiro. O código está em bom estado
— zero `TODO`/`FIXME`, zero `@Disabled`, zero `REQUIRES_NEW` no caminho de valor, zero
`console.log`/`printStackTrace` em produção. O que sobrou é de uma classe só, e é a mesma que este
projeto já perseguiu duas vezes: **afirmação que era verdadeira quando escrita e deixou de ser.**

---

## Linha de base medida antes de mexer

| | Antes | Depois |
|---|---|---|
| `./mvnw verify` | BUILD SUCCESS · 706 testes, 0 falhas, 2 skipped | idem |
| INSTRUCTION / BRANCH | 91,9% / 75,3% | inalterado |
| Mobile | typecheck limpo · lint 0 erros · 221 testes | inalterado |

**Armadilha de ambiente, para quem repetir a medição.** O primeiro `verify` desta sessão devolveu
**352 erros** que não eram do código: `systemctl --user is-active podman.socket` estava `inactive`.
Quando o socket cai, o `POSTGRES.start()` do bloco `static` de `ContainerConfig` estoura e o
`ExceptionInInitializerError` chega MASCARADO como `NoClassDefFoundError: Could not initialize class
com.omnitribo.TesteIntegracaoBase` — a saída parece defeito de classpath e não menciona contêiner em
lugar nenhum. `systemctl --user start podman.socket` antes de tudo.

---

## Como foi medido

1. Referência textual por símbolo (`grep -rlw`) em `main/` e `test/` separadamente, **incluindo o
   arquivo de definição** — excluí-lo produz falso positivo em massa, e produziu (ver §5).
2. Conferência de cada afirmação de garantia contra o código adjacente — **desta vez incluindo
   strings de anotação**, não só comentários. Foi essa extensão que achou §1.
3. Rastreamento de cada `Pendência #N` até a lista atual do `CLAUDE.md`.
4. `./mvnw verify` e `npm test` antes e depois.
5. Diagramas Mermaid validados por **renderização** (`@mermaid-js/mermaid-cli`), como manda o
   `CLAUDE.md` — não por leitura.

---

## 1. COMENTÁRIO FALSO — a garantia da outbox sobreviveu num quarto lugar · **risco alto**

`services/api/src/main/java/com/omnitribo/notificacoes/api/AlertaController.java:47`, na descrição
OpenAPI de `GET /api/v1/alertas`:

> *"A entrega da outbox é at-least-once, então o cliente deve tolerar duplicata"*

É a frase que `varredura-orfaos.md` §1.1 declarou falsa e corrigiu em três arquivos
(`PublicadorEventos`, `DespachanteAlertaService`, `application.yml`). **Escapou porque aquela
varredura conferiu comentários, e esta ocorrência mora numa string de anotação.**

Por que é a pior das quatro: as outras três eram comentários internos. Esta é **contrato publicado**
— sai em `/v3/api-docs` e no Swagger UI, e é o que um integrador lê. E arma o cliente contra o risco
errado: manda tolerar DUPLICATA quando o risco real é **perda silenciosa**. O
`DespachanteAlertaService`, já corrigido, diz literalmente *"o que NÃO se deve repetir daqui é a
palavra at-least-once"* — e o controller a repetia.

**Corrigido.** A descrição agora diz que a entrega para na quinta tentativa, que a caixa pode não
conter um fato que ocorreu, e mantém a orientação de deduplicar por `(tipo, missaoId)` — porque
duplicata continua possível. `PublicadorEventos` ganhou nota apontando que a próxima ocorrência pode
estar numa anotação.

## 2. COMENTÁRIO FALSO — `MissaoService.abrirMissaoDeRetirada` descrevia o mundo pré-ADR 0024

`missoes/dominio/MissaoService.java:236-240` afirmava três coisas, todas falsas hoje:

1. *"Não exige pote"* — exige; o pote é financiado pela transportadora antes de a missão existir.
2. *"`validarPoteSuficienteParaPublicar` devolve cedo para ENTREGA"* — aquele método lê `fonte_pote`,
   **não categoria** (o javadoc dele diz isso), e **não roda neste caminho**, porque a transição
   chama a máquina de estados direto sem passar por `aplicar`.
3. *"O token de ENTREGA é cunhado até a carteira de patrocinador existir"* — a carteira existe desde
   a V23 / ADR 0024.

O sintoma que denuncia: **um comentário trinta linhas abaixo, no mesmo método, afirmava
corretamente o contrário** do item 2. O arquivo se contradizia.

**Corrigido**, com a versão antiga preservada entre parênteses para quem for conferir o histórico.

## 3. COMENTÁRIO FALSO com consequência de calibração — a razão do teto de risco está invertida
· **o achado mais substantivo**

Três arquivos justificavam o teto `[1,00; 1,50]` do multiplicador de risco assim:

> *"Missões de ENTREGA hoje CUNHAM token — não pagam de pote — porque o financiador correto delas é
> o patrocinador, que ainda não existe. Sem teto, o multiplicador multiplicaria essa cunhagem."*

— `logistica/dominio/PrevisorDeRisco.java:136`, `application.yml:402`,
`CalculadoraDeRecompensaTest.java:102`, mais `docs/adr/0022` §5 e `docs/diagramas/fluxo-economico.md`.

**Medido, não lido:**

| Fato | Onde |
|---|---|
| ENTREGA criada por humano nasce `FontePote.CUNHAGEM` | `Missao.java:270` |
| A conversão de entrega falida sobrescreve para `PATROCINADOR` | `MissaoService.abrirMissaoDeRetirada` → `missao.financiadaPeloPatrocinador()` |
| O multiplicador só é produzido no webhook | único chamador: `WebhookTransportadoraController.avaliarRisco:148` |
| Missão criada por usuário recebe 1,00 | construtor curto de `Insumos` |

Cruzando: **o multiplicador só se aplica a missões `PATROCINADOR`, e nenhuma missão que cunha o
recebe.** A justificativa está exatamente invertida. Hoje o teto limita **quanto a transportadora
paga por conversão**, não a emissão de moeda — e o excedente não vira token do nada: multiplicador
que estoure o saldo do patrocinador faz `debitarPatrocinador` devolver vazio e a entrega vira
`SEM_PATROCINIO`.

Que o repositório se contradizia, de novo: `Missao.java:267-268` já dizia corretamente que o
patrocinador **existe** ("o caso onde o financiador correto (o patrocinador) existe mas não está
ligado àquela missão"), enquanto `PrevisorDeRisco` e o `application.yml` diziam que não.

Por que importa além da estética: é a resposta a *"por que 1,5?"* numa banca, e ela estava errada.

**Corrigido nos três arquivos de código, no ADR 0022 (retificação no padrão do ADR 0015) e no
diagrama.** **O valor 1,50 NÃO foi alterado** — mexer nele é recalibração de fórmula e exigiria
subir `versao` (`CalculadoraDeRecompensaTest.douradoV1` falha de propósito para forçar essa decisão).
**Revisar o teto sob a razão nova fica em aberto, e é decisão de projeto, não consequência desta
correção.**

## 4. LACUNA — `Pendência #N` é um ponteiro que quebra em silêncio

Treze lugares citavam pendências por NÚMERO. A lista do `CLAUDE.md` encolhe a cada item resolvido —
o próprio ADR 0015 registra que "a numeração encolheu três vezes" —, então os números apontam hoje
para outra coisa:

| Onde | Dizia | Era, de fato |
|---|---|---|
| `PublicadorEventos.java:44` | #4 | a outbox, hoje #1 (só existem três) |
| `EntregaFalidaService.java:65`, `application.yml:214` | #4 | idem |
| `MigracaoTest.java:273` | #1 | o `REVOKE` inerte, resolvido em 2026-08-11 |
| `SaqueDesabilitadoTest`, `ContratoErroTest`, `UsuarioDeTeste`, `LgpdControllerTest` ×2 | #3 | a conta anonimizada, resolvida em 2026-08-11 |
| `BuscaUsuarioService`, `BuscaHandleTest` | #3 | a busca por handle, já fechada |
| `MissaoService.java:1028` | #1 | a cunhagem por categoria, fechada pela V23 |
| `LancamentoRepository.java:63` | #2 | **correto hoje** — e quebraria no próximo item resolvido |

**Corrigido trocando o número por referência NOMINAL** ("a pendência *A outbox abandona evento em
silêncio…*"), que não envelhece.

**Quatro ocorrências foram deixadas de propósito:**
- `V23__carteira_patrocinador.sql` (×2) e `V27__handle_case_insensitive.sql` — **migrations são
  checksummed pelo Flyway.** Editar um comentário mudaria o checksum e quebraria todo banco de dev
  ou prod já migrado, com erro que não menciona comentário nenhum. Não se toca.
- `WebhookEntregaFalidaTest.java:309` — é citação histórica (*"Era `isZero()`, com a nota …"*), e
  narra corretamente o passado.

## 5. ÓRFÃO com armadilha — `PatrocinadorRepository.findByTransportadoraSlug`

Declarado e **nunca chamado**, nem em `main` nem em `test`. Não é só código morto: devolve a
entidade **sem filtrar `ativo`** — exatamente a distinção que o javadoc de
`buscarUsuarioIdAtivoPorSlug`, logo abaixo, diz não poder ficar a cargo do chamador (*"convidar um
`if` esquecido a converter missão financiada por um contrato que acabou"*). Quem precisasse do slug
alcançaria o finder de nome óbvio, não a query de nome comprido.

**Removido**, com nota na interface explicando por que não deve voltar.

## 6. RETRATADO — os "exports órfãos" do mobile não existem

Onze símbolos de `apps/mobile` apareceram como órfãos na primeira passada. **Falso positivo meu**: o
grep excluía o arquivo de definição, e todos são usados dentro dele.

Investiguei se ao menos o `export` era supérfluo, e **não é**: `chavesCarteira` e `chavesPerfil` são
importados cross-file (`beneficios/hooks.ts`, `missoes/hooks.ts`) para invalidação de cache entre
features. Os `chavesX` seguem uma convenção **deliberada e uniforme**; retirar o `export` dos quatro
que hoje não têm consumidor externo tornaria a superfície inconsistente e quebraria a próxima
invalidação que precisasse deles. `src/schemas/index.ts` é barrel de schemas pela mesma lógica.

**Nenhuma alteração no mobile.** Registrado aqui porque "não mexi" é informação, e porque a próxima
varredura vai reencontrar a mesma lista.

---

## O que esta varredura NÃO fez

- **Não tocou nas Pendências #1 (carta-morta da outbox) e #3 (alerta de ponto lotado sem teto).** As
  duas estão marcadas "não decida sozinho" no `CLAUDE.md` e mudam contrato — de entrega de
  notificação e de alerta operacional. Continuam abertas.
- **Não refatorou `MissaoService`** (1137 linhas, ~5 responsabilidades; o segundo maior arquivo do
  projeto tem 564). É o único ponto do backend onde a estrutura pede mudança, mas é caminho de
  valor, implementa duas portas, e separar serviço ali é onde nasce ciclo de bean — `logistica` tem
  duas classes exatamente por isso. Fica registrado como candidato, com ADR próprio quando for feito.
- **Não alterou nenhum número de calibração**, em particular o teto de 1,50 do §3.
- Não rodou o profile `mutacao` nem o `seguranca` (exige chave da NVD).

## Ordem de correção sugerida para o que ficou

1. Decidir o instrumento da carta-morta da outbox (Pendência #1) — é a única com perda de fato real.
2. Decidir o teto do multiplicador sob a razão nova do §3, ou registrar explicitamente que 1,50
   permanece por escolha e não por inércia.
3. Deduplicação do alerta de ponto lotado (Pendência #3).
4. `MissaoService`, com ADR.
