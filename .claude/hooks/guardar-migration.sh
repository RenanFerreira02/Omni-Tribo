#!/usr/bin/env bash
# PreToolUse(Write): recusa a criação de migration Flyway com versão inválida.
#
# Versão de migration aqui é sequência GLOBAL, e o número errado não falha na máquina de quem
# escreveu: falha no merge ("more than one migration with version N") ou no banco de dev alheio
# ("detected applied migration not resolved locally"). Nenhum teste pega isso. Daí o hook.
set -uo pipefail

recusar() {
  jq -n --arg r "$1" '{
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      permissionDecision: "deny",
      permissionDecisionReason: $r
    }
  }'
  exit 0
}

payload=$(cat)
arquivo=$(printf '%s' "$payload" | jq -r '.tool_input.file_path // empty')
[[ -n "$arquivo" ]] || exit 0

case "$arquivo" in
  */db/migration/*) faixa=schema ;;
  */db/seed/*) faixa=seed ;;
  *) exit 0 ;;
esac

base=$(basename "$arquivo")
[[ "$base" == *.sql ]] || exit 0
# Arquivo que já existe é edição, não escolha de versão — outro problema (checksum), fora do escopo.
[[ -e "$arquivo" ]] && exit 0

if [[ ! "$base" =~ ^V([0-9]+)__[a-z0-9_]+\.sql$ ]]; then
  recusar "Nome fora do padrão: '$base'. Use V<N>__<snake_case>.sql (minúsculas, sem acento)."
fi
n=$((10#${BASH_REMATCH[1]}))

if [[ "$faixa" == seed && "$n" -lt 900 ]]; then
  recusar "V$n em db/seed/ invade a faixa de schema. Seed vive em 900+ (hoje V900, V901) — é a faixa alta que garante, por construção, que o seed roda depois de todo schema. Use V902 ou maior."
fi

if [[ "$faixa" == schema && "$n" -ge 900 ]]; then
  recusar "V$n em db/migration/ invade a faixa reservada ao seed (900+). Migration de schema usa a faixa baixa."
fi

if [[ "$faixa" == schema && ( "$n" -eq 9 || "$n" -eq 10 ) ]]; then
  recusar "V9 e V10 estão QUEIMADAS e nunca podem ser reutilizadas. Foram os arquivos de seed antes da renomeação para V900__seed_dev.sql, então bancos de dev antigos têm as versões 9 e 10 gravadas no flyway_schema_history com descrição de seed. Um V$n novo passa em clone novo e quebra em máquina antiga com erro de checksum. Escolha o próximo número livre da faixa baixa."
fi

raiz=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
db="services/api/src/main/resources/db"

# Working tree + TODAS as branches (locais e remotas). Olhar só o diretório local é exatamente o
# erro que fez a F5 pular de V11 para V13: a V12 já existia numa branch não mergeada.
colisoes=$(
  {
    ls "$raiz/$db/migration" "$raiz/$db/seed" 2>/dev/null | sed 's#^#(working tree) #'
    for ref in $(git -C "$raiz" for-each-ref --format='%(refname:short)' refs/heads refs/remotes 2>/dev/null); do
      git -C "$raiz" ls-tree -r --name-only "$ref" -- "$db" 2>/dev/null |
        sed "s#^#($ref) #"
    done
  } | grep -E "/?V0*${n}__" | sort -u
)

if [[ -n "$colisoes" ]]; then
  recusar "V$n JÁ ESTÁ EM USO. Ocorrências:
$colisoes

Escolha um número livre. Se a colisão está só numa branch aberta, reserve uma faixa disjunta em vez de disputar o número — duas migrations com a mesma versão derrubam o merge."
fi

exit 0
