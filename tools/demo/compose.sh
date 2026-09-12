#!/usr/bin/env bash
# Wrapper de `docker compose`: resolve o RUNTIME de contêiner antes de delegar.
#
# POR QUE ESTE ARQUIVO EXISTE. Numa máquina onde o Docker Desktop foi removido mas o CLI ficou, o
# `docker` continua no PATH e continua apontando para o socket do Desktop, que não existe mais:
#
#   $ docker ps
#   Cannot connect to the Docker daemon at unix:///home/<user>/.docker/desktop/docker.sock.
#   $ podman ps
#   omnitribo-db   Up 5 hours (healthy)
#
# O container do banco está DE PÉ o tempo todo — quem não o enxerga é o cliente. O sintoma engana
# porque `make ps` diz "daemon não está rodando" enquanto o Postgres atende na 5432 normalmente.
#
# A detecção fica aqui, e não em `$(shell …)` no Makefile, por duas razões medidas:
#   • com `:=` o Make roda `docker info` em TODA invocação, `make help` inclusive;
#   • `make demo` chama `make reset`, que é outro processo do Make — uma variável resolvida no
#     alvo `demo` não alcançaria o `reset`. Só o ambiente exportado atravessa, e é o que o
#     `exec` daqui garante.
#
# `checar-ambiente.sh` consome o mesmo arquivo por `--runtime`, para que exista UMA regra de
# resolução no repositório e não duas que possam divergir.
set -euo pipefail

# Sempre a partir da raiz: o docker-compose.yml está lá, e os chamadores (Makefile, carrier-mock,
# conservacao-por-categoria) rodam de cwd diferentes.
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)" || exit 1

# Descreve o runtime resolvido em `RUNTIME_DESC` e deixa o ambiente pronto para o `exec`.
# Não imprime nada em stdout: quem chama `compose.sh ps` espera a saída do compose, e só dela.
resolver_runtime() {
  # DOCKER_HOST vindo de fora é decisão de quem chamou — respeitada sem checagem, porque
  # sobrescrevê-la aqui quebraria CI e qualquer ambiente com socket remoto.
  if [ -n "${DOCKER_HOST:-}" ]; then
    RUNTIME_DESC="docker (DOCKER_HOST do ambiente: $DOCKER_HOST)"
    return 0
  fi

  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    RUNTIME_DESC="docker"
    return 0
  fi

  local sock="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/podman/podman.sock"
  if [ -S "$sock" ]; then
    export DOCKER_HOST="unix://$sock"
    RUNTIME_DESC="podman (socket rootless: $sock)"
    return 0
  fi

  RUNTIME_DESC=""
  return 1
}

if ! resolver_runtime; then
  cat >&2 <<'ERRO'
Nenhum runtime de contêiner respondeu.

  • O `docker` não atende (daemon parado, ou apontando para um socket que não existe mais).
  • O socket rootless do podman não foi encontrado.

Ação corretiva, na ordem:
  systemctl --user start podman.socket     # se você usa podman
  systemctl --user enable podman.socket    # para não repetir a cada boot
  sudo systemctl start docker              # se você usa Docker de verdade
ERRO
  exit 1
fi

if [ "${1:-}" = "--runtime" ]; then
  # Modo consulta: só diz o que resolveu. Existe para o checar-ambiente.sh.
  echo "$RUNTIME_DESC"
  exit 0
fi

# O aviso vai para stderr, nunca stdout: `compose.sh ps` é lido por script, e uma linha de aviso no
# meio da tabela quebraria quem faz grep nela.
#
# OMNITRIBO_COMPOSE_SILENCIOSO existe para os scripts de DEMONSTRAÇÃO: cada chamada é um processo
# novo, então um script que consulta o banco cinco vezes imprimia o aviso cinco vezes, entremeado na
# saída que a banca está lendo. Quem silencia assume a responsabilidade de dizer qual runtime usou —
# `--runtime` serve para isso e não imprime nada em stderr.
if [ -z "${OMNITRIBO_COMPOSE_SILENCIOSO:-}" ]; then
  case "$RUNTIME_DESC" in
    podman*) echo "[compose] docker não respondeu; usando $RUNTIME_DESC" >&2 ;;
  esac
fi

# `docker compose` também é o caminho do podman: o podman delega ao mesmo plugin de compose, e o
# que muda é só para qual socket o cliente fala — que é exatamente o DOCKER_HOST acima.
if command -v docker >/dev/null 2>&1; then
  exec docker compose "$@"
fi
exec podman compose "$@"
