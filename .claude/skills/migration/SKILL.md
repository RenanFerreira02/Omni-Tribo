---
name: migration
description: Cria uma migration Flyway com o número correto da sequência global, verificando faixas queimadas, faixa de seed e branches abertas. Uso /migration adicionar tabela de alertas
allowed-tools: Bash, Read, Write, Grep, Glob
---

Criar migration para: $ARGUMENTS

Versão de migration neste repositório é **sequência global**, não por diretório, e o número errado
só falha na máquina de outra pessoa (ou no merge). Siga os passos na ordem — nenhum é opcional.

## 1. Descobrir o número, olhando MAIS que o diretório local

```bash
ls services/api/src/main/resources/db/migration/ services/api/src/main/resources/db/seed/
for b in $(git branch -a --format='%(refname:short)' | grep -v '^origin$'); do
  echo "--- $b"; git ls-tree -r --name-only "$b" -- services/api/src/main/resources/db/ 2>/dev/null
done
```

O segundo comando não é zelo: F5 pulou de V11 para V13 justamente porque V12 já existia numa branch
de geolocalização ainda não mergeada. Duas migrations com a mesma versão derrubam o merge com
*"more than one migration with version N"*, e isso não aparece em nenhum CI antes da hora.

Regras para escolher:

- **Schema** → `db/migration`, próximo número livre da faixa baixa. Único location do perfil
  default/prod.
- **Seed** (dados de dev/test) → `db/seed`, faixa **900+**. Nunca um número que o schema possa
  alcançar. A faixa alta é o que garante, por construção, que o seed roda depois de todo schema —
  então ele grava dados em forma FINAL: não conte com migration posterior para corrigir valor de seed.
- **V9 e V10 estão queimadas. Nunca as reutilize.** Foram os arquivos de seed antes da renomeação
  para `V900__seed_dev.sql`, então bancos de dev antigos têm 9 e 10 gravadas no
  `flyway_schema_history` com descrição de seed. Um `V9__*.sql` novo passa em clone novo e falha em
  máquina antiga com erro de checksum ou "detected applied migration not resolved locally".
- Se houver fase paralela em curso, **reserve faixa disjunta** e diga qual você reservou.

Confirme comigo o número escolhido antes de escrever o arquivo, citando o que encontrou nas branches.

## 2. Escrever a migration

Nome: `V<N>__<snake_case_curto>.sql`. Restrições do projeto que a migration precisa respeitar:

- `timestamptz`, nunca `timestamp`.
- Dinheiro: `numeric(12,2)`. Tokens: `bigint`. Nunca `double`, nunca `varchar` para número.
- Enum: `varchar` + `CHECK`, mapeado com `EnumType.STRING`. Nunca ordinal, nunca tipo enum nativo.
- Coordenada: `geography(POINT,4326)`. Distância é derivada por PostGIS, nunca armazenada em coluna.
- `lancamento`, `auditoria`, `checkin` e `missao_evento` são **append-only**: a migration concede
  só `SELECT, INSERT` ao papel `omnitribo_app` e revoga `UPDATE, DELETE`. Tabela append-only nova
  segue o mesmo padrão — e `MigracaoTest` trava a matriz de privilégios, então atualize o teste junto.
- `gen_random_uuid()` vem de `pgcrypto`, já habilitada em `V1__extensoes.sql`.

`ddl-auto` é sempre `validate`. Se a entidade JPA divergir do schema, a correção é a migration —
nunca afrouxar o `ddl-auto`.

## 3. Aplicar num banco de dev que já existe

```bash
make reset   # destrói o volume e recria; o seed reconstrói os dados, então o custo é zero
```

Isto **não** é opcional e a razão não é óbvia: como V900/V901 já estão aplicadas, qualquer migration
nova tem versão MENOR que o topo do histórico e o Flyway a classifica como *out-of-order* — que
`application-dev.yml` mantém desligado de propósito. O sintoma é o `spring-boot:run` morrer no boot
com `Validate failed: Detected resolved migration not applied to database: <N>`, sem mencionar seed
nem ordenação. **Não resolva com `out-of-order: true`**: isso deixaria o schema de dev divergir da
ordem que prod aplicaria.

Se você **renomeou** uma migration existente, rode também `cd services/api && ./mvnw clean`. O Maven
não remove de `target/classes` o arquivo com o nome antigo, o Flyway acha os dois e aplica os dois; o
sintoma é `duplicate key value violates unique constraint`, que não parece ter relação com renomear
arquivo. O CI não sofre disso porque clona do zero.

## 4. Verificar

```bash
cd services/api && ./mvnw -q verify
```

Só reporte pronto com a saída real colada. Se falhar, reporte a mensagem exata e espere instrução.
