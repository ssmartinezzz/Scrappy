#!/usr/bin/env bash
# Fashion Scraper Argentina — instalador/lanzador POSIX (Linux/macOS).
#
# decouple-services-postgres, Batch 4 (task 4.3): mirror of
# INSTALAR_Y_CORRER.bat's sequencing (Postgres up -> backend -> frontend),
# adapted to POSIX package-manager conventions instead of Windows portable
# zips — Linux/macOS users are expected to have (or be able to install via
# apt/brew) java21+, maven, python3, node, and postgresql-server tooling,
# unlike the zero-assumption Windows .bat which vendors everything under
# _tools/. This script provisions/generates the SAME gitignored .env the
# .bat writes, so both launchers are interchangeable for local dev.
#
# NOT executed end-to-end in the apply sandbox (Windows-only) — written and
# reviewed against the .bat's logic, but a real Linux/macOS smoke run is a
# follow-up (see apply-progress GATE-2/installer-smoke notes).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$ROOT/scraper"
FRONTEND_DIR="$ROOT/frontend"
ENV_FILE="$ROOT/.env"

PG_PORT="${PG_PORT:-5432}"
PG_DB="${PG_DB:-scraper}"
PG_USER="${PG_USER:-postgres}"
PG_DATA="${PG_DATA:-$ROOT/_tools/pgdata}"

echo "============================================================"
echo " FASHION SCRAPER ARGENTINA (POSIX)"
echo "============================================================"
echo "Raiz    : $ROOT"
echo "Proyecto: $PROJECT"
echo

# ── [1/6] Internet ───────────────────────────────────────────────────────
echo "[1/6] Verificando internet..."
if ! curl -fsS --max-time 5 https://www.google.com -o /dev/null; then
  echo "  [ERROR] Sin internet." >&2
  exit 1
fi
echo "       OK"
echo

# ── [2/6] Toolchain (java21+, maven, python3, node) ─────────────────────
echo "[2/6] Verificando toolchain (java21+/maven/python3/node)..."
require() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "  [ERROR] Falta '$1' en PATH. Instalalo con tu gestor de paquetes" >&2
    echo "          (ej: apt install $1 / brew install $1) y reintenta." >&2
    exit 1
  }
}
require java
require mvn
require python3
require node
JAVA_MAJOR="$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
if [ "${JAVA_MAJOR:-0}" -lt 21 ]; then
  echo "  [ERROR] Se requiere Java 21+, encontrado: $JAVA_MAJOR" >&2
  exit 1
fi
echo "       OK"
echo

# ── [3/6] PostgreSQL (system package or already-running instance) ───────
echo "[3/6] PostgreSQL..."
if command -v pg_ctl >/dev/null 2>&1 && command -v initdb >/dev/null 2>&1; then
  if [ ! -f "$PG_DATA/PG_VERSION" ]; then
    echo "       Inicializando data directory en $PG_DATA..."
    mkdir -p "$PG_DATA"
    initdb -D "$PG_DATA" -U "$PG_USER" -A trust --locale=C -E UTF8 >/dev/null
  fi
  if ! pg_ctl -D "$PG_DATA" status >/dev/null 2>&1; then
    echo "       Iniciando servidor en 127.0.0.1:$PG_PORT..."
    pg_ctl -D "$PG_DATA" \
      -o "-p $PG_PORT -c listen_addresses=127.0.0.1 -c unix_socket_directories=" \
      -l "$ROOT/_tools/pgserver.log" -w start
  else
    echo "       Servidor ya esta corriendo."
  fi
  if ! psql -h 127.0.0.1 -p "$PG_PORT" -U "$PG_USER" -tAc \
      "SELECT 1 FROM pg_database WHERE datname='$PG_DB'" | grep -q 1; then
    echo "       Creando base de datos '$PG_DB'..."
    createdb -h 127.0.0.1 -p "$PG_PORT" -U "$PG_USER" "$PG_DB"
  fi
  echo "       PostgreSQL listo en 127.0.0.1:$PG_PORT/$PG_DB."
elif command -v docker >/dev/null 2>&1; then
  echo "       No hay initdb/pg_ctl del sistema — usando un contenedor Docker."
  if ! docker ps --format '{{.Names}}' | grep -q '^fashion-scraper-pg$'; then
    docker run -d --name fashion-scraper-pg \
      -e POSTGRES_USER="$PG_USER" -e POSTGRES_DB="$PG_DB" -e POSTGRES_HOST_AUTH_METHOD=trust \
      -p "$PG_PORT:5432" postgres:16-alpine >/dev/null
    echo "       Esperando a que Postgres levante..."
    sleep 3
  else
    echo "       Contenedor 'fashion-scraper-pg' ya esta corriendo."
  fi
else
  echo "  [ERROR] No se encontro postgresql-server (initdb/pg_ctl) ni Docker." >&2
  echo "          Instala postgresql (apt/brew) o Docker, o apunta DATABASE_URL" >&2
  echo "          a un Postgres externo ya configurado y saltea este paso." >&2
  exit 1
fi
echo

# ── [4/6] Frontend build ─────────────────────────────────────────────────
echo "[4/6] Frontend React/Vite..."
[ -f "$FRONTEND_DIR/package.json" ] || { echo "  [ERROR] Falta frontend/package.json" >&2; exit 1; }
if [ ! -f "$FRONTEND_DIR/.env" ] && [ -f "$FRONTEND_DIR/.env.example" ]; then
  cp "$FRONTEND_DIR/.env.example" "$FRONTEND_DIR/.env"
fi
(
  cd "$FRONTEND_DIR"
  [ -d node_modules ] || npm install --prefer-offline
  npm run build
)
echo "       Frontend compilado OK."
echo

# ── [5/6] Backend build ──────────────────────────────────────────────────
echo "[5/6] Compilando backend Java..."
JAR="$PROJECT/scraper.jar"
if [ ! -f "$JAR" ]; then
  ( cd "$PROJECT" && mvn clean package -DskipTests --batch-mode )
  cp "$PROJECT/target/fashion-scraper-1.0.0.jar" "$JAR"
fi
echo "       Backend listo."
echo

# ── Generar .env (gitignored) — mismo contrato que INSTALAR_Y_CORRER.bat ─
export SCRAPER_MODELS_ROOT="$PROJECT/_models"
export DATABASE_URL="jdbc:postgresql://127.0.0.1:$PG_PORT/$PG_DB"
export DATABASE_USERNAME="$PG_USER"
export DATABASE_PASSWORD=""
export APP_CORS_ALLOWED_ORIGINS="http://localhost:5173"
export VITE_API_BASE_URL="http://localhost:3000"
export APP_OPEN_URL="http://localhost:3000"
cat > "$ENV_FILE" <<EOF
DATABASE_URL=$DATABASE_URL
DATABASE_USERNAME=$DATABASE_USERNAME
DATABASE_PASSWORD=$DATABASE_PASSWORD
SCRAPER_MODELS_ROOT=$SCRAPER_MODELS_ROOT
APP_CORS_ALLOWED_ORIGINS=$APP_CORS_ALLOWED_ORIGINS
VITE_API_BASE_URL=$VITE_API_BASE_URL
APP_OPEN_URL=$APP_OPEN_URL
EOF
echo "       .env generado en $ENV_FILE (gitignored)"
echo

# ── [6/6] menu.sh dependencies: jq (required) + gum (optional UI veneer) ──
# interactive-cli-launcher design D6: vendored under _tools/ so menu.sh's
# safe JSON body building (jq -n --arg) never depends on a system package.
# gum is a nicer-menu fast-follow — menu.sh falls back to plain bash
# prompts when it isn't present, so a failed/skipped download here is
# non-fatal.
echo "[6/6] Verificando dependencias de menu.sh (jq/gum)..."
TOOLS_DIR="$ROOT/_tools"
JQ_DIR="$TOOLS_DIR/jq"
GUM_DIR="$TOOLS_DIR/gum"
mkdir -p "$JQ_DIR" "$GUM_DIR"

detect_os_arch() {
  local os arch
  os="$(uname -s)"
  arch="$(uname -m)"
  case "$os" in
    Linux) OS_TAG="linux" ;;
    Darwin) OS_TAG="macos" ;;
    *) OS_TAG="" ;;
  esac
  case "$arch" in
    x86_64|amd64) ARCH_TAG="amd64" ;;
    arm64|aarch64) ARCH_TAG="arm64" ;;
    *) ARCH_TAG="" ;;
  esac
}
detect_os_arch

if [ -x "$JQ_DIR/jq" ]; then
  echo "       jq ya vendorizado en $JQ_DIR/jq"
elif command -v jq >/dev/null 2>&1; then
  echo "       jq del sistema encontrado — no se vendoriza."
elif [ -n "$OS_TAG" ]; then
  JQ_URL="https://github.com/jqlang/jq/releases/latest/download/jq-${OS_TAG}-${ARCH_TAG}"
  echo "       Descargando jq ($OS_TAG/$ARCH_TAG)..."
  if curl -fsSL --max-time 30 -o "$JQ_DIR/jq" "$JQ_URL"; then
    chmod +x "$JQ_DIR/jq"
    echo "       jq vendorizado en $JQ_DIR/jq"
  else
    echo "  [WARN] No se pudo descargar jq. menu.sh requiere jq en PATH para" >&2
    echo "         agregar/eliminar sitios de forma segura." >&2
  fi
else
  echo "  [WARN] SO/arquitectura no reconocidos ($(uname -s)/$(uname -m)) —" >&2
  echo "         instala jq manualmente (apt/brew install jq)." >&2
fi

if [ -x "$GUM_DIR/gum" ] || command -v gum >/dev/null 2>&1; then
  echo "       gum disponible (o ya vendorizado)."
else
  echo "       gum no encontrado — menu.sh usara prompts de bash simples (opcional)."
fi
echo

echo "============================================================"
echo " FASHION SCRAPER - SERVIDOR LISTO"
echo "============================================================"
echo "  API   : http://localhost:3000  (API-only — backend ya no sirve la SPA)"
echo "  Panel : http://localhost:5173  (se abre desde el menu interactivo)"
echo "  DB    : PostgreSQL 127.0.0.1:$PG_PORT/$PG_DB"
echo "  Salir : opcion Q del menu, o Ctrl+C"
echo "============================================================"
echo

# ── interactive-cli-launcher (design D7) ──────────────────────────────────
# menu.sh owns the lifecycle of both processes started here: backend
# (background) and frontend (npm run preview --strictPort :5173). Pure
# REST client of the existing API — no new backend endpoints. Standalone/
# re-invocable: running "bash menu.sh" directly spawns both itself.
mkdir -p "$PROJECT/logs"
cd "$PROJECT"
java -Xmx768m -Dfile.encoding=UTF-8 -jar "$JAR" > "$PROJECT/logs/backend.out.log" 2>&1 &
JAVA_PID=$!

cd "$ROOT/frontend"
npm run preview -- --port 5173 --strictPort &
VITE_PID=$!

cd "$ROOT"
ROOT="$ROOT" PROJECT="$PROJECT" JAR="$JAR" FRONTEND_DIR="$ROOT/frontend" \
  JAVA_PID="$JAVA_PID" VITE_PID="$VITE_PID" bash "$ROOT/menu.sh"
