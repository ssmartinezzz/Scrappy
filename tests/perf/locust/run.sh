#!/usr/bin/env bash
#
#   ./run.sh smoke     1 usuario, 30 s    — baseline: recorre TODOS los endpoints en orden
#   ./run.sh carga     20 usuarios, 3 m   — el tráfico esperado
#   ./run.sh stress    200 usuarios, 5 m  — subir hasta que duela
#   ./run.sh spike     150 de golpe, 2 m  — ¿se recupera de un pico?
#   ./run.sh login     Argon2id, solo
#   ./run.sh ui        la UI web en :8089, para explorar a mano
#
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOST="${PERF_API_BASE_URL:-http://localhost:3000}"
FORMA="${1:-smoke}"

die() { printf '\n\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }
say() { printf '\n\033[1;36m▸ %s\033[0m\n' "$*"; }

curl -sf -o /dev/null "$HOST/" || die "no hay backend en $HOST (ver README.md)"
[ -n "${PERF_USERNAME:-}" ] && [ -n "${PERF_PASSWORD:-}" ] || die \
  "faltan PERF_USERNAME / PERF_PASSWORD (ver README.md)"

VENV="$AQUI/.venv"
if [ ! -x "$VENV/bin/locust" ]; then
  say "creando $VENV (primera corrida)"
  python3 -m venv "$VENV"
  "$VENV/bin/pip" install --quiet --upgrade pip
  "$VENV/bin/pip" install --quiet -r "$AQUI/requirements.txt"
fi

SALIDA="$AQUI/.resultados"; mkdir -p "$SALIDA"
L=("$VENV/bin/locust" -f "$AQUI/locustfile.py" --host "$HOST"
   --html "$SALIDA/$FORMA.html" --csv "$SALIDA/$FORMA")

case "$FORMA" in
  smoke)  say "smoke — 1 usuario, 30 s";        "${L[@]}" --headless -u 1   -r 1   -t 30s BaselineUser ;;
  carga)  say "carga — 20 usuarios, 3 min";     "${L[@]}" --headless -u 20  -r 2   -t 3m  CatalogoUser ;;
  stress) say "stress — 200 usuarios, 5 min";   "${L[@]}" --headless -u 200 -r 1   -t 5m  CatalogoUser ;;
  spike)  say "spike — 150 de golpe, 2 min";    "${L[@]}" --headless -u 150 -r 150 -t 2m  CatalogoUser ;;
  login)  say "login — 10 usuarios, 1 min";     "${L[@]}" --headless -u 10  -r 2   -t 1m  LoginUser ;;
  ui)     say "UI en http://localhost:8089";    "$VENV/bin/locust" -f "$AQUI/locustfile.py" --host "$HOST" ;;
  *)      die "forma desconocida: $FORMA (smoke|carga|stress|spike|login|ui)" ;;
esac

say "reporte HTML: $SALIDA/$FORMA.html"
