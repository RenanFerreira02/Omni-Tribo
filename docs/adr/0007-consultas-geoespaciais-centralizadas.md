# 0007 — Consultas Geoespaciais numa Classe Única, em `compartilhado`

**Data:** 2026-08-07  
**Status:** Aceito — substitui parcialmente o [ADR 0002](0002-postgresql-postgis.md)

---

## Contexto

O ADR 0002 estabeleceu que toda chamada PostGIS ficaria "isolada em uma única classe de repositório
**por módulo**", como anti-corruption layer para uma eventual troca por Oracle Spatial (a FIAP mantém
parceria com a Oracle). Sob essa regra nasceram dois stubs vazios: `geolocalizacao/infra/CheckinGeoRepository`
e `logistica/infra/PontoCustodiaGeoRepository`.

Ao implementar a F6 a regra se mostrou inviável na forma escrita, por uma razão que só aparece quando
existe código de verdade: **as duas consultas geoespaciais da fase pertencem a módulos diferentes e à
mesma álgebra.**

- A busca por proximidade lê `missao.origem` — tabela do módulo `missoes`.
- O check-in mede a distância entre a coordenada reportada e a origem da missão, e grava em
  `checkin` — tabela do módulo `geolocalizacao`.

E a regra do ArchUnit (`RegrasArquiteturaTest`) é **direcional**: nenhuma classe fora de
`com.omnitribo.<modulo>..` acessa `<modulo>.dominio..` ou `<modulo>.infra..`. `compartilhado` é
isento como *alvo*, mas continua sujeito como *origem*. Com "um repo por módulo", `ST_Distance` e
`ST_DWithin` acabariam em dois arquivos — e a promessa central do ADR 0002, trocar de motor mexendo
em um arquivo só, deixaria de valer no exato momento em que passou a existir mais de uma consulta.

---

## Decisão

Toda chamada a função PostGIS vive em **`com.omnitribo.compartilhado.infra.ConsultasGeoespaciais`**,
uma classe só. Os dois stubs por módulo foram apagados.

Três consequências que fazem parte da decisão:

1. **A classe não pode conhecer tipo de módulo nenhum.** Como `compartilhado.infra` continua sendo
   *origem* sob a regra do ArchUnit, `ConsultasGeoespaciais` não importa `Missao`, `StatusMissao` nem
   `CategoriaMissao`. Filtros entram como `String` (sempre `.name()` de um enum já validado pelo
   binder, nunca texto livre do cliente) e o retorno é `AlvoProximo(UUID id, double distanciaM)`, um
   par neutro que o chamador reidrata. Custa uma segunda consulta em `buscarProximas` — a geo devolve
   ids e distâncias, um `findAllById` traz as entidades — e paga com `MissaoResponse` continuando a
   ser a única representação de leitura de missão na API.

2. **Usa `JdbcClient`, não repositório do Spring Data.** `@Query(nativeQuery = true)` exige interface
   ligada a uma `@Entity`, e a única entidade visível de `compartilhado.infra` seria `Outbox`. Atar a
   busca geoespacial a `Outbox` cumpriria a letra da convenção do `CLAUDE.md` e pioraria o desenho.
   `JdbcClient` participa da transação corrente via `DataSourceUtils`, e a parte da convenção que de
   fato protege alguma coisa — **parâmetros nomeados, zero concatenação** — segue integral.

3. **`ConsultasGeoespaciais.distanciaMetros` recebe quatro escalares e não tem cláusula `FROM`.**
   Assim o módulo `geolocalizacao` calcula distância até a origem de uma missão sem nunca ler a
   tabela `missao` — que a regra do ArchUnit lhe proíbe de conhecer. Por isso `ComandoCheckin`
   carrega `origemLat`/`origemLon`/`raioCheckinM` como valores, e não um `missaoId` para buscar.

A promessa do ADR 0002 fica mais forte, não mais fraca: `ST_DWithin` → `SDO_WITHIN_DISTANCE`,
`ST_Distance` → `SDO_GEOM.SDO_DISTANCE`, num arquivo só, de verdade.

---

## Alternativas descartadas

**Um repositório geo por módulo, como o ADR 0002 escreveu.** É o que estava valendo. Produz duas
classes com `ST_*` já na F6 e mais uma por módulo geoespacial futuro (`logistica` tem
`ponto_custodia.ponto` esperando). A migração para Oracle Spatial passaria a ser "mexer em N
arquivos", que é exatamente a situação que o ADR 0002 existia para evitar. A regra foi escrita antes
de existir a primeira consulta e não sobreviveu ao contato com a segunda.

**Repositório Spring Data amarrado a `Outbox`, só para manter `@Query(nativeQuery = true)`.**
Preservaria a convenção do `CLAUDE.md` ao preço de declarar que a busca por missões próximas é uma
operação sobre a tabela de outbox. Cumpre a letra e mente sobre o desenho — quem abrisse o arquivo
seis meses depois teria que descobrir sozinho que a entidade ali é decorativa.

**Duplicar as chamadas PostGIS nos dois módulos, sem classe compartilhada.** Elimina a dependência
entre módulos e é a leitura mais literal de "monólito modular". Mas `ST_Distance` entre dois pontos é
a mesma matemática nos dois lados; duplicá-la garante que as duas cópias divirjam — e uma divergência
aqui significa a busca dizer que a missão está a 40 m enquanto o check-in a rejeita por estar a 60 m.

**Mover a consulta de proximidade para `missoes.infra` e a de distância para `geolocalizacao.infra`,
sem `compartilhado`.** É a alternativa mais próxima do ADR 0002 e a que quase foi adotada. Falha pelo
mesmo motivo da primeira: são dois arquivos com PostGIS.

---

## Consequências

**Positivas**
- Um arquivo com `ST_*` no repositório inteiro, verificável com um `grep`.
- `ConsultasGeoespaciais` não consegue depender de módulo de negócio nem por acidente — o ArchUnit
  falha o build se alguém tentar.
- A prova de uso do índice GiST (`docs/evidencias/f6-explain-analyze.md`) roda sobre a constante de
  SQL desta classe, não sobre uma cópia: se a consulta mudar e parar de usar o índice, o teste quebra.

**Negativas assumidas**
- `compartilhado` cresce, e `compartilhado` é o único pacote **isento** da regra do ArchUnit (ver o
  array `MODULOS` em `RegrasArquiteturaTest`). Violação de camada aqui dentro não é pega por teste
  nenhum — é disciplina. Vale para esta classe como já valia para `Coordenadas`.
- Duas consultas em vez de uma na busca por proximidade (ids+distância, depois entidades). Ordem de
  grandeza irrelevante no volume do MVP, e o `LIMIT` da primeira limita a segunda.
- O `CLAUDE.md` precisou de emenda: "query nativa PostGIS vive em `infra/`" continua verdadeiro
  (`compartilhado/infra`), mas o `@Query(nativeQuery=true)` deixou de ser obrigatório quando a
  consulta cruza módulos.

---

## Notas de manutenção

**`TipoMissaoEvento.CHECK_IN_REJEITADO` segue inalcançável, e é deliberado.** A constante existe no
enum e no CHECK da V11, mas nenhum `EventoMissao` mapeia para ela. Uma rejeição de check-in não é
transição de status — a missão continua `EM_ANDAMENTO` —, então `MissaoStateMachine.transicionar`
não tem como produzi-la. Gravar a rejeição em `missao_evento` exigiria um segundo caminho de escrita
em `REQUIRES_NEW` dentro de `missoes`, e duplicaria em forma pobre o que a tabela `checkin` já guarda
melhor: coordenada, acurácia, distância medida e motivo. Duas fontes de verdade para o mesmo evento é
pior que uma constante de enum sem uso. Se a F7 precisar da rejeição na trilha, o caminho é derivá-la
de `checkin`, não escrever em dois lugares.
