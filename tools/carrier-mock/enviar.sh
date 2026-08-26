#!/usr/bin/env bash
# Transportadora de mentira: exercita o webhook do "Fim da Entrega Falida" contra o
# servidor LOCAL em execução.
#
# Dispara o CICLO COMPLETO — falha reportada, vizinho executa, transportadora confirma, executor
# creditado — mais os negativos dos dois webhooks, conferindo o resultado de cada um. É a
# demonstração ponta a ponta da tese do produto — uma entrega que falhou vira missão
# comunitária REMUNERADA — e o roteiro para defender o módulo oralmente.
#
# Até o ADR 0026 este script parava na criação da missão: ninguém conseguia confirmá-la, porque o
# criador é o usuário-sistema e AtorEsperado.CRIADOR compara identidade. O ciclo só fechava 72 h
# depois, pela varredura de prazo — que continua existindo como rede de segurança.
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
#   EXECUTOR         e-mail do vizinho que executa (default: alice, nível 3 — a missão de retirada
#                    exige nível >= app.missoes.entrega-falida.nivel-minimo, que é 2)
set -euo pipefail

API="${API:-http://localhost:8080}"
URL="$API/api/v1/webhooks/transportadora"
URL_CONFIRMACAO="$URL/confirmacao"
EXECUTOR="${EXECUTOR:-alice@omnitribo.dev}"
SENHA_SEED="${SENHA_SEED:-Senha@123}"
TRANSPORTADORA="${TRANSPORTADORA:-transportadora-dev}"
# Default idêntico ao de application-dev.yml. Trocar um exige trocar o outro.
SEGREDO="${SEGREDO:-segredo-de-desenvolvimento-local}"
PONTO_CUSTODIA="${PONTO_CUSTODIA:-cccccccc-0000-0000-0000-000000000001}"
PONTO_LOTADO="${PONTO_LOTADO:-cccccccc-0000-0000-0000-000000000904}"
# Coordenada do check-in do ciclo completo, e do destino declarado no reporte. Os defaults são os do
# Leroy Merlin Pinheiros (V900), que é o PONTO_CUSTODIA default — e é essa amarração que importa:
# a missão de entrega falida exige check-in a menos de `app.missoes.entrega-falida.raio-checkin-m`
# (200 m) da origem, que é o ponto de custódia. Trocar PONTO_CUSTODIA sem trocar estas duas faz o
# check-in reprovar por distância, e o sintoma (422 no meio do ciclo) não menciona coordenada nenhuma.
CHECKIN_LAT="${CHECKIN_LAT:--23.5640}"
CHECKIN_LON="${CHECKIN_LON:--46.6934}"
DESTINO_LAT="${DESTINO_LAT:--23.5695}"
DESTINO_LON="${DESTINO_LON:--46.6870}"

for programa in curl openssl jq; do
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

# ── Cliente HTTP autenticado, para a parte do ciclo que é do USUÁRIO ────────────────
# O mock precisa disto porque aceitar, iniciar e fazer check-in são atos do vizinho, com JWT.
# Só o reporte e a confirmação são da transportadora, por HMAC.
login() {
  curl -s -X POST "$API/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"senha\":\"$SENHA_SEED\"}" | jq -r '.accessToken'
}

acao() { # acao TOKEN MISSAO ACAO [CORPO]
  local extra=()
  [ -n "${4:-}" ] && extra=(-H 'Content-Type: application/json' -d "$4")
  curl -s -X POST "$API/api/v1/missoes/$2/$3" \
    -H "Authorization: Bearer $1" -H "Idempotency-Key: mock-$3-$2" "${extra[@]}"
}

saldo_de() { # saldo_de EMAIL — lê a projeção direto do banco, que é o número que a banca confere
  docker compose exec -T db psql -U omnitribo -d omnitribo -tAc \
    "SELECT c.saldo_tokens FROM carteira c JOIN usuario u ON u.id = c.usuario_id
      WHERE u.email = '$1';" | tr -d ' \r'
}

corpo() { # $1=rastreio  $2=ponto
  cat <<JSON
{"codigoRastreio":"$1","motivo":"Destinatário ausente após 3 tentativas de entrega",
 "pontoCustodiaId":"$2","descricaoDoItem":"2 caixas de porcelanato 60x60",
 "pesoKg":24.50,"volumeL":58.00,"valorOfertadoBrl":35.00,
 "destinoLat":$DESTINO_LAT,"destinoLon":$DESTINO_LON,
 "cep":"05416000","logradouro":"Rua Teodoro Sampaio","bairro":"Pinheiros",
 "cidade":"São Paulo","uf":"SP"}
JSON
}

# $1=rótulo  $2=status esperado  $3=corpo  $4=assinatura  $5=timestamp  $6=transportadora
# $7=URL (opcional; default é o webhook de reporte)
enviar() {
  local rotulo="$1" esperado="$2" corpo="$3" assinatura="$4" ts="$5" quem="$6" alvo="${7:-$URL}"
  local resposta status json
  resposta=$(curl -s -w '\n%{http_code}' -X POST "$alvo" \
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

# ── 7. CICLO COMPLETO: da falha ao crédito, no mesmo minuto ─────────────────────────
# Até o ADR 0026 este bloco era impossível: ninguém podia confirmar uma missão cujo criador é o
# usuário-sistema, e o executor esperava as 72 h de prazo-confirmacao para receber.
echo
echo "── Ciclo completo: falha reportada → vizinho executa → transportadora confirma ──"

rastreio="BR${execucao}CICLO"
c=$(corpo "$rastreio" "$PONTO_CUSTODIA")
ts=$(date +%s)
enviar "reporte da falha → vira missão" 200 "$c" "$(assinar "$ts" "$c")" "$ts" "$TRANSPORTADORA"
MISSAO=$(printf '%s' "$RESPOSTA_JSON" | jq -r '.missaoId')

TOKEN=$(login "$EXECUTOR")
if [ ${#TOKEN} -lt 20 ]; then
  echo "${vermelho} FALHOU${normal}  login de $EXECUTOR não devolveu token"
  falhas=$((falhas + 1))
else
  SALDO_ANTES=$(saldo_de "$EXECUTOR")
  echo "${cinza}        executor: $EXECUTOR  saldo ANTES: ${SALDO_ANTES:-?} tokens${normal}"

  printf '  ..  %-38s %s\n' "aceitar"  "$(acao "$TOKEN" "$MISSAO" aceitar  | jq -r '.status // .detail')"
  printf '  ..  %-38s %s\n' "iniciar"  "$(acao "$TOKEN" "$MISSAO" iniciar  | jq -r '.status // .detail')"
  printf '  ..  %-38s %s\n' "check-in" "$(acao "$TOKEN" "$MISSAO" checkin \
    "{\"lat\":$CHECKIN_LAT,\"lon\":$CHECKIN_LON,\"acuraciaM\":8.0,\"mocked\":false}" | jq -r '.status // .detail')"

  cc=$(printf '{"codigoRastreio":"%s"}' "$rastreio")
  ts=$(date +%s)
  enviar "confirmação → executor creditado" 200 "$cc" "$(assinar "$ts" "$cc")" "$ts" \
    "$TRANSPORTADORA" "$URL_CONFIRMACAO"

  SALDO_DEPOIS=$(saldo_de "$EXECUTOR")
  CREDITADO=$(printf '%s' "$RESPOSTA_JSON" | jq -r '.tokensCreditados')
  echo "${cinza}        saldo DEPOIS: ${SALDO_DEPOIS:-?} tokens  (creditados: $CREDITADO)${normal}"

  if [ "$((SALDO_DEPOIS - SALDO_ANTES))" = "$CREDITADO" ] && [ "$CREDITADO" -gt 0 ]; then
    echo "${verde}  OK ${normal}  saldo subiu exatamente a recompensa: +$CREDITADO"
  else
    echo "${vermelho} FALHOU${normal}  saldo foi de $SALDO_ANTES para $SALDO_DEPOIS, creditados=$CREDITADO"
    falhas=$((falhas + 1))
  fi

  # ── 8. Replay da confirmação: no-op, saldo intacto ────────────────────────────────
  ts=$(date +%s)
  enviar "replay da confirmação → no-op" 200 "$cc" "$(assinar "$ts" "$cc")" "$ts" \
    "$TRANSPORTADORA" "$URL_CONFIRMACAO"
  SALDO_REPLAY=$(saldo_de "$EXECUTOR")
  case "$RESPOSTA_JSON" in
    *'"replay":true'*) ;;
    *) echo "${vermelho} FALHOU${normal}  replay não foi sinalizado"; falhas=$((falhas + 1)) ;;
  esac
  if [ "$SALDO_REPLAY" = "$SALDO_DEPOIS" ]; then
    echo "${verde}  OK ${normal}  saldo intacto no replay: $SALDO_REPLAY"
  else
    echo "${vermelho} FALHOU${normal}  replay mexeu no saldo: $SALDO_DEPOIS → $SALDO_REPLAY"
    falhas=$((falhas + 1))
  fi
fi

# ── 9. Confirmação com assinatura inválida ──────────────────────────────────────────
cc=$(printf '{"codigoRastreio":"%s"}' "BR${execucao}CICLO")
ts=$(date +%s)
enviar "confirmação com assinatura inválida → 401" 401 "$cc" "$(printf '00%.0s' {1..32})" "$ts" \
  "$TRANSPORTADORA" "$URL_CONFIRMACAO"

# ── 10. Confirmação de rastreio desconhecido ────────────────────────────────────────
# 404, e não 200: diferente do ponto lotado, aqui não há fato NOVO a gravar. Um 200 diria
# "confirmado" para uma encomenda que o sistema nunca viu.
cc=$(printf '{"codigoRastreio":"NAO-EXISTE-%s"}' "$execucao")
ts=$(date +%s)
enviar "confirmação de rastreio desconhecido → 404" 404 "$cc" "$(assinar "$ts" "$cc")" "$ts" \
  "$TRANSPORTADORA" "$URL_CONFIRMACAO"

# ── 11. Confirmação de missão que ninguém executou ──────────────────────────────────
# A missão do caminho feliz (cenário 1) está ABERTA: sem aceite e sem check-in, CONFIRMAR não cabe.
# 409 é o contrato do projeto para "não cabe neste estado, caberia em outro".
cc=$(printf '{"codigoRastreio":"BR%sFELIZ"}' "$execucao")
ts=$(date +%s)
enviar "confirmação sem check-in → 409" 409 "$cc" "$(assinar "$ts" "$cc")" "$ts" \
  "$TRANSPORTADORA" "$URL_CONFIRMACAO"

echo
if [ "$falhas" -eq 0 ]; then
  echo "${verde}Todos os cenários responderam como esperado.${normal}"
  echo "Veja a missão criada em: $API/swagger-ui.html  →  GET /api/v1/missoes?categoria=ENTREGA"
else
  echo "${vermelho}$falhas cenário(s) fora do esperado.${normal}"
  exit 1
fi
