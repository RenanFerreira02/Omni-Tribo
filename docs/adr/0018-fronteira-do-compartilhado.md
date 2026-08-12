# 0018 — Fronteira de `compartilhado`: `infra` é privado, `dominio` é kernel

**Data:** 2026-08-11
**Status:** Aceito

---

## Contexto

`RegrasArquiteturaTest` aplica a regra de fronteira aos 7 módulos de negócio e deixa `compartilhado`
de fora do array `MODULOS`, por ser shared por design. O CLAUDE.md registrava a consequência com
honestidade: *"Violação em `compartilhado` não é pega por teste nenhum — é só disciplina."*

A disciplina não segurou, e o resultado foi medido:

- `compartilhado.infra.ConsultasGeoespaciais` era classe concreta e **quatro domínios** dependiam
  dela: `MissaoService`, `RegistroCheckinService`, `TriboService`, `PontoCustodiaService`.
- `AutenticacaoService` (`identidade/dominio`) importava `JwtService` e `BloqueioLoginService`,
  ambos de `compartilhado/infra`.

Domínio dependendo de infraestrutura de outro módulo é exatamente a direção que o resto do projeto
trata como proibida — e a única sem teste que a acusasse.

---

## Decisão

A regra nova mira **`compartilhado.infra` e só ele**:

```java
noClasses().that().resideOutsideOfPackage("com.omnitribo.compartilhado..")
    .should().accessClassesThat().resideInAPackage("com.omnitribo.compartilhado.infra..")
```

Fica a divisão: **`dominio` é kernel** (livre), **`api` é porta** (livre), **`infra` é adaptador
privado** (fechado).

Três portas extraídas para `compartilhado/api`:

| Porta | Implementação em `infra` |
|---|---|
| `ConsultasGeoespaciais` (+ records `AlvoProximo`, `Centro`) | `ConsultasGeoespaciaisPostgis` |
| `EmissorDeToken` | `JwtService` |
| `ControleDeTentativasLogin` (+ record `BloqueioAtivo`) | `BloqueioLoginService` |

`JwtService.validar` **fica fora** da porta: devolve `io.jsonwebtoken.Claims`, e pôr um tipo da
biblioteca de JWT numa porta pública faria a escolha da lib vazar para todos os módulos — justamente
o que uma porta existe para impedir. Quem valida é o filtro de autenticação, que já vive em
`compartilhado/infra`.

**A restrição direcional continua valendo, e mover para `api/` não a afrouxa.** `compartilhado` é
isento como ALVO mas continua sendo ORIGEM: `ConsultasGeoespaciais` segue proibida de importar
`Missao`, `StatusMissao` ou `CategoriaMissao`, então status e categoria continuam como String e o
retorno continua `AlvoProximo`. Mesma razão pela qual `EmissorDeToken` recebe `papel` como String.

Emenda ao **ADR 0007**: a decisão de centralizar toda chamada PostGIS numa classe única não muda —
muda o pacote da porta, e a implementação continua em arquivo único.

---

## Consequências

**Positivas:**
- A última porta aberta da fronteira modular fecha, e passa a ser travada por teste em vez de
  disciplina.
- Os quatro domínios passam a depender de uma interface, alinhando com o que já faziam para
  `PublicadorEventos`, `DespachoAlerta` e `DadosPessoaisDoUsuario`.
- Trocar PostGIS por outro backend geoespacial continua sendo reescrever um arquivo.

**Negativas / trade-offs:**
- Três interfaces a mais para navegar. O custo real é baixo: a implementação tem nome óbvio e o
  javadoc da porta aponta para ela.
- `compartilhado/dominio` continua sem proteção nenhuma — é a decisão, não um esquecimento (ver
  abaixo).

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| Incluir `compartilhado` inteiro no array `MODULOS` | **Medido: 46 imports legítimos** de `compartilhado.dominio` vindos de outros módulos — `RecursoNaoEncontradoException` (14), `RegraNegocioVioladaException` (7), `ChaveIdempotencia` (5), `Auditavel` (5), `DominioException` (4), `Coordenadas` (4). É kernel compartilhado por desenho; o teste ficaria vermelho em quase todo arquivo do projeto e a única saída prática seria desligá-lo — trocando uma regra ausente por uma regra ignorada, que é pior. |
| Mover `ConsultasGeoespaciais` inteira (com o SQL) para `api/` | `api/` viraria adaptador, e o ADR 0007 — que existe para confinar `ST_*` — perderia sentido. |
| Um repositório geoespacial por módulo | Já descartado no ADR 0007, e nada mudou: as consultas estão em módulos diferentes e a regra direcional faria `ST_*` acabar em dois arquivos. |
| Deixar `JwtService.validar` na porta | Vazaria `io.jsonwebtoken.Claims` para todos os módulos, amarrando a escolha da biblioteca de JWT à fronteira pública. |
