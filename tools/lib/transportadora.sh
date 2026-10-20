# shellcheck shell=bash
# Biblioteca da "transportadora de mentira": o que os scripts de demonstração precisam para falar
# com o webhook (HMAC) e com a API (JWT). Usada por tools/carrier-mock/enviar.sh e tools/feira/*.
#
# Uso:  . "$(dirname "${BASH_SOURCE[0]}")/../lib/transportadora.sh"
#
# Era código DUPLICADO entre dois scripts, e a nota de lá dizia o sintoma da divergência: 401 num e
# 200 no outro. Agora existe uma assinatura só.

API="${API:-http://localhost:8080}"
URL_WEBHOOK="$API/api/v1/webhooks/transportadora"
URL_CONFIRMACAO="$URL_WEBHOOK/confirmacao"
TRANSPORTADORA="${TRANSPORTADORA:-transportadora-dev}"
# Default idêntico ao de application-dev.yml. Trocar um exige trocar o outro.
SEGREDO="${SEGREDO:-segredo-de-desenvolvimento-local}"
SENHA_SEED="${SENHA_SEED:-Senha@123}"

RAIZ_REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE="$RAIZ_REPO/tools/demo/compose.sh"
export OMNITRIBO_COMPOSE_SILENCIOSO=1

verde=$'\033[32m'; vermelho=$'\033[31m'; amarelo=$'\033[33m'; cinza=$'\033[90m'
negrito=$'\033[1m'; normal=$'\033[0m'

exigir_programas() {
  local p
  for p in curl openssl jq; do
    command -v "$p" >/dev/null || { echo "Falta $p no PATH."; exit 1; }
  done
}

exigir_api() {
  if ! curl -s -m 3 -o /dev/null "$API/api/v1/ping"; then
    echo "${vermelho}A API não respondeu em $API.${normal}"
    echo "Suba e deixe rodando:  cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,feira"
    exit 1
  fi
}

# HMAC-SHA256 sobre "timestamp.corpo" — exatamente o que o filtro do servidor recalcula (ADR 0021).
assinar() { # $1=timestamp  $2=corpo
  printf '%s.%s' "$1" "$2" | openssl dgst -sha256 -hmac "$SEGREDO" -hex | sed 's/^.*= //'
}

# Imprime o corpo da resposta, uma quebra de linha e o status HTTP na última linha.
postar_webhook() { # $1=corpo  $2=url (padrão: reporte)
  local ts
  ts=$(date +%s)
  curl -s -w '\n%{http_code}' -X POST "${2:-$URL_WEBHOOK}" \
    -H 'Content-Type: application/json' \
    -H "X-Transportadora: $TRANSPORTADORA" \
    -H "X-Timestamp: $ts" \
    -H "X-Assinatura: $(assinar "$ts" "$1")" \
    --data-binary "$1"
}

login() { # $1=e-mail → access token
  curl -s -X POST "$API/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"senha\":\"$SENHA_SEED\"}" | jq -r '.accessToken'
}

# `< /dev/null` não é detalhe: sem ele o `compose exec` LÊ a entrada padrão do script e engole as
# teclas do entrega.sh — o Enter do operador sumia antes de chegar ao `read`.
psql_() { # consulta de UMA coluna, sem cabeçalho
  bash "$COMPOSE" exec -T db psql -U omnitribo -d omnitribo -tAc "$1" < /dev/null | tr -d '\r'
}
