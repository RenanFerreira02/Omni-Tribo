#!/usr/bin/env bash
set -euo pipefail
[[ "${CLAUDE_TOOL_INPUT:-}" == *"git commit"* ]] || exit 0
if git diff --cached | grep -nEi \
   '(BEGIN [A-Z ]*PRIVATE KEY|AIza[0-9A-Za-z_-]{20,}|(senha|password|secret|token|api[_-]?key)[[:space:]]*[:=][[:space:]]*["'"'"'][^"'"'"']{8,})'; then
  echo "BLOQUEADO: possível segredo no diff staged. Revise antes de commitar." >&2
  exit 2
fi