#!/usr/bin/env bash
# Fashion Scraper Argentina — dev Postgres (Docker) on-demand launcher.
#
# Additive helper for the Linux/macOS dev box. It brings up the standalone
# Postgres container the local backend talks to, and nothing else. It is NOT
# part of any install flow:
#
#   - the Windows portable path uses _tools/pgsql (no Docker at all),
#   - docker-compose.yml is the full three-service Docker path,
#   - this script is the middle ground: host-run backend/frontend (native CLI,
#     `python -m cli`) against a containerized Postgres.
#
# Key invariant — the container must NEVER outlive the machine:
#   * `--restart no` so it does not come back when the Docker daemon starts;
#   * docker.service stays `disabled` (docker.socket is enabled, so the daemon
#     wakes on demand) so nothing starts at boot.
# On shutdown systemd stops the daemon and the container exits cleanly. Run
# this script when you want the DB; it is idempotent.
set -euo pipefail

CONTAINER="${PG_CONTAINER:-fashion-scraper-pg}"
IMAGE="${PG_IMAGE:-postgres:16-alpine}"
PG_PORT="${PG_PORT:-5432}"
# Loopback-only: the container runs with trust auth (no password), so a
# bind to 0.0.0.0 hands the whole database to anyone on the LAN.
PG_BIND="${PG_BIND:-127.0.0.1}"
PG_DB="${PG_DB:-scraper}"
PG_USER="${PG_USER:-postgres}"
PG_VOLUME="${PG_VOLUME:-fashion-scraper-pgdata}"
READY_TIMEOUT="${READY_TIMEOUT:-45}"

die() { echo "ERROR: $*" >&2; exit 1; }

require_docker() {
  command -v docker >/dev/null 2>&1 \
    || die "docker no esta instalado o no esta en el PATH."
  docker info >/dev/null 2>&1 \
    || die "el daemon de Docker no responde. Proba: sudo systemctl start docker"
}

# Prints the container state (running/exited/...), or nothing if absent.
container_state() {
  docker inspect --format '{{.State.Status}}' "$CONTAINER" 2>/dev/null || true
}

wait_ready() {
  local waited=0
  until docker exec "$CONTAINER" pg_isready -U "$PG_USER" -q 2>/dev/null; do
    (( waited >= READY_TIMEOUT )) \
      && die "Postgres no acepto conexiones en ${READY_TIMEOUT}s. Log: docker logs $CONTAINER"
    sleep 1
    waited=$(( waited + 1 ))
  done
}

create_container() {
  # Only reached when the container does not exist at all. Uses a NAMED volume
  # so the data directory is stable and survives a future `docker rm`, unlike
  # the anonymous volume the original hand-run container was created with.
  echo "No existe el container '$CONTAINER'. Creandolo desde $IMAGE..."
  echo "Volumen de datos: $PG_VOLUME (vacio: la base arranca desde cero,"
  echo "Flyway corre las migraciones al arrancar el backend)."
  docker run -d \
    --name "$CONTAINER" \
    --restart no \
    -p "${PG_BIND}:${PG_PORT}:5432" \
    -e POSTGRES_USER="$PG_USER" \
    -e POSTGRES_DB="$PG_DB" \
    -e POSTGRES_HOST_AUTH_METHOD=trust \
    -v "${PG_VOLUME}:/var/lib/postgresql/data" \
    "$IMAGE" >/dev/null
}

cmd_up() {
  require_docker
  case "$(container_state)" in
    running)
      echo "Ya estaba corriendo: $CONTAINER"
      ;;
    "")
      create_container
      ;;
    *)
      echo "Levantando $CONTAINER..."
      docker start "$CONTAINER" >/dev/null
      ;;
  esac

  wait_ready
  echo "Postgres listo en localhost:${PG_PORT} (db=${PG_DB}, user=${PG_USER}, sin password)."
  echo "DATABASE_URL (Java) : jdbc:postgresql://localhost:${PG_PORT}/${PG_DB}"
  echo "Apagarlo a mano     : $0 down   (igual muere solo al apagar la PC)"
}

cmd_down() {
  require_docker
  if [[ "$(container_state)" == "running" ]]; then
    echo "Frenando $CONTAINER..."
    docker stop "$CONTAINER" >/dev/null
    echo "Frenado. Los datos quedan en el volumen."
  else
    echo "$CONTAINER no estaba corriendo."
  fi
}

cmd_status() {
  require_docker
  local state
  state="$(container_state)"
  if [[ -z "$state" ]]; then
    echo "$CONTAINER: no existe."
    return
  fi
  echo "$CONTAINER: $state"
  # Surface the restart policy: if this ever stops being "no", the container
  # will start resurrecting itself with the daemon.
  echo "restart policy: $(docker inspect --format '{{.HostConfig.RestartPolicy.Name}}' "$CONTAINER")"
  [[ "$state" == "running" ]] && docker exec "$CONTAINER" pg_isready -U "$PG_USER" || true
}

case "${1:-up}" in
  up|start|"")  cmd_up ;;
  down|stop)    cmd_down ;;
  status)       cmd_status ;;
  -h|--help|help)
    echo "Uso: $0 [up|down|status]"
    echo "  up      (default) levanta el Postgres de dev y espera a que acepte conexiones"
    echo "  down    lo frena (los datos quedan en el volumen)"
    echo "  status  muestra estado + restart policy"
    ;;
  *) die "comando desconocido: $1 (proba: up | down | status)" ;;
esac
