#!/usr/bin/env bash
# Arma o vídeo-pitch de 5 minutos (docs/ROTEIRO-PITCH-5MIN.md).
#
# POR QUE ISTO EXISTE. Dois atos do fio condutor são da TRANSPORTADORA, não do app: o reporte da
# entrega falida e a confirmação de recebimento que credita o executor. Nenhum dos dois tem gatilho
# na interface, e isso é desenho, não lacuna — o criador da missão de retirada é o usuário-sistema e
# `AtorEsperado.CRIADOR` compara IDENTIDADE, então nenhum humano confirma, nem ADMIN (ADR 0026).
# Sem este script o pitch precisaria de um terminal em quadro, que é o que a Atividade 4 proíbe.
#
# Ele roda FORA DE QUADRO, antes e durante a gravação, em duas fases:
#
#   fase 1  reporta a entrega falida por HMAC, imprime o id da missão e PARA.
#   fase 2  observa a missão e, no instante em que ela entra em AGUARDANDO_CONFIRMACAO — isto é,
#           quando o check-in acontece na tela —, posta a confirmação.
#
# As ~15 linhas de `assinar` e `login` são DUPLICADAS de tools/carrier-mock/enviar.sh de propósito.
# Extrair uma biblioteca comum mexeria no script que a banca vê funcionando, e trocar risco de
# regressão por elegância na véspera da entrega é mau negócio. Se um dia os dois divergirem, o
# sintoma é 401 aqui e 200 lá.
#
# Uso:
#   bash tools/demo/pitch-armar.sh              # as duas fases, com a espera entre elas
#   bash tools/demo/pitch-armar.sh --so-fase-1  # só reporta (para ensaiar a tela sem gastar vaga)
#
# Variáveis (mesmo contrato de tools/carrier-mock/enviar.sh):
#   API EXECUTOR SENHA_SEED TRANSPORTADORA SEGREDO PONTO_CUSTODIA
#   CHECKIN_LAT CHECKIN_LON DESTINO_LAT DESTINO_LON
#   ESPERA_MAX_S  quanto tempo a fase 2 aguarda o check-in (default 600 s)
set -euo pipefail

API="${API:-http://localhost:8080}"
URL="$API/api/v1/webhooks/transportadora"
URL_CONFIRMACAO="$URL/confirmacao"
EXECUTOR="${EXECUTOR:-renan@omnitribo.dev}"
SENHA_SEED="${SENHA_SEED:-Senha@123}"
TRANSPORTADORA="${TRANSPORTADORA:-transportadora-dev}"
SEGREDO="${SEGREDO:-segredo-de-desenvolvimento-local}"

# LOCKER Cidade Líder (V903), a 170 m do ponto de referência do seed: é o ponto que aparece
# primeiro na busca por raio, e a tribo dele é a do executor — sem isso o fan-out não o alcança.
PONTO_CUSTODIA="${PONTO_CUSTODIA:-cccccccc-0000-0000-0000-000000000902}"
CHECKIN_LAT="${CHECKIN_LAT:--23.55650}"
CHECKIN_LON="${CHECKIN_LON:--46.46850}"
DESTINO_LAT="${DESTINO_LAT:--23.55737}"
DESTINO_LON="${DESTINO_LON:--46.46987}"

# Contexto de risco. Os três campos são OPCIONAIS no webhook e vão preenchidos aqui porque o bloco
# de 45 s do pitch para na tela para mostrar a faixa de risco e a linha do multiplicador — e o
# multiplicador só APARECE acima de 1,00. Com tentativas=0 e endereço residencial (a imputação
# conservadora do webhook), a probabilidade fica baixa e a tela não tem o que mostrar.
# Isto não fabrica risco: descreve o caso que a narração conta em voz alta — três tentativas, 19h,
# portaria de condomínio. A faixa que sair é a que o modelo calcular, e o clima é consultado ao vivo.
TENTATIVAS="${TENTATIVAS:-3}"
JANELA_HORA="${JANELA_HORA:-19}"
TIPO_ENDERECO="${TIPO_ENDERECO:-CONDOMINIO}"

ESPERA_MAX_S="${ESPERA_MAX_S:-600}"

for programa in curl openssl jq; do
  command -v "$programa" >/dev/null || { echo "Falta $programa no PATH."; exit 1; }
done

verde=$'\033[32m'; vermelho=$'\033[31m'; cinza=$'\033[90m'; negrito=$'\033[1m'; normal=$'\033[0m'

assinar() { # $1=timestamp  $2=corpo
  printf '%s.%s' "$1" "$2" \
    | openssl dgst -sha256 -hmac "$SEGREDO" -hex \
    | sed 's/^.*= //'
}

login() {
  curl -s -X POST "$API/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"senha\":\"$SENHA_SEED\"}" | jq -r '.accessToken'
}

postar_webhook() { # $1=corpo  $2=url
  local ts assinatura
  ts=$(date +%s)
  assinatura=$(assinar "$ts" "$1")
  curl -s -w '\n%{http_code}' -X POST "$2" \
    -H 'Content-Type: application/json' \
    -H "X-Transportadora: $TRANSPORTADORA" \
    -H "X-Timestamp: $ts" \
    -H "X-Assinatura: $assinatura" \
    --data-binary "$1"
}

# Endereço em Cidade Líder, onde o executor e os benefícios do seed vivem. O CEP não é decorativo:
# a faixa de 3 dígitos é uma das 14 características do modelo de risco.
corpo_reporte() { # $1=rastreio
  cat <<JSON
{"codigoRastreio":"$1","motivo":"Destinatário ausente após 3 tentativas de entrega",
 "pontoCustodiaId":"$PONTO_CUSTODIA","descricaoDoItem":"1 caixa de piso laminado 1,5 m²",
 "pesoKg":18.40,"volumeL":42.00,"valorOfertadoBrl":32.00,
 "destinoLat":$DESTINO_LAT,"destinoLon":$DESTINO_LON,
 "cep":"08280460","logradouro":"Rua Antônio Maria Bessa","bairro":"Cidade Líder",
 "cidade":"São Paulo","uf":"SP",
 "janelaHoraInicio":$JANELA_HORA,"tipoEndereco":"$TIPO_ENDERECO",
 "tentativasAnteriores":$TENTATIVAS}
JSON
}

# ── Fase 1 ────────────────────────────────────────────────────────────────────────────
RASTREIO="BRPITCH$(date +%s)"
echo
echo "${negrito}[1/2] reportando a entrega falida${normal}  rastreio $RASTREIO"

corpo="$(corpo_reporte "$RASTREIO")"
resposta="$(postar_webhook "$corpo" "$URL")"
status="$(printf '%s' "$resposta" | tail -n1)"
json="$(printf '%s' "$resposta" | sed '$d')"

if [ "$status" != "200" ]; then
  echo "${vermelho}  FALHOU${normal}  HTTP $status"
  printf '%s  %s%s\n' "$cinza" "$(printf '%s' "$json" | head -c 300)" "$normal"
  echo "  401 aqui costuma ser SEGREDO diferente de app.webhooks.segredos.$TRANSPORTADORA."
  exit 1
fi

MISSAO="$(printf '%s' "$json" | jq -r '.missaoId // empty')"
DESFECHO="$(printf '%s' "$json" | jq -r '.desfecho // "?"')"

if [ -z "$MISSAO" ] || [ "$MISSAO" = "null" ]; then
  # RECUSADA (ponto sem vaga) e SEM_PATROCINIO também devolvem 200 — é desfecho de negócio, não
  # erro. Para o pitch, porém, não há missão a mostrar: é melhor parar aqui com a causa na tela.
  echo "${vermelho}  SEM MISSÃO${normal}  desfecho=$DESFECHO"
  printf '%s  %s%s\n' "$cinza" "$(printf '%s' "$json" | head -c 300)" "$normal"
  echo "  RECUSADA → o ponto está lotado (ensaio gastou as vagas): rode 'make demo'."
  echo "  SEM_PATROCINIO → patrocinador sem saldo para o pote: rode 'make demo'."
  exit 1
fi

echo "${verde}  OK${normal}     desfecho=$DESFECHO  missão ${negrito}$MISSAO${normal}"
echo "${cinza}         o alerta cai na caixa de entrada na próxima varredura da outbox (PT10S)${normal}"
echo "${cinza}         espere ~15 s e comece a gravar na aba Avisos${normal}"

if [ "${1:-}" = "--so-fase-1" ]; then
  echo
  echo "Fase 2 não vai rodar (--so-fase-1): esta missão fica sem confirmação e o executor não é"
  echo "creditado. Reexecutar o script cria OUTRA missão, com outro rastreio — não retoma esta."
  echo "Para ensaiar o crédito, rode sem a flag; para limpar o ensaio, 'make demo'."
  exit 0
fi

# ── Fase 2 ────────────────────────────────────────────────────────────────────────────
# Espera o check-in, que acontece NA TELA. Só então confirma: postar antes devolve 409, porque a
# confirmação exige que a missão já esteja em AGUARDANDO_CONFIRMACAO.
TOKEN="$(login "$EXECUTOR")"
if [ ${#TOKEN} -lt 20 ]; then
  echo "${vermelho}  FALHOU${normal}  login de $EXECUTOR não devolveu token"
  exit 1
fi

echo
echo "${negrito}[2/2] aguardando o check-in do executor${normal}  (até ${ESPERA_MAX_S}s)"

decorrido=0
status_missao=""
while [ "$decorrido" -lt "$ESPERA_MAX_S" ]; do
  status_missao="$(curl -s "$API/api/v1/missoes/$MISSAO" \
    -H "Authorization: Bearer $TOKEN" | jq -r '.status // "?"')"
  [ "$status_missao" = "AGUARDANDO_CONFIRMACAO" ] && break
  printf '\r%s         %-24s %ss%s' "$cinza" "$status_missao" "$decorrido" "$normal"
  sleep 3
  decorrido=$((decorrido + 3))
done
printf '\r%-60s\r' ' '

if [ "$status_missao" != "AGUARDANDO_CONFIRMACAO" ]; then
  echo "${vermelho}  TEMPO ESGOTADO${normal}  a missão parou em ${status_missao:-?}"
  echo "  A fase 2 só dispara depois do check-in. Se a tela reprovou o check-in por distância,"
  echo "  CHECKIN_LAT/LON estão longe da origem — o raio da missão é de 200 m."
  exit 1
fi

cc="$(printf '{"codigoRastreio":"%s"}' "$RASTREIO")"
resposta="$(postar_webhook "$cc" "$URL_CONFIRMACAO")"
status="$(printf '%s' "$resposta" | tail -n1)"
json="$(printf '%s' "$resposta" | sed '$d')"

if [ "$status" = "200" ]; then
  creditados="$(printf '%s' "$json" | jq -r '.tokensCreditados // "?"')"
  echo "${verde}  OK${normal}     confirmação enviada  HTTP 200  creditados: ${negrito}$creditados${normal}"
  echo "${cinza}         puxe a carteira para atualizar — o saldo subiu $creditados${normal}"
else
  echo "${vermelho}  FALHOU${normal}  confirmação HTTP $status"
  printf '%s  %s%s\n' "$cinza" "$(printf '%s' "$json" | head -c 300)" "$normal"
  exit 1
fi
