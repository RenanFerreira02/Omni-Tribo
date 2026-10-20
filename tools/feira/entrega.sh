#!/usr/bin/env bash
# O laço do estande: a transportadora, numa tecla.
#
#   Enter  → "uma entrega acabou de falhar": reporta pelo webhook no ponto do estande, com insumos
#            que dão risco ALTO (3 tentativas, 19 h, condomínio) — o detalhe da missão mostra o
#            aviso acionável e o multiplicador. O alerta chega na aba Avisos em ~10 s (outbox).
#   ...    → espera o check-in do visitante e, quando a missão chega a AGUARDANDO_CONFIRMACAO,
#            confirma o recebimento como a transportadora faria. O executor é creditado na hora.
#   q      → sai.
#
# Por que existe: os dois atos da transportadora não têm botão no app, por desenho — o criador da
# missão de retirada é o usuário-sistema, e nenhum humano confirma, nem ADMIN (ADR 0026).
#
# O ponto vem de tools/demo/.env.ponto (gerado por ponto-aqui.sh no estande); sem ele, o ponto do
# Arca semeado na V908.
#
# Uso:  bash tools/feira/entrega.sh      (com a API de pé em dev,feira)
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../lib/transportadora.sh"
exigir_programas
exigir_api

ESTADO="$RAIZ_REPO/tools/demo/.env.ponto"
# shellcheck disable=SC1090
[ -f "$ESTADO" ] && . "$ESTADO"
PONTO_CUSTODIA="${PONTO_CUSTODIA:-cccccccc-0000-0000-0000-000000000982}"
EXECUTOR="${EXECUTOR:-renan@omnitribo.dev}"
ESPERA_MAX_S="${ESPERA_MAX_S:-900}"

ORIGEM=$(psql_ "SELECT round(ST_Y(ponto::geometry)::numeric, 5) || ',' || round(ST_X(ponto::geometry)::numeric, 5)
                  FROM ponto_custodia WHERE id = '$PONTO_CUSTODIA' AND ativo;")
if [ -z "$ORIGEM" ]; then
  echo "${vermelho}O ponto $PONTO_CUSTODIA não existe neste banco.${normal}"
  echo "Rode 'bash tools/demo/ponto-aqui.sh <LAT> <LON>' no estande, ou 'make feira'."
  exit 1
fi
APELIDO=$(psql_ "SELECT apelido FROM ponto_custodia WHERE id = '$PONTO_CUSTODIA';")
DEST_LAT=$(printf '%s' "$ORIGEM" | cut -d, -f1); DEST_LON=$(printf '%s' "$ORIGEM" | cut -d, -f2)

TOKEN=$(login "$EXECUTOR")
[ ${#TOKEN} -gt 20 ] || { echo "${vermelho}Login de $EXECUTOR falhou.${normal}"; exit 1; }

ITENS=("2 caixas de porcelanato 60x60|24.50|58.00" "Lata de tinta acrílica 18 L|22.00|20.00"
       "Kit de torneira gourmet|3.10|8.00" "Furadeira de impacto 650 W|2.40|6.00"
       "Luminária pendente industrial|4.80|30.00")

echo
echo "${negrito}Estande Omni-Tribo${normal} — ponto: $APELIDO ($ORIGEM)"
echo "${cinza}Executor de referência: $EXECUTOR. O check-in é feito no APP, com o GPS do celular.${normal}"

rodada=0
while true; do
  echo
  read -r -p "${negrito}[Enter]${normal} uma entrega falhou   ${negrito}[q]${normal} sair  › " tecla || exit 0
  [ "$tecla" = "q" ] && exit 0

  item="${ITENS[$((rodada % ${#ITENS[@]}))]}"; rodada=$((rodada + 1))
  descricao="${item%%|*}"; resto="${item#*|}"; peso="${resto%%|*}"; volume="${resto#*|}"
  rastreio="BRNEXT$(date +%s)"
  corpo=$(cat <<JSON
{"codigoRastreio":"$rastreio","motivo":"Destinatário ausente após 3 tentativas de entrega",
 "pontoCustodiaId":"$PONTO_CUSTODIA","descricaoDoItem":"$descricao",
 "pesoKg":$peso,"volumeL":$volume,"valorOfertadoBrl":32.00,
 "destinoLat":$DEST_LAT,"destinoLon":$DEST_LON,
 "cep":"05317020","logradouro":"Avenida Manuel Bandeira","bairro":"Vila Leopoldina",
 "cidade":"São Paulo","uf":"SP",
 "janelaHoraInicio":19,"tipoEndereco":"CONDOMINIO","tentativasAnteriores":3}
JSON
)
  resposta=$(postar_webhook "$corpo")
  status=$(printf '%s' "$resposta" | tail -n1); json=$(printf '%s' "$resposta" | sed '$d')
  missao=$(printf '%s' "$json" | jq -r '.missaoId // empty')
  if [ "$status" != "200" ] || [ -z "$missao" ]; then
    echo "${vermelho}  sem missão${normal}  HTTP $status  desfecho=$(printf '%s' "$json" | jq -r '.desfecho // "?"')"
    echo "${cinza}  RECUSADA = ponto lotado · SEM_PATROCINIO = patrocinador sem saldo → make feira${normal}"
    continue
  fi
  detalhe=$(curl -s "$API/api/v1/missoes/$missao" -H "Authorization: Bearer $TOKEN")
  echo "${verde}  ✓ missão no ar${normal}  $descricao · $(printf '%s' "$detalhe" \
    | jq -r '"\(.xpRecompensa) XP + \(.tokensRecompensa) tokens · risco \(.multiplicadorRisco)×"')"
  echo "${cinza}    o aviso chega em ~10 s na aba Avisos. Esperando o check-in…  (Ctrl-C cancela a espera)${normal}"

  decorrido=0; estado=""
  trap 'estado=INTERROMPIDO' INT
  while [ "$decorrido" -lt "$ESPERA_MAX_S" ] && [ "$estado" != "INTERROMPIDO" ]; do
    estado=$(curl -s "$API/api/v1/missoes/$missao" -H "Authorization: Bearer $TOKEN" | jq -r '.status // "?"')
    case "$estado" in AGUARDANDO_CONFIRMACAO|CANCELADA|EXPIRADA|CONCLUIDA) break ;; esac
    printf '\r%s    %-24s %4ss%s' "$cinza" "$estado" "$decorrido" "$normal"
    sleep 3; decorrido=$((decorrido + 3))
  done
  trap - INT
  printf '\r%-60s\r' ' '

  if [ "$estado" != "AGUARDANDO_CONFIRMACAO" ]; then
    echo "${amarelo}  a missão ficou em ${estado:-?}${normal} — nada a confirmar. Ela segue no radar."
    continue
  fi
  resposta=$(postar_webhook "{\"codigoRastreio\":\"$rastreio\"}" "$URL_CONFIRMACAO")
  status=$(printf '%s' "$resposta" | tail -n1); json=$(printf '%s' "$resposta" | sed '$d')
  if [ "$status" = "200" ]; then
    echo "${verde}${negrito}  ✓ transportadora confirmou o recebimento: +$(printf '%s' "$json" | jq -r .tokensCreditados) tokens${normal}"
    echo "${cinza}    puxe a Carteira para atualizar. O telão já mostra a entrega concluída.${normal}"
  else
    echo "${vermelho}  confirmação falhou${normal}  HTTP $status $(printf '%s' "$json" | head -c 160)"
  fi
done
