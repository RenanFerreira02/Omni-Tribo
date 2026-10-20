#!/usr/bin/env bash
# Povoa a Vila Leopoldina em volta do estande, PELA API, para o mapa parecer um bairro vivo.
#
# Por que pela API e não por seed: a recompensa tem de sair da CalculadoraDeRecompensa e o pote de
# um financiamento de verdade. Missão escrita à mão em SQL faria a prévia dizer um número e a
# lista outro, e o pote nasceria de lugar nenhum. O seed V908 traz só o estático (tribo, pontos,
# vizinhos com saldo, parceiros).
#
# O que cria:
#   - 4 entregas falidas pelo webhook (Leroy Merlin Villa Lobos e uma portaria), com insumos de
#     risco variados — o mapa mostra as faixas ALTO/MÉDIO/BAIXO e o multiplicador;
#   - um mutirão TRIBO e uma COLETA, criados por um vizinho e financiados por OUTRO (ADR 0037);
#   - uma AJUDA só XP e uma ENTREGA de vizinho financiada.
#
# Idempotente: se já povoou (há missões criadas pelos vizinhos do seed), sai sem duplicar. Para
# recomeçar do zero: make feira.
#
# Uso:  bash tools/feira/povoar.sh       (com a API de pé em dev,feira)
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../lib/transportadora.sh"
exigir_programas
exigir_api

TRIBO=aaaaaaaa-0000-0000-0000-000000000908
LEROY=cccccccc-0000-0000-0000-000000000981
PORTARIA=cccccccc-0000-0000-0000-000000000983
VIZINHOS="'bbbbbbbb-0000-0000-0000-000000000981','bbbbbbbb-0000-0000-0000-000000000982','bbbbbbbb-0000-0000-0000-000000000983'"

ja=$(psql_ "SELECT count(*) FROM missao WHERE criador_id IN ($VIZINHOS);")
if [ "${ja:-0}" -gt 0 ]; then
  echo "${amarelo}Já povoado${normal} ($ja missões dos vizinhos). Nada a fazer — para recomeçar: make feira."
  exit 0
fi

PAULA=$(login paula@omnitribo.dev); SERGIO=$(login sergio@omnitribo.dev)
BEATRIZ=$(login beatriz@omnitribo.dev)
for t in "$PAULA" "$SERGIO" "$BEATRIZ"; do
  [ ${#t} -gt 20 ] || { echo "${vermelho}Login de vizinho falhou${normal} — o seed V908 foi aplicado?"; exit 1; }
done

INICIO=$(date -u +%Y-%m-%dT%H:%M:%SZ)
FIM=$(date -u -d '+3 day' +%Y-%m-%dT%H:%M:%SZ)
execucao=$(date +%s)

# ── 1. Entregas falidas (o caminho da tese) ─────────────────────────────────────────────────
entrega_falida() { # $1=ponto $2=item $3=peso $4=volume $5=tentativas $6=hora $7=tipoEndereco
                   # $8=destLat $9=destLon $10=cep $11=logradouro
  local rastreio="BRFEIRA${execucao}${RANDOM}"
  local corpo
  corpo=$(cat <<JSON
{"codigoRastreio":"$rastreio","motivo":"Destinatário ausente na tentativa de entrega",
 "pontoCustodiaId":"$1","descricaoDoItem":"$2",
 "pesoKg":$3,"volumeL":$4,"valorOfertadoBrl":30.00,
 "destinoLat":$8,"destinoLon":$9,
 "cep":"${10}","logradouro":"${11}","bairro":"Vila Leopoldina","cidade":"São Paulo","uf":"SP",
 "janelaHoraInicio":$6,"tipoEndereco":"$7","tentativasAnteriores":$5}
JSON
)
  local resposta status json
  resposta=$(postar_webhook "$corpo")
  status=$(printf '%s' "$resposta" | tail -n1); json=$(printf '%s' "$resposta" | sed '$d')
  if [ "$status" = "200" ] && [ "$(printf '%s' "$json" | jq -r .desfecho)" = "CONVERTIDA" ]; then
    printf '  %s✓%s  entrega falida  %-34s %s\n' "$verde" "$normal" "$2" "$(printf '%s' "$json" | jq -r .missaoId)"
  else
    printf '  %s✗%s  entrega falida  %-34s HTTP %s %s\n' "$vermelho" "$normal" "$2" "$status" "$(printf '%s' "$json" | head -c 160)"
  fi
}

echo "${negrito}Entregas falidas (webhook da transportadora)${normal}"
entrega_falida "$LEROY" "2 caixas de porcelanato 60x60" 24.50 58.00 3 19 CONDOMINIO -23.5309775 -46.7292628 05302001 "Rua Schilling"
entrega_falida "$LEROY" "Lata de tinta acrílica 18 L" 22.00 20.00 2 14 CONDOMINIO -23.5342365 -46.7245745 05303000 "Rua Carlos Weber"
entrega_falida "$PORTARIA" "Furadeira de impacto 650 W" 2.40 6.00 0 10 RESIDENCIAL -23.5396563 -46.7298747 05319000 "Avenida Doutor Gastão Vidigal"
entrega_falida "$LEROY" "Kit de torneira gourmet" 3.10 8.00 0 14 CONDOMINIO -23.5344743 -46.7342782 05318000 "Avenida Doutor Gastão Vidigal"

# ── 2. Missões entre vizinhos ───────────────────────────────────────────────────────────────
# $1=token do criador $2=categoria $3=título $4=descrição $5=insumos (JSON parcial) $6=lat $7=lon
# $8=cep $9=logradouro $10=comToken(true|false) $11=token do financiador (vazio = não financia)
missao_vizinho() {
  local corpo resposta id tokens fonte
  corpo=$(cat <<JSON
{"categoria":"$2","titulo":"$3","descricao":"$4","valorBrl":0.00,$5,
 "origemLat":$6,"origemLon":$7,"cep":"$8","logradouro":"$9","bairro":"Vila Leopoldina",
 "cidade":"São Paulo","uf":"SP","raioCheckinM":150,"recompensaEmToken":${10},
 "janelaInicio":"$INICIO","janelaFim":"$FIM"}
JSON
)
  resposta=$(curl -s -X POST "$API/api/v1/missoes" -H "Authorization: Bearer $1" \
    -H 'Content-Type: application/json' -d "$corpo")
  id=$(printf '%s' "$resposta" | jq -r '.id // empty')
  if [ -z "$id" ]; then
    printf '  %s✗%s  %-8s %-44s %s\n' "$vermelho" "$normal" "$2" "$3" "$(printf '%s' "$resposta" | head -c 160)"
    return
  fi
  tokens=$(printf '%s' "$resposta" | jq -r .tokensRecompensa)
  if [ -n "${11}" ] && [ "$tokens" -gt 0 ]; then
    curl -s -o /dev/null -X POST "$API/api/v1/tribos/$TRIBO/financiamentos" \
      -H "Authorization: Bearer ${11}" -H "Idempotency-Key: feira-fin-$id" \
      -H 'Content-Type: application/json' -d "{\"missaoId\":\"$id\",\"tokens\":$tokens}"
  fi
  fonte=$(curl -s -X POST "$API/api/v1/missoes/$id/publicar" -H "Authorization: Bearer $1" \
    | jq -r '"\(.status) · \(.fontePote) · \(.xpRecompensa) XP + \(.tokensRecompensa) tokens"')
  printf '  %s✓%s  %-8s %-44s %s\n' "$verde" "$normal" "$2" "$3" "$fonte"
}

echo
echo "${negrito}Missões entre vizinhos${normal}"
missao_vizinho "$PAULA" TRIBO "Mutirão de plantio no Parque Villa-Lobos" \
  "Plantio de mudas nativas no canteiro da entrada da Fonseca Rodrigues. Luvas por nossa conta." \
  '"complexidade":"MEDIA"' -23.5441098 -46.7241046 05461010 "Avenida Professor Fonseca Rodrigues" \
  true "$SERGIO"
missao_vizinho "$SERGIO" COLETA "Coleta de recicláveis da feira de quarta" \
  "Recolher papelão e plástico separados pelos feirantes e levar ao ponto de coleta." \
  '"pesoKg":15.00,"volumeL":120.00' -23.5396563 -46.7298747 05319000 "Avenida Doutor Gastão Vidigal" \
  true "$PAULA"
missao_vizinho "$BEATRIZ" AJUDA "Montar o armário da dona Cida" \
  "Ela tem 82 anos e o armário chegou desmontado. Duas mãos a mais resolvem em uma hora." \
  '"complexidade":"LEVE"' -23.5309775 -46.7292628 05302001 "Rua Schilling" \
  false ""
missao_vizinho "$PAULA" ENTREGA "Levar a escada emprestada até a Rua Carlos Weber" \
  "Escada de alumínio de 6 degraus, devolvendo para o vizinho do 102." \
  '"pesoKg":6.00,"volumeL":40.00' -23.5342365 -46.7245745 05303000 "Rua Carlos Weber" \
  true "$BEATRIZ"

echo
total=$(psql_ "SELECT count(*) FROM missao m JOIN ponto_custodia p ON true
                WHERE p.id = 'cccccccc-0000-0000-0000-000000000982' AND m.status = 'ABERTA'
                  AND ST_DWithin(m.origem, p.ponto, 4000);")
echo "${negrito}$total missões abertas${normal} a até 4 km do estande. Abra o radar no celular."
