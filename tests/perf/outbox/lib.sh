# Compartido por los scripts de esta carpeta. Se carga con `. lib.sh`; no se ejecuta.
#
# Todo se parametriza por entorno. Ningún valor secreto vive en un archivo: las
# credenciales del backend se generan al azar en cada corrida.

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RAIZ="$(cd "$AQUI/../../.." && pwd)"

OUTBOX_DB="${OUTBOX_DB:-perf_outbox}"
OUTBOX_PGHOST="${OUTBOX_PGHOST:-127.0.0.1}"
OUTBOX_PGPORT="${OUTBOX_PGPORT:-5432}"
OUTBOX_PGUSER="${OUTBOX_PGUSER:-postgres}"
OUTBOX_JAR="${OUTBOX_JAR:-$RAIZ/scraper/target/fashion-scraper-1.0.1.jar}"
OUTBOX_JAVA="${OUTBOX_JAVA:-/usr/lib/jvm/java-21-openjdk-amd64/bin/java}"
OUTBOX_HEAP="${OUTBOX_HEAP:-512m}"
OUTBOX_PORT="${OUTBOX_PORT:-3100}"
OUTBOX_SMTP_HOST="${OUTBOX_SMTP_HOST:-127.0.0.1}"
OUTBOX_SMTP_PORT="${OUTBOX_SMTP_PORT:-1025}"
OUTBOX_MAILPIT_API="${OUTBOX_MAILPIT_API:-http://127.0.0.1:8025}"
OUTBOX_WORK="${OUTBOX_WORK:-$(mktemp -d -t outbox-perf.XXXXXX)}"

die() { printf '\n\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }
say() { printf '\n\033[1;36m▸ %s\033[0m\n' "$*"; }

pg() { psql -h "$OUTBOX_PGHOST" -p "$OUTBOX_PGPORT" -U "$OUTBOX_PGUSER" "$OUTBOX_DB" -v ON_ERROR_STOP=1 -q "$@"; }
pg_admin() { psql -h "$OUTBOX_PGHOST" -p "$OUTBOX_PGPORT" -U "$OUTBOX_PGUSER" postgres -v ON_ERROR_STOP=1 -q "$@"; }

# Una base descartable: nunca la del catálogo real.
db_crear() {
  [[ "$OUTBOX_DB" != "scraper" ]] || die "OUTBOX_DB=scraper es la base real; usá otra"
  pg_admin -c "CREATE DATABASE \"$OUTBOX_DB\"" 2>/dev/null || true
}
db_borrar() { pg_admin -c "DROP DATABASE IF EXISTS \"$OUTBOX_DB\" WITH (FORCE)"; }

# jar_arrancar <jar> <log> [VAR=valor ...]  — las VAR extra pisan el entorno del backend.
jar_arrancar() {
  local jar="$1" log="$2"; shift 2
  [[ -f "$jar" ]] || die "no existe el jar: $jar"
  ! (exec 3<>"/dev/tcp/127.0.0.1/$OUTBOX_PORT") 2>/dev/null || die "el puerto $OUTBOX_PORT ya está en uso; otro backend contestaría por el que se mide"
  local secreto="${OUTBOX_SECRETO:-$(head -c 24 /dev/urandom | base64 | tr -d "\n=/+")}"
  env DATABASE_URL="jdbc:postgresql://$OUTBOX_PGHOST:$OUTBOX_PGPORT/$OUTBOX_DB" \
      DATABASE_USERNAME="$OUTBOX_PGUSER" DATABASE_PASSWORD="${OUTBOX_PGPASSWORD:-}" \
      APP_CORS_ALLOWED_ORIGINS=http://localhost:5173 \
      AUTH_JWT_SECRET="jwt${secreto}${secreto}" \
      ADMIN_BOOTSTRAP_USERNAME=admin ADMIN_BOOTSTRAP_PASSWORD="adm${secreto}" \
      CLI_SERVICE_ACCOUNT_USERNAME=cli-svc CLI_SERVICE_ACCOUNT_PASSWORD="cli${secreto}" \
      PASSWORD_RESET_CHANNEL=console SERVER_PORT="$OUTBOX_PORT" LOG_DIR="$OUTBOX_WORK/logs" \
      SMTP_HOST="$OUTBOX_SMTP_HOST" SMTP_PORT="$OUTBOX_SMTP_PORT" SMTP_FROM_ADDRESS=alertas@example.test \
      "$@" \
      "$OUTBOX_JAVA" "-Xms$OUTBOX_HEAP" "-Xmx$OUTBOX_HEAP" -jar "$jar" >"$log" 2>&1 &
  echo $! > "$OUTBOX_WORK/jar.pid"
  local i
  for i in $(seq 1 120); do
    [[ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$OUTBOX_PORT/")" = 200 ]] && { sleep 2; return 0; }
    sleep 1
  done
  jar_parar; die "el backend no levantó; mirá $log"
}
jar_parar() {
  [[ -f "$OUTBOX_WORK/jar.pid" ]] || return 0
  kill "$(cat "$OUTBOX_WORK/jar.pid")" 2>/dev/null || true
  while kill -0 "$(cat "$OUTBOX_WORK/jar.pid")" 2>/dev/null; do sleep 0.5; done
  rm -f "$OUTBOX_WORK/jar.pid"
}

mailpit_vaciar() { curl -s -X DELETE "$OUTBOX_MAILPIT_API/api/v1/messages" >/dev/null; }
mailpit_total() { curl -s "$OUTBOX_MAILPIT_API/api/v1/messages?limit=1" | python3 -c 'import json,sys; print(json.load(sys.stdin)["total"])'; }
