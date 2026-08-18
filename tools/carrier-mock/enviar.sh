#!/usr/bin/env bash
# Transportadora de mentira: exercita o webhook do "Fim da Entrega Falida" contra o
# servidor LOCAL em execução.
#
# Dispara o caminho feliz e os CINCO negativos, e confere o resultado de cada um. É a
# demonstração ponta a ponta da tese do produto — uma entrega que falhou vira missão
# comunitária — e o roteiro para defender o módulo oralmente.
#
# Uso:
#   cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # noutro terminal
#   bash tools/carrier-mock/enviar.sh
#
# Variáveis (todas com default de desenvolvimento):
#   API              http://localhost:8080
#   SEGREDO          precisa bater com app.webhooks.segredos.transportadora-dev
#   PONTO_CUSTODIA   ponto com vaga (default: Leroy Merlin Pinheiros, do seed)
#   PONTO_LOTADO     ponto sem vaga (default: Portaria Ed. Aurora, do seed V904)
set -euo pipefail

API="${API:-http://localhost:8080}"
URL="$API/api/v1/webhooks/transportadora"
TRANSPORTADORA="${TRANSPORTADORA:-transportadora-dev}"
# Default idêntico ao de application-dev.yml. Trocar um exige trocar o outro.
SEGREDO="${SEGREDO:-segredo-de-desenvolvimento-local}"
PONTO_CUSTODIA="${PONTO_CUSTODIA:-cccccccc-0000-0000-0000-000000000001}"
PONTO_LOTADO="${PONTO_LOTADO:-cccccccc-0000-0000-0000-000000000904}"

for programa in curl openssl; do
  command -v "$programa" >/dev/null || { echo "Faltando: $programa"; exit 1; }
done

if ! curl -sf "$API/api/v1/ping" >/dev/null; then
  echo "ERRO: nada respondendo em $API."
  echo "Suba o servidor: cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev"
  exit 1
fi

verde=$'\033[32m'; vermelho=$'\033[31m'; cinza=$'\033[90m'; normal=$'\033[0m'
falhas=0

# Código de rastreio único por execução: o replay abaixo é demonstrado DE PROPÓSITO, com
# duas chamadas iguais. Sem isto, uma segunda execução do script encontraria tudo já
# registrado e o caminho feliz viraria replay sem ninguém notar.
execucao="$(date +%s)"

assinar() { # $1=timestamp  $2=corpo
  printf '%s.%s' "$1" "$2" \
    | openssl dgst -sha256 -hmac "$SEGREDO" -hex \
    | sed 's/^.*= //'
}

corpo() { # $1=rastreio  $2=ponto
  cat <<JSON
{"codigoRastreio":"$1","motivo":"Destinatário ausente após 3 tentativas de entrega",
 "pontoCustodiaId":"$2","descricaoDoItem":"2 caixas de porcelanato 60x60",
 "pesoKg":24.50,"volumeL":58.00,"valorOfertadoBrl":35.00,
 "destinoLat":-23.5695,"destinoLon":-46.6870,
 "cep":"05416000","logradouro":"Rua Teodoro Sampaio","bairro":"Pinheiros",
 "cidade":"São Paulo","uf":"SP"}
JSON
}

# $1=rótulo  $2=status esperado  $3=corpo  $4=assinatura  $5=timestamp  $6=transportadora
enviar() {
  local rotulo="$1" esperado="$2" corpo="$3" assinatura="$4" ts="$5" quem="$6"
  local resposta status json
  resposta=$(curl -s -w '\n%{http_code}' -X POST "$URL" \
    -H 'Content-Type: application/json' \
    -H "X-Transportadora: $quem" \
    -H "X-Timestamp: $ts" \
    -H "X-Assinatura: $assinatura" \
    --data-binary "$corpo")
  status=$(printf '%s' "$resposta" | tail -n1)
  json=$(printf '%s' "$resposta" | sed '$d')

  if [ "$status" = "$esperado" ]; then
    printf '%s  OK %s  %-38s HTTP %s\n' "$verde" "$normal" "$rotulo" "$status"
  else
    printf '%s FALHOU%s  %-38s HTTP %s (esperado %s)\n' \
      "$vermelho" "$normal" "$rotulo" "$status" "$esperado"
    falhas=$((falhas + 1))
  fi
  printf '%s        %s%s\n' "$cinza" "$(printf '%s' "$json" | head -c 220)" "$normal"
  RESPOSTA_JSON="$json"
}

echo
echo "Webhook de transportadora → $URL"
echo "Transportadora: $TRANSPORTADORA"
echo

# ── 1. Caminho feliz ────────────────────────────────────────────────────────────────
rastreio="BR${execucao}FELIZ"
c=$(corpo "$rastreio" "$PONTO_CUSTODIA")
ts=$(date +%s)
enviar "caminho feliz → vira missão" 200 "$c" "$(assinar "$ts" "$c")" "$ts" "$TRANSPORTADORA"

# ── 2. Replay: exatamente a mesma encomenda ─────────────────────────────────────────
# Timestamp e assinatura NOVOS, corpo idêntico. É o retry real de uma transportadora que
# não recebeu a resposta: a idempotência é por (transportadora, codigoRastreio), não pela
# assinatura. Tem de devolver a MESMA missão, sem criar uma segunda.
ts=$(date +%s)
enviar "replay → mesma missão, sem duplicar" 200 "$c" "$(assinar "$ts" "$c")" "$ts" "$TRANSPORTADORA"

# ── 3. Assinatura errada ────────────────────────────────────────────────────────────
rastreio="BR${execucao}ASSIN"
c=$(corpo "$rastreio" "$PONTO_CUSTODIA")
ts=$(date +%s)
enviar "assinatura inválida → 401" 401 "$c" "$(printf '00%.0s' {1..32})" "$ts" "$TRANSPORTADORA"

# ── 4. Timestamp velho ──────────────────────────────────────────────────────────────
# Assinatura ÍNTEGRA para o carimbo enviado: só a janela venceu. Sem o timestamp dentro do
# material assinado, este seria o replay trivial — capturar e reenviar depois.
rastreio="BR${execucao}VELHO"
c=$(corpo "$rastreio" "$PONTO_CUSTODIA")
ts=$(( $(date +%s) - 600 ))
enviar "timestamp de 10 min atrás → 401" 401 "$c" "$(assinar "$ts" "$c")" "$ts" "$TRANSPORTADORA"

# ── 5. Ponto lotado ─────────────────────────────────────────────────────────────────
# NÃO é erro HTTP: a requisição foi válida e o fato ficou gravado. Devolver 4xx faria a
# transportadora reenviar em laço contra um ponto que continuará lotado.
rastreio="BR${execucao}LOTAD"
c=$(corpo "$rastreio" "$PONTO_LOTADO")
ts=$(date +%s)
enviar "ponto lotado → 200 RECUSADA, sem missão" 200 "$c" "$(assinar "$ts" "$c")" "$ts" "$TRANSPORTADORA"
case "$RESPOSTA_JSON" in
  *'"desfecho":"RECUSADA"'*) ;;
  *) echo "${vermelho} FALHOU${normal}  ponto lotado devolveu desfecho inesperado"; falhas=$((falhas + 1)) ;;
esac

# ── 6. Ponto inexistente ────────────────────────────────────────────────────────────
rastreio="BR${execucao}NOPTO"
c=$(corpo "$rastreio" "cccccccc-9999-9999-9999-999999999999")
ts=$(date +%s)
enviar "ponto inexistente → 404" 404 "$c" "$(assinar "$ts" "$c")" "$ts" "$TRANSPORTADORA"

echo
if [ "$falhas" -eq 0 ]; then
  echo "${verde}Todos os 6 cenários responderam como esperado.${normal}"
  echo "Veja a missão criada em: $API/swagger-ui.html  →  GET /api/v1/missoes?categoria=ENTREGA"
else
  echo "${vermelho}$falhas cenário(s) fora do esperado.${normal}"
  exit 1
fi
