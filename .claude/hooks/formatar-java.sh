#!/usr/bin/env bash
# Stop: aplica o Google Java Format nos .java pendentes, se houver algum.
#
# Spotless é verificado no `verify` com failOnError — falha de formatação quebra o build igual a um
# teste vermelho. Formatar no fim do turno evita descobrir isso só na hora do PR. O guard do
# git status é o que mantém o hook barato: turno que não tocou Java sai em milissegundos.
set -uo pipefail

cat >/dev/null 2>&1 # drena o payload do stdin; este hook não precisa dele

raiz=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
cd "$raiz" || exit 0

pendentes=$(git status --porcelain -- 'services/api/*.java' | sed 's/^...//' | sed 's/.* -> //')
[[ -n "$pendentes" ]] || exit 0

# O `java` do PATH no Fedora é JRE-only (sem javac) e o Maven morre com "release version 21 not
# supported" — mensagem que sugere JDK velho e na verdade significa "não há compilador nenhum".
# O hook não herda o profile do shell, então resolve o JDK por conta própria.
if [[ ! -x "${JAVA_HOME:-/inexistente}/bin/javac" ]]; then
  for candidato in "$HOME/.sdkman/candidates/java/current" /usr/lib/jvm/java-21-openjdk; do
    if [[ -x "$candidato/bin/javac" ]]; then
      export JAVA_HOME="$candidato"
      break
    fi
  done
fi
[[ -x "${JAVA_HOME:-/inexistente}/bin/javac" ]] || exit 0

antes=$(printf '%s\n' "$pendentes" | xargs -r md5sum 2>/dev/null | md5sum)
(cd services/api && ./mvnw -q spotless:apply) >/dev/null 2>&1 || exit 0
depois=$(printf '%s\n' "$pendentes" | xargs -r md5sum 2>/dev/null | md5sum)

if [[ "$antes" != "$depois" ]]; then
  jq -n '{systemMessage: "Spotless reformatou arquivos .java pendentes (Google Java Format)."}'
fi
exit 0
