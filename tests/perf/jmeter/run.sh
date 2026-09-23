#!/usr/bin/env bash
#
#   ./run.sh smoke    1 hilo, 3 iteraciones  — la baseline: ¿cuánto tarda cada cosa?
#   ./run.sh carga    20 usuarios, 3 min     — el tráfico esperado
#   ./run.sh stress   escalones a 200        — subir hasta que duela
#   ./run.sh spike    150 de golpe           — ¿se recupera de un pico?
#   ./run.sh login    Argon2id, solo
#   ./run.sh jmx      exporta los planes .jmx para abrir en la GUI de JMeter
#
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOST="${PERF_API_BASE_URL:-http://localhost:3000}"
FORMA="${1:-smoke}"

die() { printf '\n\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }
say() { printf '\n\033[1;36m▸ %s\033[0m\n' "$*"; }

# JMeter corre sobre la JRE 21 de esta máquina; la JDK 24 sólo compila.
export JAVA_HOME="${PERF_JAVA_HOME:-${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}}"

if [ "$FORMA" = "jmx" ]; then
  say "exportando planes .jmx"
  mvn -q -f "$AQUI/pom.xml" compile exec:java
  exit 0
fi

curl -sf -o /dev/null "$HOST/" || die "no hay backend en $HOST (ver README.md)"

# La cuenta, resuelta sin que haya que exportar nada. Tres lugares, en orden:
# el entorno (para apuntar a otra cuenta sin tocar nada), el archivo que dejó
# perf-user.sh, y —si no hay ninguno— correr perf-user.sh acá mismo. Acordarse
# de exportar dos variables antes de cada corrida es exactamente el tipo de paso
# que hace que una suite se deje de correr.
CREDENCIALES="$AQUI/../.perf-credentials.env"
if [ -z "${PERF_USERNAME:-}" ] || [ -z "${PERF_PASSWORD:-}" ]; then
  if [ ! -f "$CREDENCIALES" ]; then
    say "sin credenciales: creando la cuenta con perf-user.sh"
    PERF_API_BASE_URL="$HOST" "$AQUI/../perf-user.sh" >/dev/null || die \
"no se pudo crear la cuenta de performance. Corré el script a mano con un ADMIN:
   ADMIN_USERNAME=<vos> ADMIN_PASSWORD=<tu password> tests/perf/perf-user.sh"
  fi
  # shellcheck disable=SC1090
  set -a; . "$CREDENCIALES"; set +a
fi
[ -n "${PERF_USERNAME:-}" ] && [ -n "${PERF_PASSWORD:-}" ] || die \
  "$CREDENCIALES quedó sin PERF_USERNAME/PERF_PASSWORD"

case "$FORMA" in
  smoke)  CLASE=SmokeIT  ;;
  carga)  CLASE=CargaIT  ;;
  stress) CLASE=StressIT ;;
  spike)  CLASE=SpikeIT  ;;
  login)  CLASE=LoginIT  ;;
  *) die "forma desconocida: $FORMA (smoke|carga|stress|spike|login|jmx)" ;;
esac

say "$FORMA — $CLASE contra $HOST"
mvn -f "$AQUI/pom.xml" verify -Dit.test="$CLASE"

say "resultados JTL: $AQUI/target/jtl/"
