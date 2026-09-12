#!/usr/bin/env bash
#
# Roda o teste de carga (tools/carga/carga.js) e resume a saída em percentis por patamar.
#
# PRÉ-REQUISITOS, e nenhum deles é ajustado por este script:
#   1. make up                      — o Postgres+PostGIS do compose
#   2. cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
#   3. k6 no PATH                   — https://github.com/grafana/k6/releases
#
# NÃO passe -Dspring.datasource.hikari.maximum-pool-size nem -Dapp.rate-limit.* ao subir a API. A
# medição vale porque é da configuração que o projeto entrega, não de uma afinada para o teste.
#
# O banco é MUTADO pela execução: o cenário 3 cria missões e gasta o saldo do patrocinador, e o 2
# move token entre carteiras. Rode `make reset` antes, para partir de um estado conhecido, e depois,
# para não deixar o dev com resíduo de carga.
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
API="${API:-http://localhost:8080}"
SAIDA="${SAIDA:-$RAIZ/tools/carga/saida}"

mkdir -p "$SAIDA"

echo "Conferindo se a API responde em $API ..."
if ! curl -fsS --max-time 5 "$API/api/v1/ping" > /dev/null; then
  echo "ERRO: $API/api/v1/ping não respondeu." >&2
  echo "Suba o backend: cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev" >&2
  exit 1
fi

# `--out csv` porque o resumo do k6 agrega a execução INTEIRA, e o que se quer saber é onde a curva
# vira. O resumo por patamar sai do resumir.py, sobre a série temporal.
k6 run \
  --out "csv=$SAIDA/bruto.csv" \
  --summary-export "$SAIDA/resumo.json" \
  -e API="$API" \
  "$RAIZ/tools/carga/carga.js" 2>&1 | tee "$SAIDA/k6.log"

# SEGUNDA invocação, e não um quarto cenário na primeira. O access token do `setup()` vale 900 s;
# um cenário começando aos 16m30s recebe 401 em toda requisição — medido, com 1.180 delas. Um
# processo próprio dá um `setup()` fresco sem tocar no instante de nenhum dos três originais.
#
# Roda DEPOIS, contra o banco já mutado pelo cenário 3 (57 conversões) e com a JVM quente. É estado
# diferente do da primeira invocação, e a comparação antes-e-depois só vale porque as duas execuções
# repetem a mesma sequência.
echo
echo "Cenário de leituras, em processo próprio (setup fresco) ..."
k6 run \
  --out "csv=$SAIDA/bruto-leituras.csv" \
  --summary-export "$SAIDA/resumo-leituras.json" \
  -e API="$API" -e CENARIO=leituras \
  "$RAIZ/tools/carga/carga.js" 2>&1 | tee "$SAIDA/k6-leituras.log"

echo
echo "Resumindo por patamar de 30 s ..."
python3 "$RAIZ/tools/carga/resumir.py" "$SAIDA/bruto.csv" | tee "$SAIDA/por-patamar.md"
python3 "$RAIZ/tools/carga/resumir.py" "$SAIDA/bruto-leituras.csv" | tee "$SAIDA/por-patamar-leituras.md"

echo
echo "Saída em $SAIDA/"
