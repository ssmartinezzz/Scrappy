#!/usr/bin/env bash
# Optional Grafana over the scraper's Postgres (docker-compose.observability.yml).
#
#   scripts/observability.sh up [--stack]     levanta Grafana (crea observability/grafana.env la 1ra vez)
#   scripts/observability.sh down [--stack]   lo apaga (los datos de Grafana quedan en su volumen)
#   scripts/observability.sh status [--stack] estado de los contenedores + health de Grafana
#   scripts/observability.sh logs [--stack]   sigue los logs de Grafana
#   scripts/observability.sh reset-admin [--stack]  pone la clave de observability/grafana.env al admin
#
# Sin --stack: el backend corre en el host (CLI nativo / dev-db.sh) y Grafana va
# por host.docker.internal. Con --stack: se suma al `docker compose` completo y
# habla con el servicio `postgres` de ese stack.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OBS_ENV="$ROOT/observability/grafana.env"

die() { echo "ERROR: $*" >&2; exit 1; }

random_secret() { od -An -N24 -tx1 /dev/urandom | tr -d ' \n'; }

ensure_env() {
  [[ -f "$OBS_ENV" ]] && return
  cp "$ROOT/observability/grafana.env.example" "$OBS_ENV"
  sed -i.bak \
    -e "s/^GRAFANA_ADMIN_PASSWORD=$/GRAFANA_ADMIN_PASSWORD=$(random_secret)/" \
    -e "s/^GRAFANA_DB_PASSWORD=$/GRAFANA_DB_PASSWORD=$(random_secret)/" \
    "$OBS_ENV"
  rm -f "$OBS_ENV.bak"
  chmod 600 "$OBS_ENV"
  echo "Creado observability/grafana.env con claves al azar."
}

# Fills the global COMPOSE array for the chosen mode.
compose_cmd() {
  local stack="$1"
  COMPOSE=(docker compose --project-directory "$ROOT")
  # Root .env first (DB superuser creds), ours second so it wins.
  [[ -f "$ROOT/.env" ]] && COMPOSE+=(--env-file "$ROOT/.env")
  COMPOSE+=(--env-file "$OBS_ENV")
  if [[ "$stack" == 1 ]]; then
    COMPOSE+=(-f "$ROOT/docker-compose.yml" -f "$ROOT/docker-compose.observability.yml")
  else
    COMPOSE+=(-p scrappy-observability -f "$ROOT/docker-compose.observability.yml")
    if uses_host_network; then
      COMPOSE+=(-f "$ROOT/observability/compose.host-network.yml")
      # Only the default host is translated; a custom one (a remote PG) is kept.
      [[ "$(env_value GRAFANA_DB_HOST)" == host.docker.internal ]] && export GRAFANA_DB_HOST=127.0.0.1
    fi
  fi
}

env_value() { sed -n "s/^$1=//p" "$OBS_ENV"; }

# Linux Engine has no real host.docker.internal; Desktop (any OS) does.
uses_host_network() {
  [[ "$(uname -s)" == Linux ]] && [[ "$(docker info --format '{{.OperatingSystem}}')" != *"Docker Desktop"* ]]
}

admin_password() { env_value GRAFANA_ADMIN_PASSWORD; }
grafana_url() {
  local host port
  host="$(env_value GRAFANA_BIND_HOST)"; port="$(env_value GRAFANA_PORT)"
  [[ -z "$host" || "$host" == 0.0.0.0 ]] && host=127.0.0.1
  echo "http://$host:${port:-3001}"
}

cmd="${1:-}"; shift || true
stack=0
for arg in "$@"; do
  case "$arg" in
    --stack) stack=1 ;;
    *) die "argumento desconocido: $arg" ;;
  esac
done

command -v docker >/dev/null 2>&1 || die "docker no esta instalado o no esta en el PATH."
docker info >/dev/null 2>&1 || die "el daemon de Docker no responde."
ensure_env
compose_cmd "$stack"
[[ "$stack" == 1 ]] && export GRAFANA_DB_HOST=postgres

case "$cmd" in
  up)
    if [[ "$stack" == 1 ]]; then
      "${COMPOSE[@]}" up -d --wait
    else
      "${COMPOSE[@]}" up -d --wait grafana
    fi
    echo
    echo "Grafana: $(grafana_url)   usuario: admin   clave: $(admin_password)"
    ;;
  down)
    if [[ "$stack" == 1 ]]; then
      "${COMPOSE[@]}" rm -sf grafana grafana-db-init
    else
      "${COMPOSE[@]}" down
    fi
    ;;
  status)
    "${COMPOSE[@]}" ps -a grafana grafana-db-init
    if curl -fsS "$(grafana_url)/api/health" >/dev/null 2>&1; then
      echo "Grafana responde en $(grafana_url)"
    else
      echo "Grafana NO responde en $(grafana_url)"
    fi
    ;;
  logs)
    "${COMPOSE[@]}" logs -f --tail 100 grafana grafana-db-init
    ;;
  reset-admin)
    "${COMPOSE[@]}" exec grafana grafana cli admin reset-admin-password "$(admin_password)"
    ;;
  *)
    sed -n '2,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
