#!/usr/bin/env bash
# Cria (ou remaneja) um ponto de custódia NAS SUAS COORDENADAS, para gravar o vídeo-pitch de onde
# você está.
#
# POR QUE ISTO EXISTE. A origem da missão de retirada é a coordenada do PONTO DE CUSTÓDIA, e o
# check-in exige o aparelho a menos de `app.missoes.entrega-falida.raio-checkin-m` (200 m) dela. Os
# pontos do seed vivem na zona leste de São Paulo, então gravar de qualquer outro lugar reprova o
# check-in com "você está a N m da origem" — e não há variável de script que mude isso, porque quem
# manda a posição é o GPS do aparelho.
#
# App de mock de GPS NÃO é alternativa: `AvaliacaoAntifraude.avaliar` rejeita antes de tudo quando
# `mocked` é true, com motivo LOCALIZACAO_SIMULADA.
#
# AS COORDENADAS NÃO ENTRAM NO GIT. Elas são argumento deste script e vivem apenas no seu banco de
# dev. É por isso que isto não é um seed da faixa 900: seed é arquivo versionado, e endereço de quem
# grava não é fixture pública. O preço é que `make reset` apaga o ponto — rode isto de novo depois.
#
# Uso:
#   bash tools/demo/ponto-aqui.sh <LAT> <LON> ["apelido do ponto"]
#
# Onde pegar LAT/LON: no Google Maps, clique com o botão direito no local e o primeiro item do menu
# é "lat, lon" — copie e cole. Prefira um COMÉRCIO ou uma esquina a menos de 200 m de onde você vai
# gravar, em vez da sua porta: o modelo do produto é custódia comercial (ADR 0020), e o ponto fica
# visível no app como "onde a encomenda está".
set -euo pipefail

# Tribo Cidade Líder — a mesma de renan@omnitribo.dev, o executor do roteiro.
#
# A escolha não é decorativa e é o que faz o alerta do bloco 1:00 continuar chegando: o fan-out
# procura tribos por DISTÂNCIA MÍNIMA A QUALQUER PONTO DA TRIBO (SQL_TRIBOS_NO_RAIO, ADR 0020), não
# até um centro. Como este ponto passa a ser um ponto da tribo, a distância dele até si mesmo é 0 m
# e a Cidade Líder entra no raio de `app.notificacoes.raio-alerta-metros` de onde quer que você
# esteja. Pôr o ponto em tribo nenhuma (`tribo_id` nulo) tiraria a missão do fan-out por completo.
TRIBO="${TRIBO:-aaaaaaaa-0000-0000-0000-000000000901}"

# Fixos para que reexecutar REMANEJE o mesmo ponto em vez de acumular pontos órfãos a cada ensaio.
# `codigo` tem UNIQUE, então sem o upsert a segunda execução morreria em violação de constraint.
PONTO_ID="${PONTO_ID:-cccccccc-0000-0000-0000-000000000907}"
CODIGO="${CODIGO:-PT-AQUI-001}"

LAT="${1:-}"
LON="${2:-}"
APELIDO="${3:-Ponto de gravação}"

if [ -z "$LAT" ] || [ -z "$LON" ]; then
  echo "Uso: bash tools/demo/ponto-aqui.sh <LAT> <LON> [\"apelido\"]"
  echo "Ex.:  bash tools/demo/ponto-aqui.sh -23.55650 -46.46850 \"Mercado da esquina\""
  exit 1
fi

# Validação de faixa. Ela pega o dedo escorregado (lat 100), mas NÃO pega a troca de lat por lon em
# São Paulo: −46,6 é uma latitude perfeitamente legal, e o ponto iria para o Atlântico Sul sem um
# aviso. Quem pega esse caso é a medição de "distância até o ponto mais próximo", abaixo.
if ! printf '%s' "$LAT" | grep -qE '^-?[0-9]+(\.[0-9]+)?$' \
   || ! printf '%s' "$LON" | grep -qE '^-?[0-9]+(\.[0-9]+)?$'; then
  echo "LAT e LON precisam ser números decimais. Recebi LAT='$LAT' LON='$LON'."
  exit 1
fi
# A comparação vai por `awk` com LC_ALL=C, e não por `printf` do shell: neste ambiente o locale é
# pt_BR, onde o separador decimal é a vírgula, e `printf '%.0f' -23.5450` falha com "número
# inválido" — a validação abortava com ruído no terminal enquanto o INSERT seguia em frente.
fora_da_faixa() { # $1=valor  $2=limite
  LC_ALL=C awk -v v="$1" -v lim="$2" 'BEGIN { exit (v > lim || v < -lim) ? 0 : 1 }'
}
if fora_da_faixa "$LAT" 90; then
  echo "LAT=$LAT está fora de [-90, 90]. Você inverteu latitude e longitude?"
  exit 1
fi
if fora_da_faixa "$LON" 180; then
  echo "LON=$LON está fora de [-180, 180]."
  exit 1
fi

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE="$RAIZ/demo/compose.sh"
export OMNITRIBO_COMPOSE_SILENCIOSO=1

psql_() { bash "$COMPOSE" exec -T db psql -U omnitribo -d omnitribo -tAc "$1"; }

verde=$'\033[32m'; vermelho=$'\033[31m'; cinza=$'\033[90m'; negrito=$'\033[1m'; normal=$'\033[0m'

if ! psql_ 'SELECT 1' >/dev/null 2>&1; then
  echo "${vermelho}Banco não respondeu.${normal} Suba com 'make up' e tente de novo."
  exit 1
fi

# O banco responder NÃO significa que o schema existe, e a diferença é a armadilha deste script.
# `make demo` e `make reset` recriam o volume, e o Flyway migra no BOOT DO BACKEND — nenhum dos dois
# alvos sobe o backend, de propósito. Ou seja: entre o `make demo` e a primeira subida do backend, o
# banco tem só as tabelas de sistema do PostGIS, e o INSERT abaixo devolvia
# `ERROR: relation "ponto_custodia" does not exist` — mensagem que não diz em lugar nenhum que o que
# falta é subir o backend uma vez.
if ! psql_ "SELECT to_regclass('public.ponto_custodia') IS NOT NULL;" 2>/dev/null | grep -q '^t$'; then
  echo
  echo "${vermelho}O banco está sem schema.${normal}  (só as tabelas de sistema do PostGIS)"
  echo
  echo "Quem cria o schema é o Flyway, e ele roda no BOOT DO BACKEND — 'make demo' e 'make reset'"
  echo "recriam o volume e não sobem backend nenhum, então depois deles o banco nasce vazio."
  echo
  echo "Suba o backend uma vez e deixe rodando:"
  echo "  cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev"
  echo
  echo "Quando ele disser 'Started ApiApplication', rode este script de novo."
  exit 1
fi

# Capacidade folgada de propósito: cada ensaio ocupa uma vaga e só a conclusão dá baixa. Com
# capacidade baixa, o terceiro ensaio do dia devolveria RECUSADA (ponto lotado) e o roteiro pararia
# por um motivo que não tem nada a ver com o que se está ensaiando.
psql_ "INSERT INTO ponto_custodia (id, codigo, tipo, apelido, ponto, tribo_id, capacidade, ocupacao, ativo)
       VALUES ('$PONTO_ID', '$CODIGO', 'LOJA', \$\$$APELIDO\$\$,
               ST_SetSRID(ST_MakePoint($LON, $LAT), 4326)::geography,
               '$TRIBO', 99, 0, TRUE)
       ON CONFLICT (id) DO UPDATE
          SET ponto = EXCLUDED.ponto,
              apelido = EXCLUDED.apelido,
              tribo_id = EXCLUDED.tribo_id,
              ocupacao = 0,
              ativo = TRUE;" >/dev/null

# Persiste as coordenadas LOCALMENTE, e é isto que transforma "rode a cada reset" em "rode uma vez":
# o pitch-armar.sh lê este arquivo e recria o ponto sozinho quando o `make demo` o apaga.
#
# `tools/demo/.env.ponto` é ignorado pelo git por `.gitignore:25` (`.env.*`) — conferido com
# `git check-ignore`. É de propósito: endereço de quem grava não é fixture pública, e foi por isso
# que este ponto não virou seed da faixa 900.
ESTADO="$RAIZ/demo/.env.ponto"

# O apelido vai entre ASPAS SIMPLES, com as aspas simples internas escapadas. O arquivo é lido com
# `.` (source), então um valor sem quotes quebra na primeira palavra com espaço, parêntese ou `&` —
# medido com "Padaria da Praça (teste)", que abortou o script com "erro de sintaxe próximo ao token
# inesperado `('". Aspas duplas não bastariam: sobrariam `$` e backtick vivos.
escapar() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }

cat > "$ESTADO" <<ARQUIVO
# Ponto de gravação do vídeo-pitch — gerado por tools/demo/ponto-aqui.sh
# NÃO VERSIONADO: são as coordenadas de onde você grava. Apague o arquivo para voltar ao padrão.
PONTO_CUSTODIA=$(escapar "$PONTO_ID")
PONTO_LAT=$(escapar "$LAT")
PONTO_LON=$(escapar "$LON")
PONTO_APELIDO=$(escapar "$APELIDO")
ARQUIVO

echo
echo "${negrito}ponto de custódia pronto${normal}"
printf '  %-14s %s\n' "id" "$PONTO_ID"
printf '  %-14s %s\n' "apelido" "$APELIDO"
printf '  %-14s %s\n' "coordenada" "lat $LAT, lon $LON"
printf '  %-14s %s\n' "tribo" "$(psql_ "SELECT nome FROM tribo WHERE id = '$TRIBO';")"
printf '  %-14s %s\n' "vagas" "$(psql_ "SELECT capacidade - ocupacao FROM ponto_custodia WHERE id = '$PONTO_ID';")"

# A checagem que importa: o predicado REAL do fan-out, não uma aproximação dele. É a mesma consulta
# de SQL_TRIBOS_NO_RAIO — distância mínima a qualquer ponto da tribo, e não até um centro.
RAIO_ALERTA="${RAIO_ALERTA:-3000}"
echo
echo "${negrito}o alerta do bloco 1:00 vai chegar?${normal}"
psql_ "SELECT t.nome || ' — ' || round(MIN(ST_Distance(p.geo,
            ST_SetSRID(ST_MakePoint($LON, $LAT), 4326)::geography))) || ' m'
       FROM (SELECT pc.tribo_id, pc.ponto AS geo FROM ponto_custodia pc
              WHERE pc.tribo_id IS NOT NULL AND pc.ativo = true
             UNION ALL
             SELECT u.tribo_id, m.origem FROM missao m
               JOIN usuario u ON u.id = m.criador_id WHERE u.tribo_id IS NOT NULL) p
       JOIN tribo t ON t.id = p.tribo_id
      WHERE ST_DWithin(p.geo, ST_SetSRID(ST_MakePoint($LON, $LAT), 4326)::geography, $RAIO_ALERTA)
      GROUP BY t.nome ORDER BY 1;" | sed "s/^/  ${verde}✓${normal} /"

# O guarda contra lat/lon trocados. O bloco acima nunca revela a troca, porque ele mede a distância
# do ponto até a própria tribo dele — que é sempre 0 m. Aqui a medição EXCLUI este ponto, então um
# número absurdo denuncia a inversão, que é o erro real que este script convida a cometer.
LONGE=$(psql_ "SELECT round(MIN(ST_Distance(pc.ponto,
            ST_SetSRID(ST_MakePoint($LON, $LAT), 4326)::geography)) / 1000)
        FROM ponto_custodia pc WHERE pc.id <> '$PONTO_ID';")
if [ "${LONGE:-0}" -gt 200 ]; then
  echo
  echo "  ${vermelho}⚠ atenção${normal}  o ponto mais próximo do seed está a ${LONGE} km daqui."
  echo "    O Google Maps mostra \"lat, lon\" — e é nessa ordem que este script os recebe."
  echo "    Se você colou ao contrário, rode de novo com os dois trocados."
fi

echo
echo "${negrito}quem recebe, de fato${normal}  (tribo no raio + NOTIFICACAO e LOCALIZACAO vigentes + nível ≥ 2)"
DESTINATARIOS=$(psql_ "SELECT count(*) FROM usuario u
   WHERE u.tribo_id = '$TRIBO' AND u.status = 'ATIVO' AND u.nivel >= 2
     AND (SELECT c.concedido FROM consentimento c
           WHERE c.usuario_id = u.id AND c.tipo = 'NOTIFICACAO'
           ORDER BY c.criado_em DESC LIMIT 1) IS TRUE
     AND (SELECT c.concedido FROM consentimento c
           WHERE c.usuario_id = u.id AND c.tipo = 'LOCALIZACAO'
           ORDER BY c.criado_em DESC LIMIT 1) IS TRUE;")
if [ "${DESTINATARIOS:-0}" -gt 0 ]; then
  echo "  ${verde}✓${normal} $DESTINATARIOS pessoa(s) na Tribo Cidade Líder"
else
  echo "  ${vermelho}✗${normal} ninguém — o seed não dá consentimento a esta tribo."
  echo "    Ligue NOTIFICACAO e LOCALIZACAO em Perfil → Privacidade, no app, antes de gravar."
  echo "    Sem isso a missão nasce e aparece no radar, mas nenhum alerta chega na aba Avisos."
fi

echo
echo "${negrito}agora arme o pitch — sem variável nenhuma:${normal}"
echo "  ${cinza}bash tools/demo/pitch-armar.sh${normal}"
echo
echo "${cinza}Ele lê tools/demo/.env.ponto (não versionado) e usa este ponto sozinho. Se um${normal}"
echo "${cinza}'make demo' apagar o ponto, ele o RECRIA daqui — você não precisa rodar isto de novo.${normal}"
echo "${cinza}O check-in vai exigir o aparelho a 200 m de lat $LAT, lon $LON.${normal}"
