#!/usr/bin/env bash
# Mede a invariante de CONSERVAÇÃO nas QUATRO categorias, contra o banco de pé.
#
#   conservacao = SUM(carteira.saldo_tokens) + SUM(missao.pote_tokens)
#
# Desde o ADR 0024 (patrocinador) e o ADR 0025 (AJUDA), toda missão com financiador paga o executor
# DO POTE. As quatro categorias devem fechar Δ = 0:
#
#   TRIBO   -> pote financiado por membro da tribo
#   COLETA  -> idem
#   AJUDA   -> idem (cunhava até o ADR 0025)
#   ENTREGA -> pote financiado pelo PATROCINADOR, na conversão do webhook de entrega falida
#
# E a RECONCILIAÇÃO (ledger × projeção) deve responder integro=true o tempo todo — não como prova de
# conservação, mas como demonstração de que são invariantes DIFERENTES: a reconciliação passaria
# mesmo se um token fosse cunhado, porque cunhar escreve os dois lados.
#
# Um quinto ciclo mede a recusa: transportadora integrada, patrocinador SEM saldo. Deve responder
# 200 com desfecho SEM_PATROCINIO, sem missão e sem token cunhado.
#
# Nenhum pré-requisito além do servidor de pé em dev. Até o ADR 0026 este script precisava subi-lo
# com a varredura de expiração acelerada e recuar `estado_desde` por SQL, porque ninguém conseguia
# confirmar uma missão cujo criador é o usuário-sistema. O webhook de confirmação fechou isso: o
# ciclo 4 agora termina por HTTP, como os outros três.
set -uo pipefail

API=http://localhost:8080
# Se a sua máquina usa podman em vez de Docker Desktop, exporte o socket antes:
#   export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock
PSQL=(docker compose exec -T db psql -U omnitribo -d omnitribo -tAc)

cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 1

falhas=0
conferir() { # conferir RÓTULO ESPERADO OBTIDO
  if [[ "$2" == "$3" ]]; then
    echo "    ✓ $1: $3"
  else
    echo "    ✗ $1: obtido '$3', esperado '$2'"
    falhas=$((falhas + 1))
  fi
}

medir() { "${PSQL[@]}" \
  "SELECT (SELECT COALESCE(SUM(saldo_tokens),0) FROM carteira) || '|' ||
          (SELECT COALESCE(SUM(pote_tokens),0)  FROM missao)   || '|' ||
          (SELECT COALESCE(SUM(saldo_tokens),0) FROM carteira)
        + (SELECT COALESCE(SUM(pote_tokens),0)  FROM missao);"; }

total() { medir | cut -d'|' -f3; }

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

# Leroy Merlin Pinheiros — mesma coordenada do ponto de custódia do seed, para que o check-in do
# ciclo 4 caia dentro do raio da missão de retirada (a origem dela é o ponto, não o endereço).
ORIGEM_LAT=-23.564; ORIGEM_LON=-46.6934
PONTO_CUSTODIA=cccccccc-0000-0000-0000-000000000001

corpo() { # corpo CATEGORIA TITULO EXTRA_JSON
  local agora ini fim
  agora=$(date +%s)
  ini=$(date -u -d "@$((agora-3600))" +%Y-%m-%dT%H:%M:%SZ)
  fim=$(date -u -d "@$((agora+172800))" +%Y-%m-%dT%H:%M:%SZ)
  cat <<EOF
{"categoria":"$1","titulo":"$2","descricao":"Evidência F14 — conservação do token nas quatro categorias.",
 "valorBrl":0,$3"origemLat":$ORIGEM_LAT,"origemLon":$ORIGEM_LON,
 "cep":"05416000","logradouro":"Rua Teodoro Sampaio","bairro":"Pinheiros","cidade":"São Paulo","uf":"SP",
 "raioCheckinM":50,"janelaInicio":"$ini","janelaFim":"$fim"}
EOF
}

# ─────────────────────────────────────────────────────────────────────────────
# Um ciclo comunitário completo, ponta a ponta por HTTP.
#
#   ciclo_comunitario CATEGORIA EXTRA_JSON CRIADOR FINANCIADOR EXECUTOR TRIBO_DO_CRIADOR
#
# Quem financia NUNCA é o criador: o ADR 0009 mantém "quem cria a missão não paga", e é justamente
# essa separação que o teste mede. Financiador e criador precisam ser da MESMA tribo, porque
# FinanciamentoService.validarAutorizacao exige afiliação; o executor pode ser de qualquer uma.
# ─────────────────────────────────────────────────────────────────────────────
ciclo_comunitario() {
  local categoria=$1 extra=$2 criador=$3 financiador=$4 executor=$5 tribo=$6
  local antes previa tokens missao

  antes=$(total)
  previa=$(api POST /api/v1/missoes/previa-recompensa "$criador" "$(corpo "$categoria" "F14 $categoria" "$extra")")
  tokens=$(echo "$previa" | jq -r '.tokensRecompensa')
  echo "prévia: ${tokens} tokens, $(echo "$previa" | jq -r '.xpRecompensa') XP"

  missao=$(api POST /api/v1/missoes "$criador" "$(corpo "$categoria" "F14 $categoria" "$extra")")
  local id; id=$(echo "$missao" | jq -r '.id')
  echo "criada: $id status=$(echo "$missao" | jq -r '.status') pote=$(echo "$missao" | jq -r '.poteTokens // 0')"

  local fin; fin=$(api POST "/api/v1/tribos/$tribo/financiamentos" "$financiador" \
    "{\"missaoId\":\"$id\",\"tokens\":$tokens}")
  echo "financiado: $(echo "$fin" | jq -c '{poteTokens, saldoTokensRestante} // .')"
  conferir "financiar não cria nem destrói" "$antes" "$(total)"

  echo "publicar: $(api POST "/api/v1/missoes/$id/publicar" "$criador"  | jq -r '.status')"
  echo "aceitar:  $(api POST "/api/v1/missoes/$id/aceitar"  "$executor" | jq -r '.status')"
  echo "iniciar:  $(api POST "/api/v1/missoes/$id/iniciar"  "$executor" | jq -r '.status')"
  echo "checkin:  $(api POST "/api/v1/missoes/$id/checkin" "$executor" \
    "{\"lat\":$ORIGEM_LAT,\"lon\":$ORIGEM_LON,\"acuraciaM\":8.0,\"mocked\":false}" \
    | jq -r '.status // .type')"
  echo "confirmar:$(api POST "/api/v1/missoes/$id/confirmar" "$criador" | jq -r '.status')"

  local depois; depois=$(total)
  echo "--> conservação antes=$antes depois=$depois Δ=$((depois-antes))  (recompensa: $tokens)"
  conferir "Δ do ciclo $categoria" "0" "$((depois-antes))"
  echo "--> reconciliação: $(recon "$ADMIN")"
  DELTA=$((depois-antes)); RECOMPENSA=$tokens
}

# ─────────────────────────────────────────────────────────────────────────────
# Webhook de transportadora, assinado como o tools/carrier-mock/ assina.
# ─────────────────────────────────────────────────────────────────────────────
assinar() { # assinar TIMESTAMP CORPO SEGREDO
  printf '%s.%s' "$1" "$2" | openssl dgst -sha256 -hmac "$3" -hex | sed 's/^.*= //'
}

corpo_webhook() { # corpo_webhook RASTREIO
  cat <<JSON
{"codigoRastreio":"$1","motivo":"Destinatário ausente após 3 tentativas de entrega",
 "pontoCustodiaId":"$PONTO_CUSTODIA","descricaoDoItem":"2 caixas de porcelanato 60x60",
 "pesoKg":24.50,"volumeL":58.00,"valorOfertadoBrl":35.00,
 "destinoLat":-23.5695,"destinoLon":-46.6870,
 "cep":"05416000","logradouro":"Rua Teodoro Sampaio","bairro":"Pinheiros",
 "cidade":"São Paulo","uf":"SP"}
JSON
}

webhook() { # webhook SLUG SEGREDO CORPO [CAMINHO]
  local ts; ts=$(date +%s)
  curl -s -X POST "$API/api/v1/webhooks/transportadora${4:-}" \
    -H 'Content-Type: application/json' \
    -H "X-Transportadora: $1" -H "X-Timestamp: $ts" \
    -H "X-Assinatura: $(assinar "$ts" "$3" "$2")" \
    --data-binary "$3"
}

webhook_com_status() { # igual a webhook(), mas devolve o corpo e o status HTTP na última linha
  local ts; ts=$(date +%s)
  curl -s -w '\n%{http_code}' -X POST "$API/api/v1/webhooks/transportadora${4:-}" \
    -H 'Content-Type: application/json' \
    -H "X-Transportadora: $1" -H "X-Timestamp: $ts" \
    -H "X-Assinatura: $(assinar "$ts" "$3" "$2")" \
    --data-binary "$3"
}

echo "############ LOGINS ############"
ALICE=$(login alice@omnitribo.dev); BOB=$(login bob@omnitribo.dev); CAROL=$(login carol@omnitribo.dev)
ADMIN=$(login admin@omnitribo.dev)
for n in ALICE BOB CAROL ADMIN; do
  v=${!n}; [[ ${#v} -gt 20 ]] && echo "$n ok" || { echo "$n FALHOU: $v"; exit 1; }
done

TRIBO_BOB=$("${PSQL[@]}" "SELECT tribo_id FROM usuario WHERE email='bob@omnitribo.dev';" | tr -d ' ')
TRIBO_CAROL=$("${PSQL[@]}" "SELECT tribo_id FROM usuario WHERE email='carol@omnitribo.dev';" | tr -d ' ')

echo
echo "############ BASELINE ############"
echo "carteiras|potes|total = $(medir)"
echo "reconciliação inicial: $(recon "$ADMIN")"
BASELINE=$(total)

echo
echo "############ CICLO 1 — TRIBO (bob cria, carol financia, alice executa) ############"
ciclo_comunitario TRIBO '"complexidade":"MEDIA",' "$BOB" "$CAROL" "$ALICE" "$TRIBO_BOB"
D_TRIBO=$DELTA; R_TRIBO=$RECOMPENSA

echo
echo "############ CICLO 2 — COLETA (carol cria, bob financia, alice executa) ############"
# COLETA move objeto físico: peso e volume são OBRIGATÓRIOS e a complexidade é DERIVADA pelo
# servidor. Declarar os dois juntos é 400 — ver CriacaoMissaoVerificador.
ciclo_comunitario COLETA '"pesoKg":8.00,"volumeL":20.00,' "$CAROL" "$BOB" "$ALICE" "$TRIBO_CAROL"
D_COLETA=$DELTA; R_COLETA=$RECOMPENSA

echo
echo "############ CICLO 3 — AJUDA (bob cria, carol financia, alice executa) ############"
ciclo_comunitario AJUDA '"complexidade":"MEDIA",' "$BOB" "$CAROL" "$ALICE" "$TRIBO_BOB"
D_AJUDA=$DELTA; R_AJUDA=$RECOMPENSA

echo
echo "############ CICLO 4 — ENTREGA via webhook (patrocinador financia o pote) ############"
E1=$(total)
SALDO_PATRO_ANTES=$("${PSQL[@]}" \
  "SELECT c.saldo_tokens FROM carteira c JOIN patrocinador p ON p.usuario_id = c.usuario_id
    WHERE p.transportadora_slug = 'transportadora-dev';")
echo "saldo do patrocinador antes: $SALDO_PATRO_ANTES"

RASTREIO="F14$(date +%s)"
RESP=$(webhook transportadora-dev "${WEBHOOK_SEGREDO_DEV:-segredo-de-desenvolvimento-local}" \
  "$(corpo_webhook "$RASTREIO")")
echo "webhook: $(echo "$RESP" | jq -c '{desfecho, missaoId, replay}')"
conferir "desfecho" "CONVERTIDA" "$(echo "$RESP" | jq -r '.desfecho')"

M4=$(echo "$RESP" | jq -r '.missaoId')
M4_INFO=$("${PSQL[@]}" "SELECT fonte_pote || '|' || tokens_recompensa || '|' || pote_tokens
                          FROM missao WHERE id = '$M4';")
echo "missão: fonte_pote|recompensa|pote = $M4_INFO"
conferir "fonte do pote" "PATROCINADOR" "$(echo "$M4_INFO" | cut -d'|' -f1)"
conferir "pote cobre a recompensa" "$(echo "$M4_INFO" | cut -d'|' -f2)" "$(echo "$M4_INFO" | cut -d'|' -f3)"
R_ENTREGA=$(echo "$M4_INFO" | cut -d'|' -f2)
conferir "converter não cria nem destrói" "$E1" "$(total)"

echo "aceitar:  $(api POST "/api/v1/missoes/$M4/aceitar"  "$ALICE" | jq -r '.status')"
echo "iniciar:  $(api POST "/api/v1/missoes/$M4/iniciar"  "$ALICE" | jq -r '.status')"
echo "checkin:  $(api POST "/api/v1/missoes/$M4/checkin" "$ALICE" \
  "{\"lat\":$ORIGEM_LAT,\"lon\":$ORIGEM_LON,\"acuraciaM\":8.0,\"mocked\":false}" \
  | jq -r '.status // .type')"

# A transportadora confirma o recebimento pelo destinatário — mesma autenticação HMAC do reporte.
# É o caminho que substituiu o UPDATE manual em `estado_desde`: o criador desta missão é o
# usuário-sistema e nenhum humano pode chamar /confirmar, mas a contraparte comercial pode.
CONF=$(webhook transportadora-dev "${WEBHOOK_SEGREDO_DEV:-segredo-de-desenvolvimento-local}" \
  "{\"codigoRastreio\":\"$RASTREIO\"}" /confirmacao)
echo "confirmação: $(echo "$CONF" | jq -c '{tokensCreditados, replay}')"
conferir "conclusão por confirmação da transportadora" "CONCLUIDA" \
  "$("${PSQL[@]}" "SELECT status FROM missao WHERE id = '$M4';")"

E2=$(total)
SALDO_PATRO_DEPOIS=$("${PSQL[@]}" \
  "SELECT c.saldo_tokens FROM carteira c JOIN patrocinador p ON p.usuario_id = c.usuario_id
    WHERE p.transportadora_slug = 'transportadora-dev';")
echo "saldo do patrocinador depois: $SALDO_PATRO_DEPOIS  (pagou $((SALDO_PATRO_ANTES-SALDO_PATRO_DEPOIS)))"
echo "--> conservação antes=$E1 depois=$E2 Δ=$((E2-E1))  (recompensa: $R_ENTREGA)"
conferir "Δ do ciclo ENTREGA" "0" "$((E2-E1))"
echo "--> reconciliação: $(recon "$ADMIN")"
D_ENTREGA=$((E2-E1))

echo
echo "############ CICLO 5 — ENTREGA sem patrocínio (esperado: 200, sem missão) ############"
# `transportadora-sem-saldo` está integrada em application-dev.yml, então a assinatura é VÁLIDA e a
# requisição atravessa o HmacWebhookFilter. O patrocinador dela é cadastrado agora e NUNCA recebe
# aporte — é assim que a recusa é demonstrada sem zerar carteira por UPDATE, o que corromperia a
# projeção contra o ledger no meio da medição.
S1=$(total)
MISSOES_ANTES=$("${PSQL[@]}" "SELECT COUNT(*) FROM missao;")

CAD=$(api POST /api/v1/admin/patrocinadores "$ADMIN" \
  '{"nome":"Transportadora Sem Saldo","transportadoraSlug":"transportadora-sem-saldo"}')
echo "patrocinador cadastrado: $(echo "$CAD" | jq -c '{transportadoraSlug, ativo}')"
conferir "nasce sem saldo" "0" "$("${PSQL[@]}" \
  "SELECT c.saldo_tokens FROM carteira c JOIN patrocinador p ON p.usuario_id = c.usuario_id
    WHERE p.transportadora_slug = 'transportadora-sem-saldo';")"

# UMA chamada só, capturando corpo e status juntos: o timestamp entra DENTRO do material assinado,
# então recalcular `date` entre a assinatura e o envio produziria 401 intermitente.
BRUTO5=$(webhook_com_status transportadora-sem-saldo \
  "${WEBHOOK_SEGREDO_SEM_SALDO:-segredo-de-desenvolvimento-sem-saldo}" \
  "$(corpo_webhook "F14SEM$(date +%s)")")
CODIGO5=$(printf '%s' "$BRUTO5" | tail -n1)
RESP5=$(printf '%s' "$BRUTO5" | sed '$d')
echo "webhook: $(echo "$RESP5" | jq -c '{desfecho, missaoId, mensagem}')"
conferir "HTTP" "200" "$CODIGO5"
conferir "desfecho" "SEM_PATROCINIO" "$(echo "$RESP5" | jq -r '.desfecho')"
conferir "sem missão no corpo" "null" "$(echo "$RESP5" | jq -r '.missaoId')"
conferir "nenhuma missão criada" "$MISSOES_ANTES" "$("${PSQL[@]}" "SELECT COUNT(*) FROM missao;")"
conferir "nenhum token cunhado" "$S1" "$(total)"
echo "--> reconciliação: $(recon "$ADMIN")"

echo
echo "############ RESUMO ############"
printf 'TRIBO    Δ=%s  recompensa=%s\n' "$D_TRIBO"   "$R_TRIBO"
printf 'COLETA   Δ=%s  recompensa=%s\n' "$D_COLETA"  "$R_COLETA"
printf 'AJUDA    Δ=%s  recompensa=%s\n' "$D_AJUDA"   "$R_AJUDA"
printf 'ENTREGA  Δ=%s  recompensa=%s  (pote pago pelo patrocinador)\n' "$D_ENTREGA" "$R_ENTREGA"
echo "conservação: baseline=$BASELINE  final=$(total)"
echo "reconciliação final: $(recon "$ADMIN")"
echo "lançamentos por motivo:"
"${PSQL[@]}" "SELECT motivo || ' = ' || count(*) FROM lancamento GROUP BY motivo ORDER BY 1;"
echo "missões por fonte_pote:"
"${PSQL[@]}" "SELECT fonte_pote || ' = ' || count(*) FROM missao GROUP BY fonte_pote ORDER BY 1;"

echo
if [[ $falhas -eq 0 ]]; then
  echo "Todas as conferências passaram."
else
  echo "$falhas conferência(s) FALHARAM."
fi
exit $falhas
