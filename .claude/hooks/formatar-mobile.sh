#!/usr/bin/env bash
# PostToolUse (Write|Edit): roda o Prettier no arquivo do mobile que acabou de ser escrito.
#
# Contraparte do formatar-java.sh. A assimetria era real: no backend o Spotless roda no fim do turno,
# mas no mobile o Prettier entra pelo ESLint (eslint-plugin-prettier), então formatação errada só
# aparecia como lint VERMELHO depois — em `npm run lint` ou no CI de mobile.yml, longe da edição que
# a causou. Aqui é por ARQUIVO e não por turno: o Prettier num arquivo só custa milissegundos, e
# rodar `prettier --write .` no repositório inteiro reformataria arquivo que a tarefa não tocou.
set -uo pipefail

arquivo=$(jq -r '.tool_response.filePath // .tool_input.file_path // empty' 2>/dev/null)
[[ -n "$arquivo" ]] || exit 0

raiz=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0

# Caminho pode chegar absoluto ou relativo à raiz; normaliza para relativo antes de filtrar.
relativo=${arquivo#"$raiz"/}
case "$relativo" in
  apps/mobile/*) ;;
  *) exit 0 ;; # fora do mobile não é assunto deste hook
esac
case "$relativo" in
  */node_modules/*) exit 0 ;;
esac
case "$relativo" in
  *.ts | *.tsx | *.js | *.jsx | *.json) ;;
  *) exit 0 ;; # .prettierrc não cobre o resto (md, sql, sh) — não invente formatação
esac

# Binário local, nunca `npx prettier`: npx buscaria na rede se faltasse, e um hook não pode depender
# de rede. Sem node_modules instalado, o hook simplesmente não age.
prettier="$raiz/apps/mobile/node_modules/.bin/prettier"
[[ -x "$prettier" ]] || exit 0

"$prettier" --write --ignore-unknown "$raiz/$relativo" >/dev/null 2>&1 || exit 0
exit 0
