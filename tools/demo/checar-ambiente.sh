#!/usr/bin/env bash
# Responde "o ambiente da demonstração está pronto?" com ✓/✗ por linha, e a AÇÃO CORRETIVA ao lado
# de cada ✗. Rode antes de entrar na sala.
#
#   bash tools/demo/checar-ambiente.sh
#
# Sai com 1 se houver qualquer ✗, para poder encadear num script.
#
# NÃO usa `set -e`: aqui um ✗ é RESULTADO, não erro de execução — abortar na primeira falha
# esconderia as outras seis, que é justamente o que um verificador não pode fazer.
set -uo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$RAIZ" || exit 1

FALHAS=0
API_NO_AR=0

if [ -t 1 ]; then V=$'\033[32m'; X=$'\033[31m'; A=$'\033[33m'; N=$'\033[1m'; Z=$'\033[0m'
else V=""; X=""; A=""; N=""; Z=""; fi

ok()     { printf '  %s✓%s  %s\n' "$V" "$Z" "$1"; }
falha()  { printf '  %s✗%s  %s\n' "$X" "$Z" "$1"; printf '       %s→ %s%s\n' "$A" "$2" "$Z"
           FALHAS=$((FALHAS + 1)); }
aviso()  { printf '  %s!%s  %s\n' "$A" "$Z" "$1"; }

printf '\n%sAmbiente da demonstração — Omni-Tribo%s\n\n' "$N" "$Z"

# ---------------------------------------------------------------------------------------------
# 1. JDK 21 — e JDK de verdade, não JRE.
#
# `java -version` NÃO distingue os dois: um JRE responde a ele igualzinho. Quem separa é o
# `javac`, que só existe no JDK. É o caso desta máquina (Fedora): o `java` do PATH é JRE-only, e o
# Maven morre com "No compiler is provided in this environment" — mensagem que não menciona
# JAVA_HOME em lugar nenhum.
# ---------------------------------------------------------------------------------------------
ACAO_JDK='export JAVA_HOME="$(ls -d ~/.sdkman/candidates/java/21*/ | head -1)"  (ou: sdk use java 21.0.12-tem)'
if [ -z "${JAVA_HOME:-}" ]; then
  falha "JAVA_HOME não está definida" "$ACAO_JDK"
elif [ ! -x "$JAVA_HOME/bin/javac" ]; then
  falha "JAVA_HOME aponta para um JRE, não um JDK — não há javac em $JAVA_HOME/bin" "$ACAO_JDK"
else
  JDK_VER="$("$JAVA_HOME/bin/javac" -version 2>&1 | awk '{print $2}')"
  case "$JDK_VER" in
    21.*) ok "JDK $JDK_VER (javac presente — é JDK, não JRE)" ;;
    *)    falha "javac é $JDK_VER; o projeto exige 21" "$ACAO_JDK" ;;
  esac
fi

# ---------------------------------------------------------------------------------------------
# 2. Node.
#
# O mínimo é o `engines` de apps/mobile/package.json (>=22.13), e NÃO "major igual a 22": esta
# máquina roda v24 e o app funciona. O .nvmrc fixa 22 porque é o que o CI usa, então um major
# diferente vira AVISO — a diferença entre CI e local é fato a saber, não defeito a barrar.
# ---------------------------------------------------------------------------------------------
NODE_MIN_MAJOR=22
NODE_MIN_MINOR=13
if ! command -v node >/dev/null 2>&1; then
  falha "node não está no PATH" "instale o Node 22 (nvm install) — apps/mobile/.nvmrc fixa a versão"
else
  NODE_VER="$(node -v 2>/dev/null | tr -d 'v')"
  NODE_MAJOR="${NODE_VER%%.*}"
  NODE_RESTO="${NODE_VER#*.}"
  NODE_MINOR="${NODE_RESTO%%.*}"
  if [ "$NODE_MAJOR" -lt "$NODE_MIN_MAJOR" ] ||
     { [ "$NODE_MAJOR" -eq "$NODE_MIN_MAJOR" ] && [ "$NODE_MINOR" -lt "$NODE_MIN_MINOR" ]; }; then
    falha "Node v$NODE_VER — o package.json do mobile exige >=$NODE_MIN_MAJOR.$NODE_MIN_MINOR" \
          "cd apps/mobile && nvm use   (lê o .nvmrc)"
  else
    ok "Node v$NODE_VER (mínimo >=$NODE_MIN_MAJOR.$NODE_MIN_MINOR)"
    NVMRC="$(cat apps/mobile/.nvmrc 2>/dev/null | tr -d '[:space:]')"
    if [ -n "$NVMRC" ] && [ "$NODE_MAJOR" != "$NVMRC" ]; then
      aviso "o CI roda Node $NVMRC (apps/mobile/.nvmrc) e aqui é $NODE_MAJOR — atende o mínimo, mas não é a mesma versão"
    fi
  fi
fi

# ---------------------------------------------------------------------------------------------
# 3. Runtime de contêiner e o banco de pé.
#
# Diz QUAL runtime respondeu, porque nesta máquina não é o Docker: o CLI dele sobreviveu ao Docker
# Desktop desinstalado e aponta para um socket morto, enquanto o banco roda sob podman. A regra de
# resolução mora em tools/demo/compose.sh, e é a mesma que o Makefile usa — uma só, para não
# divergirem.
#
# A sonda é uma consulta por TCP, não `pg_isready` no socket unix, pelo mesmo motivo documentado no
# alvo `demo` do Makefile: durante o initdb o socket unix já atende e a porta 5432 ainda não.
# ---------------------------------------------------------------------------------------------
if ! RUNTIME="$(bash tools/demo/compose.sh --runtime 2>/dev/null)"; then
  falha "nenhum runtime de contêiner respondeu (nem docker, nem socket do podman)" \
        "systemctl --user start podman.socket   (ou: sudo systemctl start docker)"
elif ! bash tools/demo/compose.sh ps db 2>/dev/null | grep -q "Up"; then
  falha "runtime é $RUNTIME, mas o container do banco não está Up" "make up"
elif ! bash tools/demo/compose.sh exec -T db \
        sh -c 'psql -h 127.0.0.1 -U $POSTGRES_USER -d $POSTGRES_DB -tAc "SELECT 1"' \
        >/dev/null 2>&1; then
  falha "container do banco Up, mas o Postgres ainda não responde a consulta por TCP" \
        "espere alguns segundos e repita; se persistir:  make logs"
else
  ok "banco de pé e aceitando conexão — runtime: $RUNTIME"
  BANCO_NO_AR=1
fi

# ---------------------------------------------------------------------------------------------
# 3b. O SCHEMA e o SEED aplicados.
#
# ESTA VERIFICAÇÃO EXISTE POR UM FALSO POSITIVO OBSERVADO. Rodar `make demo` com o backend já de pé
# recria o volume, e o backend NÃO remigra: o Flyway só roda no boot. O resultado é um backend
# ligado a um banco vazio — e todas as outras seis verificações passavam, porque `/api/v1/ping` não
# toca o banco. Medido no estado quebrado: 3 tabelas em `public`, `POST /auth/login` devolvendo 000,
# e o script dizendo "Tudo pronto".
#
# As 7 migrations de seed (V900-V906) só existem se o Flyway rodou contra ESTE volume. É o sinal
# mais barato que separa "banco de pé" de "banco que serve a demonstração".
# ---------------------------------------------------------------------------------------------
if [ "${BANCO_NO_AR:-0}" -eq 1 ]; then
  SEEDS="$(bash tools/demo/compose.sh exec -T db \
    sh -c 'psql -h 127.0.0.1 -U $POSTGRES_USER -d $POSTGRES_DB -tAc \
      "SELECT count(*) FROM flyway_schema_history WHERE version::numeric >= 900"' 2>/dev/null \
    | tr -d '[:space:]')"
  if [ "$SEEDS" = "7" ]; then
    ok "schema e seed aplicados (7 migrations V900-V906)"
  elif [ -z "$SEEDS" ]; then
    falha "o banco não tem schema — o Flyway nunca rodou contra este volume" \
          "(re)inicie o backend: ele migra no boot. Se ele JÁ estava de pé quando você rodou 'make demo', reinicie-o — ele está ligado a um banco vazio"
  else
    falha "só $SEEDS das 7 migrations de seed foram aplicadas" \
          "make demo   (e reinicie o backend depois)"
  fi
fi

# ---------------------------------------------------------------------------------------------
# 4. Chaves RSA.
#
# services/api/keys/ é gitignored, então clone novo NÃO tem as chaves e NENHUM contexto Spring
# sobe sem elas. Confere também que a privada parseia: um PEM truncado passa no teste de
# existência e só falha lá no boot.
# ---------------------------------------------------------------------------------------------
if [ ! -f services/api/keys/private.pem ] || [ ! -f services/api/keys/public.pem ]; then
  falha "faltam as chaves RSA em services/api/keys/" "bash tools/gerar-chaves-dev.sh"
elif ! openssl rsa -in services/api/keys/private.pem -noout -check >/dev/null 2>&1; then
  falha "services/api/keys/private.pem existe mas não é uma chave RSA válida" \
        "rm services/api/keys/*.pem && bash tools/gerar-chaves-dev.sh"
else
  PERM="$(stat -c '%a' services/api/keys/private.pem 2>/dev/null)"
  ok "chaves RSA presentes e válidas (private.pem $PERM)"
  [ "$PERM" = "600" ] || aviso "private.pem está $PERM; o esperado é 600 — chmod 600 services/api/keys/private.pem"
fi

# ---------------------------------------------------------------------------------------------
# 5. A API responde.
# ---------------------------------------------------------------------------------------------
PING="$(curl -s -m 5 http://localhost:8080/api/v1/ping 2>/dev/null)"
if printf '%s' "$PING" | grep -q '"pong"'; then
  API_NO_AR=1
  ok "API responde em localhost:8080/api/v1/ping"
else
  falha "a API não respondeu em localhost:8080 (backend fora do ar)" \
        "cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev"
fi

# ---------------------------------------------------------------------------------------------
# 6. A 8080 alcançável pelo IP DA LAN — o erro nº 1 do celular.
#
# `localhost` dentro do celular é o PRÓPRIO celular: o app abre, o login gira e morre em "não foi
# possível falar com o servidor". Testar por localhost aqui não pega isso; só o IP da LAN pega.
#
# A distinção do diagnóstico importa: se a #5 já falhou, não há o que a 8080 sirva, e culpar o
# firewall mandaria você mexer no lugar errado.
# ---------------------------------------------------------------------------------------------
IP_LAN="$(ip -4 -o addr show scope global 2>/dev/null \
  | awk '$2 !~ /^(docker|br-|veth|podman|cni-)/ {print $4}' | cut -d/ -f1 | head -1)"

if [ -z "$IP_LAN" ]; then
  falha "nenhum IP de LAN encontrado (só loopback e interfaces de contêiner)" \
        "conecte-se ao Wi-Fi; sem rede o celular não alcança o backend de jeito nenhum"
elif timeout 3 bash -c "exec 3<>/dev/tcp/$IP_LAN/8080" 2>/dev/null; then
  ok "porta 8080 alcançável em $IP_LAN — é o endereço que o celular usa"
elif [ "$API_NO_AR" -eq 0 ]; then
  falha "8080 inalcançável em $IP_LAN, porque o backend está fora do ar (ver acima)" \
        "suba o backend e rode este script de novo — só então isto distingue firewall"
else
  falha "a API responde em localhost mas NÃO em $IP_LAN:8080 — é firewall" \
        "sudo firewall-cmd --add-port=8080/tcp   (permanente: --permanent && firewall-cmd --reload)"
fi

# ---------------------------------------------------------------------------------------------
# 7. O IP, em destaque.
#
# Impresso mesmo quando tudo passa: é o valor que muda de rede para rede, e é por isso que nenhum
# documento deste repositório o fixa.
# ---------------------------------------------------------------------------------------------
printf '\n  %sIP desta máquina na LAN:  %s%s\n' "$N" "${IP_LAN:-<nenhum>}" "$Z"
if [ -n "$IP_LAN" ]; then
  printf '  Na maioria dos casos o app acerta sozinho (deriva do host do Metro).\n'
  printf '  Se não acertar, force:\n'
  printf '    EXPO_PUBLIC_API_URL=http://%s:8080 npm start\n' "$IP_LAN"
fi

printf '\n'
if [ "$FALHAS" -eq 0 ]; then
  printf '  %sTudo pronto.%s\n\n' "$V" "$Z"
  exit 0
fi
printf '  %s%s verificação(ões) reprovada(s).%s Corrija e rode de novo.\n\n' "$X" "$FALHAS" "$Z"
exit 1
