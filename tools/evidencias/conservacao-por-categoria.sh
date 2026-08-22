#!/usr/bin/env bash
# Mede a invariante de CONSERVAÇÃO por categoria contra o banco de pé.
#
#   conservacao = SUM(carteira.saldo_tokens) + SUM(missao.pote_tokens)
#
# TRIBO paga do pote -> a soma não deve mudar (Δ = 0)
# AJUDA paga do pote -> a soma não deve mudar (Δ = 0)
#
# AJUDA CUNHAVA até o ADR 0025, e este script media exatamente isso: Δ > 0. O argumento que a mantinha
# fora do pote — "exigir pote faria membros da tribo custearem a logística do varejista" — descreve
# ENTREGA, que tem varejista do outro lado; AJUDA é missão entre vizinhos, como TRIBO. Agora as duas
# seguem a mesma regra, e quem financia NUNCA é o criador (ADR 0009).
#
# Em ambos os casos, a RECONCILIAÇÃO (ledger × projeção) deve continuar respondendo integro=true —
# é exatamente esse o ponto: são invariantes diferentes, e a segunda passa mesmo quando a primeira
# é violada.
set -uo pipefail

API=http://localhost:8080
# Se a sua máquina usa podman em vez de Docker Desktop, exporte o socket antes:
#   export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock
PSQL=(docker compose exec -T db psql -U omnitribo -d omnitribo -tAc)

cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 1

medir() { "${PSQL[@]}" \
  "SELECT (SELECT COALESCE(SUM(saldo_tokens),0) FROM carteira) || '|' ||
          (SELECT COALESCE(SUM(pote_tokens),0)  FROM missao)   || '|' ||
          (SELECT COALESCE(SUM(saldo_tokens),0) FROM carteira)
        + (SELECT COALESCE(SUM(pote_tokens),0)  FROM missao);"; }

login() { curl -s -X POST "$API/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$1\",\"senha\":\"Senha@123\"}" | jq -r '.accessToken'; }

api() { # api METODO CAMINHO TOKEN [CORPO]
  local m=$1 p=$2 t=$3 body=${4:-}
  if [[ -n "$body" ]]; then
    curl -s -X "$m" "$API$p" -H "Authorization: Bearer $t" -H 'Content-Type: application/json' \
      -H "Idempotency-Key: $(uuidgen)" -d "$body"
  else
    curl -s -X "$m" "$API$p" -H "Authorization: Bearer $t" -H "Idempotency-Key: $(uuidgen)"
  fi; }

recon() { curl -s "$API/api/v1/admin/carteiras/reconciliacao" -H "Authorization: Bearer $1" \
  | jq -c '{integro, divergencias: (.divergencias|length)}'; }

ORIGEM_LAT=-23.564; ORIGEM_LON=-46.6934

corpo() { # corpo CATEGORIA TITULO
  local agora ini fim
  agora=$(date +%s)
  ini=$(date -u -d "@$((agora-3600))" +%Y-%m-%dT%H:%M:%SZ)
  fim=$(date -u -d "@$((agora+172800))" +%Y-%m-%dT%H:%M:%SZ)
  cat <<EOF
{"categoria":"$1","titulo":"$2","descricao":"Evidência F13 — medição da invariante de conservação por categoria.",
 "valorBrl":0,"complexidade":"MEDIA","origemLat":$ORIGEM_LAT,"origemLon":$ORIGEM_LON,
 "cep":"05416000","logradouro":"Rua Teodoro Sampaio","bairro":"Pinheiros","cidade":"São Paulo","uf":"SP",
 "raioCheckinM":50,"janelaInicio":"$ini","janelaFim":"$fim"}
EOF
}

echo "############ LOGINS ############"
ALICE=$(login alice@omnitribo.dev); BOB=$(login bob@omnitribo.dev); CAROL=$(login carol@omnitribo.dev)
ADMIN=$(login admin@omnitribo.dev)
for n in ALICE BOB CAROL ADMIN; do
  v=${!n}; [[ ${#v} -gt 20 ]] && echo "$n ok" || { echo "$n FALHOU: $v"; exit 1; }
done

echo
echo "############ BASELINE ############"
BASE=$(medir); echo "carteiras|potes|total = $BASE"
echo "reconciliação inicial: $(recon "$ADMIN")"

# ─────────────────────────────────────────────────────────────────────────────
echo
echo "############ CICLO 1 — AJUDA financiada (esperado: CONSERVAÇÃO, Δ = 0) ############"
A1=$(medir | cut -d'|' -f3)
PREVIA=$(api POST /api/v1/missoes/previa-recompensa "$BOB" "$(corpo AJUDA 'F13 AJUDA')")
TOK_AJUDA=$(echo "$PREVIA" | jq -r '.tokensRecompensa'); XP_AJUDA=$(echo "$PREVIA" | jq -r '.xpRecompensa')
echo "prévia: ${TOK_AJUDA} tokens, ${XP_AJUDA} XP"

# Bob CRIA e Carol FINANCIA — os dois na Vila Madalena. O criador não paga (ADR 0009), e o
# financiamento exige mesma tribo, então o par tem de sair da mesma comunidade. Alice EXECUTA:
# aceitar não tem restrição de tribo, e usar alguém de fora deixa claro que o token que ela recebe
# é o token que Carol pôs no pote.
M1=$(api POST /api/v1/missoes "$BOB" "$(corpo AJUDA 'F13 AJUDA')")
M1_ID=$(echo "$M1" | jq -r '.id')
echo "criada: $M1_ID status=$(echo "$M1" | jq -r '.status') tokens=$(echo "$M1" | jq -r '.tokensRecompensa') pote=$(echo "$M1" | jq -r '.poteTokens // 0')"

TRIBO_BOB=$("${PSQL[@]}" "SELECT tribo_id FROM usuario WHERE email='bob@omnitribo.dev';" | tr -d ' ')
FIN1=$(api POST "/api/v1/tribos/$TRIBO_BOB/financiamentos" "$CAROL" \
  "{\"missaoId\":\"$M1_ID\",\"tokens\":$TOK_AJUDA}")
echo "financiamento por carol: $(echo "$FIN1" | jq -c '{poteTokens, saldoTokensRestante} // .')"
echo "  (conservação logo após financiar: $(medir))"

echo "publicar: $(api POST "/api/v1/missoes/$M1_ID/publicar" "$BOB"   | jq -r '.status')"
echo "aceitar:  $(api POST "/api/v1/missoes/$M1_ID/aceitar"  "$ALICE" | jq -r '.status')"
echo "iniciar:  $(api POST "/api/v1/missoes/$M1_ID/iniciar"  "$ALICE" | jq -r '.status')"
CHK=$(api POST "/api/v1/missoes/$M1_ID/checkin" "$ALICE" \
  "{\"lat\":$ORIGEM_LAT,\"lon\":$ORIGEM_LON,\"acuraciaM\":8.0,\"mocked\":false}")
echo "checkin:  $(echo "$CHK" | jq -r '.status // .type')"
echo "confirmar:$(api POST "/api/v1/missoes/$M1_ID/confirmar" "$BOB" | jq -r '.status')"

A2=$(medir | cut -d'|' -f3)
echo "--> conservação antes=$A1  depois=$A2  Δ=$((A2-A1))   (recompensa da missão: $TOK_AJUDA)"
echo "--> reconciliação: $(recon "$ADMIN")"

# ─────────────────────────────────────────────────────────────────────────────
echo
echo "############ CICLO 2 — TRIBO financiada (esperado: CONSERVAÇÃO, Δ = 0) ############"
B1=$(medir | cut -d'|' -f3)
PREVIA2=$(api POST /api/v1/missoes/previa-recompensa "$BOB" "$(corpo TRIBO 'F13 TRIBO')")
TOK_TRIBO=$(echo "$PREVIA2" | jq -r '.tokensRecompensa')
echo "prévia: ${TOK_TRIBO} tokens"

M2=$(api POST /api/v1/missoes "$BOB" "$(corpo TRIBO 'F13 TRIBO')")
M2_ID=$(echo "$M2" | jq -r '.id')
echo "criada: $M2_ID status=$(echo "$M2" | jq -r '.status')"

TRIBO_ID=$TRIBO_BOB
echo "tribo de bob: $TRIBO_ID"
FIN=$(api POST "/api/v1/tribos/$TRIBO_ID/financiamentos" "$CAROL" \
  "{\"missaoId\":\"$M2_ID\",\"tokens\":$TOK_TRIBO}")
echo "financiamento por carol: $(echo "$FIN" | jq -c '{poteTokens, saldoTokens} // .')"
echo "  (conservação logo após financiar: $(medir))"

echo "publicar: $(api POST "/api/v1/missoes/$M2_ID/publicar" "$BOB"   | jq -r '.status')"
echo "aceitar:  $(api POST "/api/v1/missoes/$M2_ID/aceitar"  "$CAROL" | jq -r '.status')"
echo "iniciar:  $(api POST "/api/v1/missoes/$M2_ID/iniciar"  "$CAROL" | jq -r '.status')"
CHK2=$(api POST "/api/v1/missoes/$M2_ID/checkin" "$CAROL" \
  "{\"lat\":$ORIGEM_LAT,\"lon\":$ORIGEM_LON,\"acuraciaM\":8.0,\"mocked\":false}")
echo "checkin:  $(echo "$CHK2" | jq -r '.status // .type')"
echo "confirmar:$(api POST "/api/v1/missoes/$M2_ID/confirmar" "$BOB" | jq -r '.status')"

B2=$(medir | cut -d'|' -f3)
echo "--> conservação antes=$B1  depois=$B2  Δ=$((B2-B1))   (recompensa da missão: $TOK_TRIBO)"
echo "--> reconciliação: $(recon "$ADMIN")"

echo
echo "############ RESUMO ############"
echo "AJUDA  Δ=$((A2-A1))  recompensa=$TOK_AJUDA   (era +$TOK_AJUDA até o ADR 0025)"
echo "TRIBO  Δ=$((B2-B1))  recompensa=$TOK_TRIBO"
echo "reconciliação final: $(recon "$ADMIN")"
echo "lançamentos por motivo:"
"${PSQL[@]}" "SELECT motivo || ' = ' || count(*) FROM lancamento GROUP BY motivo ORDER BY 1;"
