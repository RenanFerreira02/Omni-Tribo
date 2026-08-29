# OWASP Dependency-Check — tentativa de execução sem chave da NVD

**Data:** 2026-08-24
**Plugin:** `org.owasp:dependency-check-maven:13.0.0` (profile `seguranca`)
**Resultado:** **NÃO EXECUTOU.** Nenhum relatório foi produzido.

---

## 0. O que este documento é

É o registro de uma tentativa que **falhou**, e da hipótese que ela derrubou. Não há lista de CVE
aqui porque nenhuma varredura completou — e a alternativa, que seria deixar o arquivo de evidência
para depois, esconderia o único dado novo que esta execução produziu.

O critério de aceite pedia "uma linha por achado dizendo se afeta o projeto e por quê". Não há
achados: **a base de dados nunca chegou a ser montada.** A seção 4 traz o comando que produzirá
essa lista quando a chave existir.

---

## 1. A hipótese testada

A `matriz-rastreabilidade.md` afirmava, desde 2026-08-16:

> O OWASP Dependency-Check 13.0.0 **exige uma chave da API da NVD** para montar a base local.

E logo acima citava o erro:

```
NvdApiException: Invalid API Key, length of 0 too short to provided a masked partial key
```

**A hipótese era que a frase estava imprecisa.** Aquele erro é o da string VAZIA — o modo de falha
do `${env.*}` com variável ausente, documentado no `pom.xml`. "Chave vazia" e "chave nenhuma" são
entradas diferentes, e a segunda poderia cair em acesso anônimo à NVD, que é limitado por taxa mas
funcional. Se fosse o caso, haveria relatório hoje e a frase precisaria de correção.

## 2. O que aconteceu

```bash
$ echo "NVD_API_KEY presente no ambiente?"
False        # a variável está AUSENTE, não vazia

$ cd services/api && ./mvnw -Pseguranca verify -DskipTests -Djacoco.skip=true -Dspotbugs.skip=true
#                                              ↑ sem -Dnvd.api.key, de propósito
```

Também conferido antes de rodar: nada injeta `nvd.api.key` por `~/.m2/settings.xml`, por `.mvn/` ou
pelo próprio `pom.xml` — as duas ocorrências no pom são comentário.

Saída, aos 8,6 segundos:

```
[INFO] Checking for updates
[ERROR] Error updating the NVD Data
[INFO] Updating CISA Known Exploited Vulnerability list: https://www.cisa.gov/…/known_exploited_vulnerabilities.json
[INFO] Check for updates complete (1723 ms)
[WARNING] Unable to update 1 or more Cached Web DataSource, using local data instead.
          Results may not include recent vulnerabilities.
[ERROR] Unable to continue dependency-check analysis.
[ERROR] Failed to execute goal org.owasp:dependency-check-maven:13.0.0:check (dependency-check)
        on project api: Fatal exception(s) analyzing omnitribo-api:
[ERROR] 	UpdateException: Error updating the NVD Data
[ERROR] 		caused by NvdApiException: Invalid API Key, length of 0 too short to provided a masked partial key
[ERROR] 	NoDataException: No documents exist
[INFO] BUILD FAILURE
[INFO] Total time:  8.610 s
```

Nenhum arquivo em `services/api/target/dependency-check-report.*`. Nenhum diretório
`dependency-check-data`.

## 3. O que isto estabeleceu

**A hipótese estava errada e a frase da matriz estava certa.** Não há acesso anônimo: o plugin 13.0.0
exige a chave, ponto.

**E há um fato novo, que vale mais que a confirmação:** com a variável **ausente**, o erro é
**exatamente o mesmo** de quando ela está **vazia** — `Invalid API Key, length of 0`. O plugin
normaliza "sem chave" para string vazia antes de validar.

Isso importa porque **a mensagem de erro engana em uma das duas direções**. Quem a lê procura uma
chave *errada* — e a causa pode ser simplesmente não haver chave nenhuma. Foi por isso que a
armadilha do `${env.*}` custou duas depurações neste projeto: o sintoma de "configurei errado" e o de
"não configurei" são indistinguíveis pelo log.

Consequência prática: **`NoDataException: No documents exist`** é a segunda linha do erro, e é ela que
diz o que de fato aconteceu — a base local ficou vazia. Quem for depurar isto no futuro deve olhar a
segunda linha, não a primeira.

## 4. Como completar, quando a chave existir

A chave é gratuita: <https://nvd.nist.gov/developers/request-an-api-key>.

```bash
export NVD_API_KEY=<a chave>
cd services/api
./mvnw -Pseguranca verify -DskipTests -Djacoco.skip=true -Dspotbugs.skip=true \
       -Dnvd.api.key=$NVD_API_KEY
```

**Nunca configure a chave no `pom.xml` com `${env.NVD_API_KEY}`** — é o que produz a string vazia e
o erro acima. O `pom.xml` já traz esse aviso no comentário do profile.

A primeira execução baixa a base inteira da NVD e demora; as seguintes usam
`services/api/target/dependency-check-data`, que o CI cacheia.

Quando rodar, este arquivo ganha:

| CVE | Dependência | Severidade | Alcançável a partir do nosso código? | Veredito |
|---|---|---|---|---|
| *(uma linha por achado — lista sem triagem é ruído)* | | | | |

E, se o build reprovar por `failBuildOnCVSS=7`, a lista do que reprovou entra aqui **antes** de
qualquer supressão ser considerada.

## 5. O que isto NÃO prova

- **Não prova que o projeto está livre de dependência vulnerável.** Não prova nada sobre
  vulnerabilidades: a varredura não aconteceu. O `Security Scan` do CI continua verde por não ter
  varrido, que é precisamente o que o `::warning` daquele workflow existe para tornar visível.
- **Não prova que a fiação funciona ponta a ponta.** Prova que ela funciona até a aquisição de dados:
  o plugin executa, resolve o profile, lê o arquivo de supressões e chega ao passo de atualização.
  Falha só ali.
- **Não diz nada sobre o secret no GitHub.** `gh` não está instalado nesta máquina. O que se sabe
  sobre o `NVD_API_KEY` no repositório remoto é o que a `f13-ci-github-actions.md` registrou em
  2026-08-17, e **não foi reconferido nesta data**.
- **Varredura de dependência não cobre código próprio.** Ela olha o que importamos, não o que
  escrevemos — para isso existem o SpotBugs no `verify` e o gitleaks no CI.
- **A base da NVD tem atraso de publicação.** Mesmo com a varredura completa, um CVE publicado
  ontem pode não aparecer hoje.
