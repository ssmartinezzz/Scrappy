#!/usr/bin/env bash
# ============================================================================
# API CONTRACT - Fashion Scraper Argentina backend (Java/Spring, port 3000)
#
# This block is the canonical param/response reference for BOTH menu.ps1 and
# menu.sh (design D1 - shared action-contract table, D8 - param-drift
# mitigation). Source of truth: docs/API_REFERENCE.md and
# scraper/src/main/java/ar/scraper/web/ApiController.java. Keep this block
# byte-identical between the two scripts; if drift is suspected at runtime,
# run the `--selftest` action.
#
#   GET  /api/status
#     -> { status: IDLE|RUNNING|DONE|ERROR, mensaje, tieneData, total,
#          mlRefinadas?, mlModeloActivo?, extractionStats? }
#
#   POST /api/scrape?precioMin=<double>&precioMax=<double>
#                    &sitios=<string>&sitios=<string>...&forceRetrain=<bool>
#     all params optional/repeatable; -> { iniciado: bool, mensaje }
#     iniciado:false means a scrape is already running - NOT an error.
#
#   POST /api/ml/entrenar?images=<bool>&epochs=<int>
#     -> 200 { status: "started" }
#     -> 400 { error: "Entrenamiento ya en curso" } (pre-check)
#     -> 409 { error: "Entrenamiento ya en curso" } (race lost)
#     Both 400 and 409 mean "already running" - NOT a failure to surface as
#     an error.
#
#   GET  /api/ml/estado
#     -> { hasTextModel, hasImageModel, training: { running, phase, pct,
#          msg, startedAt }, embeddingsCount, totalProductos, coveragePct }
#
#   GET  /api/sitios
#     -> { base: [{nombre,url,tipo,rubro}], extras: [{nombre,url,plataforma}],
#          precioMinimo, precioMaximo, moneda }
#
#   POST /api/sitios   Body (JSON): { nombre, url, plataforma? = "tiendanube" }
#     -> 200 { ok: bool, mensaje }  (400 if nombre/url blank)
#     JSON body MUST be built via a safe serializer (ConvertTo-Json in
#     menu.ps1, jq -n --arg here) - NEVER string-interpolated. See threat
#     matrix in design.md, "Shell command composition" boundary.
#
#   DELETE /api/sitios/{nombre}
#     -> { ok: bool, mensaje }  (ok:false when the site name is unknown)
# ============================================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ----------------------------------------------------------------------------
# Configuration. From Ejecutar_instalar.sh these come from the parent process
# environment; STANDALONE they don't exist yet, so resolve the repo root first
# and source the installer-generated .env (spec "Installer-generated .env
# sourced by launcher") before anything else. Without DATABASE_URL et al. the
# backend fail-fasts and the readiness poll waits ~60s on a dead process.
# ----------------------------------------------------------------------------
REPO_ROOT="${ROOT:-$SCRIPT_DIR}"

load_dotenv() {
  local envfile="$1" line name
  [ -f "$envfile" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      ''|'#'*) continue ;;
    esac
    name="${line%%=*}"
    name="$(printf '%s' "$name" | tr -d '[:space:]')"
    [ -n "$name" ] || continue
    # Never clobber a var the launcher/parent process already exported.
    if [ -z "${!name:-}" ]; then
      export "$name=${line#*=}"
    fi
  done < "$envfile"
}
load_dotenv "$REPO_ROOT/.env"

API_BASE="${VITE_API_BASE_URL:-http://localhost:3000}"
FRONTEND_PORT=5173
FRONTEND_URL="http://localhost:${FRONTEND_PORT}"

PROJECT_DIR="${PROJECT:-$REPO_ROOT/scraper}"
JAR_PATH="${JAR:-$PROJECT_DIR/scraper.jar}"
# Prefer the bundled JDK 21: a system `java` may be an older major that cannot
# run the Java 21 fat JAR (UnsupportedClassVersionError). Env override wins.
if [ -n "${JAVA_EXE:-}" ]; then
  :
elif [ -x "$REPO_ROOT/_tools/jdk21/bin/java" ]; then
  JAVA_EXE="$REPO_ROOT/_tools/jdk21/bin/java"
else
  JAVA_EXE="java"
fi
FRONTEND_DIR="${FRONTEND_DIR:-$REPO_ROOT/frontend}"

# D6: prefer the vendored jq/curl under _tools/ (installer-provisioned);
# fall back to whatever is already on PATH.
JQ_BIN="${JQ_BIN:-}"
if [ -z "$JQ_BIN" ]; then
  if [ -x "$REPO_ROOT/_tools/jq/jq" ]; then
    JQ_BIN="$REPO_ROOT/_tools/jq/jq"
  else
    JQ_BIN="jq"
  fi
fi

GUM_BIN="${GUM_BIN:-}"
if [ -z "$GUM_BIN" ]; then
  if [ -x "$REPO_ROOT/_tools/gum/gum" ]; then
    GUM_BIN="$REPO_ROOT/_tools/gum/gum"
  elif command -v gum >/dev/null 2>&1; then
    GUM_BIN="gum"
  else
    GUM_BIN=""
  fi
fi

BACKEND_READY=0
FRONTEND_READY=0
# Pre-set by Ejecutar_instalar.sh when it already spawned these processes
# (task 3.4); left empty when menu.sh is invoked standalone (D7).
BACKEND_PID="${JAVA_PID:-}"
FRONTEND_PID="${VITE_PID:-}"

# ----------------------------------------------------------------------------
# D2 - Safe JSON body building. `jq -n --arg` never lets user-supplied text
# reach a shell eval boundary — quotes, semicolons, and "$()"-looking
# sequences are passed as literal --arg values and escaped by jq itself.
# See tests/menu_test.sh for the RED case this protects.
# ----------------------------------------------------------------------------
build_site_json() {
  local nombre="$1" url="$2" plataforma="${3:-tiendanube}"
  "$JQ_BIN" -n \
    --arg nombre "$nombre" \
    --arg url "$url" \
    --arg plataforma "$plataforma" \
    '{nombre: $nombre, url: $url, plataforma: $plataforma}'
}

# ----------------------------------------------------------------------------
# D3 - Bounded readiness poll (2s interval, 30 attempts, ~60s cap).
# ----------------------------------------------------------------------------
wait_for_url() {
  local url="$1" attempts="${2:-30}" delay="${3:-2}"
  local i
  for ((i = 1; i <= attempts; i++)); do
    if curl -sS --max-time 5 -o /dev/null -w '%{http_code}' "$url" 2>/dev/null | grep -qE '^[0-9]+$'; then
      return 0
    fi
    sleep "$delay"
  done
  return 1
}

wait_backend() {
  # Same bound as wait_for_url (30 x 2s) but aborts the moment the backend
  # process dies, so a backend that fails on boot (Postgres down, wrong Java
  # major) surfaces in seconds instead of ~60s of polling a corpse.
  local i
  for ((i = 1; i <= 30; i++)); do
    if [ -n "$BACKEND_PID" ] && ! kill -0 "$BACKEND_PID" 2>/dev/null; then
      BACKEND_READY=0
      return 1
    fi
    if curl -sS --max-time 5 -o /dev/null -w '%{http_code}' "$API_BASE/api/status" 2>/dev/null | grep -qE '^[0-9]+$'; then
      BACKEND_READY=1
      return 0
    fi
    sleep 2
  done
  BACKEND_READY=0
  return 1
}

wait_frontend() {
  if wait_for_url "$FRONTEND_URL/"; then
    FRONTEND_READY=1
  else
    FRONTEND_READY=0
  fi
}

# ----------------------------------------------------------------------------
# D4/D5 - Process lifecycle: spawn with $! (PID capture) in a dedicated
# process group (`set -m`), tear down via the group so Vite preview's child
# node process is also reaped. Tolerates already-dead processes.
# ----------------------------------------------------------------------------
set -m

start_backend() {
  if [ -n "$BACKEND_PID" ] && kill -0 "$BACKEND_PID" 2>/dev/null; then
    return 0
  fi
  if [ ! -f "$JAR_PATH" ]; then
    echo "  [WARN] No se encontro $JAR_PATH - el backend no se inicio."
    return 1
  fi
  mkdir -p "$PROJECT_DIR/logs"
  # Pass DATABASE_PASSWORD as a -D system property so an empty trust-auth value
  # counts as PRESENT for RequiredEnvVarsGuard (parity with menu.ps1; harmless
  # on POSIX where empty env vars already export fine).
  ( cd "$PROJECT_DIR" && "$JAVA_EXE" -Xmx768m -Dfile.encoding=UTF-8 \
      "-DDATABASE_PASSWORD=${DATABASE_PASSWORD:-}" -jar "$JAR_PATH" \
      > "$PROJECT_DIR/logs/backend.out.log" 2>&1 ) &
  BACKEND_PID=$!
}

start_frontend() {
  if [ -n "$FRONTEND_PID" ] && kill -0 "$FRONTEND_PID" 2>/dev/null; then
    return 0
  fi
  if [ ! -f "$FRONTEND_DIR/package.json" ]; then
    echo "  [WARN] No se encontro frontend/package.json - el frontend no se inicio."
    return 1
  fi
  # D4: pin the preview port and require it exactly (--strictPort) so a
  # port clash fails loudly instead of silently falling back to Vite's
  # default 4173 (the RED case in tasks 2.6).
  ( cd "$FRONTEND_DIR" && npm run preview -- --port "$FRONTEND_PORT" --strictPort ) &
  FRONTEND_PID=$!
}

stop_tracked_pid() {
  local pid="$1" label="$2"
  if [ -z "$pid" ]; then
    return 0
  fi
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "  $label ya estaba detenido."
    return 0
  fi
  # Kill the whole process group rooted at $pid so a spawned child (e.g.
  # Vite preview's node child) doesn't survive as an orphan.
  kill -TERM "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null
  sleep 1
  if kill -0 "$pid" 2>/dev/null; then
    kill -KILL "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null
  fi
  echo "  $label detenido (PID $pid)."
}

stop_services() {
  stop_tracked_pid "$FRONTEND_PID" "Frontend"
  stop_tracked_pid "$BACKEND_PID" "Backend"
}

# ----------------------------------------------------------------------------
# Menu actions - pure REST client, honest surfacing of no-op/conflict
# responses (spec "Honest surfacing of idempotent/no-op API responses").
# ----------------------------------------------------------------------------
show_status() {
  local resp
  resp="$(curl -sS --max-time 5 "$API_BASE/api/status" 2>/dev/null)" || {
    echo "  [ERROR] No se pudo consultar /api/status"
    return 1
  }
  echo ""
  echo "  Estado     : $(echo "$resp" | "$JQ_BIN" -r '.status')"
  echo "  Mensaje    : $(echo "$resp" | "$JQ_BIN" -r '.mensaje')"
  echo "  Tiene data : $(echo "$resp" | "$JQ_BIN" -r '.tieneData')  Total: $(echo "$resp" | "$JQ_BIN" -r '.total')"
  LAST_STATUS="$resp"
}

invoke_scrape() {
  show_status
  local current_status
  current_status="$(echo "${LAST_STATUS:-}" | "$JQ_BIN" -r '.status // empty' 2>/dev/null)"
  if [ "$current_status" = "RUNNING" ]; then
    echo "  Ya hay un scraping en curso - no se ofrece lanzar otro."
    return 0
  fi
  read -r -p "  precioMin (enter para default): " precio_min
  read -r -p "  precioMax (enter para default): " precio_max
  read -r -p "  sitios separados por coma (enter para todos): " sitios_raw
  read -r -p "  forceRetrain? (s/N): " force_retrain_raw

  local qs="" sep=""
  if [ -n "$precio_min" ]; then qs="${qs}${sep}precioMin=$(printf '%s' "$precio_min" | "$JQ_BIN" -sRr @uri 2>/dev/null || printf '%s' "$precio_min")"; sep="&"; fi
  if [ -n "$precio_max" ]; then qs="${qs}${sep}precioMax=$(printf '%s' "$precio_max" | "$JQ_BIN" -sRr @uri 2>/dev/null || printf '%s' "$precio_max")"; sep="&"; fi
  if [ -n "$sitios_raw" ]; then
    IFS=',' read -ra sitios_arr <<< "$sitios_raw"
    for s in "${sitios_arr[@]}"; do
      s="$(echo "$s" | sed 's/^ *//;s/ *$//')"
      [ -n "$s" ] && { qs="${qs}${sep}sitios=$s"; sep="&"; }
    done
  fi
  case "$force_retrain_raw" in
    s|S|si|SI|y|Y|yes|YES) qs="${qs}${sep}forceRetrain=true" ;;
  esac

  local resp
  resp="$(curl -sS --max-time 10 -X POST "$API_BASE/api/scrape?${qs}" 2>/dev/null)" || {
    echo "  [ERROR] POST /api/scrape fallo"
    return 1
  }
  local iniciado mensaje
  iniciado="$(echo "$resp" | "$JQ_BIN" -r '.iniciado')"
  mensaje="$(echo "$resp" | "$JQ_BIN" -r '.mensaje')"
  if [ "$iniciado" = "true" ]; then
    echo "  Scraping iniciado: $mensaje"
  else
    echo "  No se inicio un nuevo scraping: $mensaje"
  fi
}

invoke_retrain() {
  local http_code body
  body="$(mktemp)"
  http_code="$(curl -sS --max-time 10 -X POST -o "$body" -w '%{http_code}' "$API_BASE/api/ml/entrenar" 2>/dev/null)"
  case "$http_code" in
    200) echo "  Entrenamiento iniciado (200)." ;;
    400|409) echo "  Entrenamiento ya en curso - no se inicio otro." ;;
    *) echo "  [ERROR] POST /api/ml/entrenar fallo (HTTP $http_code)" ;;
  esac
  rm -f "$body"
}

get_sitios() {
  local resp
  resp="$(curl -sS --max-time 5 "$API_BASE/api/sitios" 2>/dev/null)" || {
    echo "  [ERROR] GET /api/sitios fallo"
    return 1
  }
  echo ""
  echo "  -- Sitios base --"
  echo "$resp" | "$JQ_BIN" -r '.base[] | "  \(.nombre)  \(.url)"'
  echo "  -- Sitios extra --"
  echo "$resp" | "$JQ_BIN" -r '.extras[] | "  \(.nombre)  \(.url)  (\(.plataforma))"'
}

add_sitio() {
  read -r -p "  nombre: " nombre
  read -r -p "  url: " url
  read -r -p "  plataforma (enter = tiendanube): " plataforma
  plataforma="${plataforma:-tiendanube}"
  local json resp
  json="$(build_site_json "$nombre" "$url" "$plataforma")"
  resp="$(curl -sS --max-time 10 -X POST -H 'Content-Type: application/json' -d "$json" "$API_BASE/api/sitios" 2>/dev/null)" || {
    echo "  [ERROR] POST /api/sitios fallo"
    return 1
  }
  local ok mensaje
  ok="$(echo "$resp" | "$JQ_BIN" -r '.ok')"
  mensaje="$(echo "$resp" | "$JQ_BIN" -r '.mensaje')"
  if [ "$ok" = "true" ]; then
    echo "  $mensaje"
  else
    echo "  No se agrego: $mensaje"
  fi
}

remove_sitio() {
  read -r -p "  nombre a eliminar: " nombre
  local nombre_enc resp
  # URL-encode the path segment (mirrors menu.ps1's [uri]::EscapeDataString)
  # so names with spaces/special chars resolve to the right site.
  nombre_enc="$(printf '%s' "$nombre" | "$JQ_BIN" -sRr @uri)"
  resp="$(curl -sS --max-time 10 -X DELETE "$API_BASE/api/sitios/$nombre_enc" 2>/dev/null)" || {
    echo "  [ERROR] DELETE /api/sitios fallo"
    return 1
  }
  echo "  $(echo "$resp" | "$JQ_BIN" -r '.mensaje')"
}

open_dashboard() {
  if [ "$FRONTEND_READY" -ne 1 ]; then
    echo "  [WARN] El frontend todavia no respondio en $FRONTEND_URL."
  fi
  if command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$FRONTEND_URL" >/dev/null 2>&1 &
  elif command -v open >/dev/null 2>&1; then
    open "$FRONTEND_URL" >/dev/null 2>&1 &
  else
    echo "  Abri manualmente: $FRONTEND_URL"
  fi
}

invoke_selftest() {
  echo "  --selftest: validando contrato de API contra $API_BASE ..."
  local ok=0
  local status_resp sitios_resp
  status_resp="$(curl -sS --max-time 5 "$API_BASE/api/status" 2>/dev/null)"
  if [ -z "$status_resp" ]; then
    echo "  [DRIFT] GET /api/status fallo"
    ok=1
  else
    for key in status mensaje tieneData; do
      if ! echo "$status_resp" | "$JQ_BIN" -e "has(\"$key\")" >/dev/null 2>&1; then
        echo "  [DRIFT] /api/status no tiene el campo '$key'"
        ok=1
      fi
    done
  fi
  sitios_resp="$(curl -sS --max-time 5 "$API_BASE/api/sitios" 2>/dev/null)"
  if [ -z "$sitios_resp" ]; then
    echo "  [DRIFT] GET /api/sitios fallo"
    ok=1
  else
    for key in base extras; do
      if ! echo "$sitios_resp" | "$JQ_BIN" -e "has(\"$key\")" >/dev/null 2>&1; then
        echo "  [DRIFT] /api/sitios no tiene el campo '$key'"
        ok=1
      fi
    done
  fi
  if [ "$ok" -eq 0 ]; then
    echo "  Contrato OK - sin drift detectado."
  fi
  return "$ok"
}

# ----------------------------------------------------------------------------
# Main loop
# ----------------------------------------------------------------------------
show_menu() {
  local running="$1"
  echo ""
  echo "  ============================================================"
  echo "   FASHION SCRAPER - MENU"
  echo "  ============================================================"
  if [ "$BACKEND_READY" -ne 1 ]; then
    echo "  [!] Backend no disponible - acciones de API deshabilitadas."
  fi
  echo "  1) Ver estado"
  if [ "$running" = "1" ]; then
    echo "  2) Lanzar scraping        (deshabilitado - scraping en curso)"
    echo "  3) Reentrenar ML          (deshabilitado - scraping en curso)"
  else
    echo "  2) Lanzar scraping"
    echo "  3) Reentrenar ML"
  fi
  echo "  4) Listar sitios"
  echo "  5) Agregar sitio"
  echo "  6) Eliminar sitio"
  echo "  7) Abrir dashboard ($FRONTEND_URL)"
  echo "  8) Selftest (validar contrato de API)"
  echo "  Q) Salir"
  echo ""
}

main_loop() {
  echo "  Iniciando backend y frontend..."
  start_backend
  start_frontend

  echo "  Esperando a que el backend responda (hasta ~60s)..."
  wait_backend
  if [ "$BACKEND_READY" -ne 1 ]; then
    if [ -n "$BACKEND_PID" ] && ! kill -0 "$BACKEND_PID" 2>/dev/null; then
      echo "  [ERROR] El backend se cerro al arrancar."
      if [ -f "$PROJECT_DIR/logs/backend.out.log" ]; then
        echo "  Ultimas lineas del backend:"
        tail -n 8 "$PROJECT_DIR/logs/backend.out.log" | sed 's/^/    /'
      fi
      echo "  Causa tipica: PostgreSQL no esta corriendo. Corre Ejecutar_instalar.sh (arranca la DB, setea el entorno y compila) o levanta Postgres antes de usar menu.sh standalone."
    else
      echo "  [ERROR] El backend no respondio a tiempo. El menu se abre igual con acciones de API deshabilitadas."
    fi
  fi

  echo "  Esperando a que el frontend responda (hasta ~60s)..."
  wait_frontend
  if [ "$FRONTEND_READY" -ne 1 ]; then
    echo "  [WARN] El frontend no respondio a tiempo en $FRONTEND_URL."
  fi

  local running=0
  while true; do
    if [ "$BACKEND_READY" -eq 1 ]; then
      show_status
      running=0
      [ "$(echo "${LAST_STATUS:-}" | "$JQ_BIN" -r '.status // empty' 2>/dev/null)" = "RUNNING" ] && running=1
    fi
    show_menu "$running"
    read -r -p "  Elegi una opcion: " choice
    case "$choice" in
      1) show_status ;;
      2) if [ "$running" -eq 1 ]; then echo "  Scraping en curso - accion deshabilitada."; else invoke_scrape; fi ;;
      3) if [ "$running" -eq 1 ]; then echo "  Scraping en curso - accion deshabilitada."; else invoke_retrain; fi ;;
      4) get_sitios ;;
      5) add_sitio ;;
      6) remove_sitio ;;
      7) open_dashboard ;;
      8) invoke_selftest ;;
      q|Q) break ;;
      *) echo "  Opcion invalida." ;;
    esac
  done
}

# ----------------------------------------------------------------------------
# Entry point - guarded so `source menu.sh` (tests/menu_test.sh) only loads
# functions without spawning processes or running the interactive loop.
# ----------------------------------------------------------------------------
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  # D5: teardown both tracked processes on quit AND Ctrl-C/TERM, tolerating
  # already-dead processes (spec "Lifecycle ownership and clean teardown").
  trap 'stop_services; exit 0' INT TERM
  trap 'stop_services' EXIT

  if [ "${1:-}" = "--selftest" ]; then
    BACKEND_READY=1
    invoke_selftest
    exit $?
  fi
  main_loop
fi
