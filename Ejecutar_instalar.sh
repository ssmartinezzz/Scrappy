#!/usr/bin/env bash
# Fashion Scraper Argentina — instalador/lanzador POSIX (Linux/macOS).
#
# decouple-services-postgres, Batch 4 (task 4.3): mirror of
# INSTALAR_Y_CORRER.bat's sequencing (Postgres up -> toolchain), adapted to
# POSIX package-manager conventions instead of Windows portable zips —
# Linux/macOS users are expected to have (or be able to install via
# apt/brew) java21+, maven, python3, node, and postgresql-server tooling,
# unlike the zero-assumption Windows .bat which vendors everything under
# _tools/.
#
# native-cli-installer: this script now ONLY provisions the toolchain
# (java/mvn/python3/node checks, PostgreSQL, uv + _tools/cli-venv) and
# hands off to the native CLI (cli/, invoked as `-m cli` on the
# uv-provisioned venv), which owns build, .env generation (no longer
# written here), and backend+frontend orchestration. menu.sh was retired.
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

# ── [1/4] Internet ───────────────────────────────────────────────────────
echo "[1/4] Verificando internet..."
if ! curl -fsS --max-time 5 https://www.google.com -o /dev/null; then
  echo "  [ERROR] Sin internet." >&2
  exit 1
fi
echo "       OK"
echo

# ── [2/4] Toolchain (java21+, maven, python3, node) ─────────────────────
echo "[2/4] Verificando toolchain (java21+/maven/python3/node)..."
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

# ── [3/4] PostgreSQL (system package or already-running instance) ───────
echo "[3/4] PostgreSQL..."
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

# ── native-cli-installer (design.md invariant, §9): this script no longer
# runs `npm install`/`npm run build`, `mvn clean package`, or writes .env —
# those, plus the critical "VITE_API_BASE_URL must be a REAL env var
# BEFORE `npm run build`" ordering hazard (Vite never reads .env at build
# time), now live in cli/core/builder.py / cli/core/env_file.py. This
# installer only provisions the toolchain the CLI's build step will use
# (java/mvn/python3/node above, uv + _tools/cli-venv below) and hands off.

# ── [4/4] uv + _tools/cli-venv (native-cli-installer, ADR-002 / design §7.1) ─
# uv-managed standalone CPython (pinned 3.11.x) — NOT the system python3
# checked above and NOT the ML embeddable the .bat provisions on Windows.
# The CLI never imports ML libraries, so this venv is fully isolated and
# reproducible regardless of whatever python3 the distro ships (design
# §7.2/§7.3). Fully vendored under _tools/uv/ — never touches a global/user
# uv install, cache, or Python installation.
echo "[4/4] uv + entorno Python del CLI nativo (_tools/cli-venv)..."
UV_DIR="$ROOT/_tools/uv"
UV_BIN="$UV_DIR/uv"
UV_VERSION="0.11.32"
UV_PY_VER="3.11.9"
CLI_VENV="$ROOT/_tools/cli-venv"
CLI_VENV_PY="$CLI_VENV/bin/python"
export UV_PYTHON_INSTALL_DIR="$UV_DIR/python"
export UV_CACHE_DIR="$UV_DIR/cache"
mkdir -p "$UV_DIR"

if [ -x "$UV_BIN" ]; then
  echo "       uv ya instalado."
else
  UV_ARCH=""
  case "$(uname -m)" in
    x86_64|amd64) UV_ARCH="x86_64-unknown-linux-gnu" ;;
    aarch64|arm64) UV_ARCH="aarch64-unknown-linux-gnu" ;;
  esac
  if [ -z "$UV_ARCH" ]; then
    echo "  [ERROR] Arquitectura no reconocida ($(uname -m)) para descargar uv." >&2
    echo "          El CLI nativo requiere Python/uv — instalacion abortada." >&2
    exit 1
  fi
  echo "       Descargando uv ($UV_ARCH)..."
  UV_TARBALL="$UV_DIR/uv.tar.gz"
  if ! curl -fsSL --max-time 60 -o "$UV_TARBALL" \
      "https://github.com/astral-sh/uv/releases/download/${UV_VERSION}/uv-${UV_ARCH}.tar.gz"; then
    echo "  [ERROR] Descarga de uv fallo. El CLI nativo requiere Python/uv —" >&2
    echo "          instalacion abortada." >&2
    exit 1
  fi
  tar -xzf "$UV_TARBALL" -C "$UV_DIR"
  mv "$UV_DIR/uv-${UV_ARCH}/uv" "$UV_BIN"
  rm -rf "$UV_DIR/uv-${UV_ARCH}" "$UV_TARBALL"
  chmod +x "$UV_BIN"
  if [ ! -x "$UV_BIN" ]; then
    echo "  [ERROR] Extraccion de uv fallo. Instalacion abortada." >&2
    exit 1
  fi
  echo "       uv listo."
fi

if [ -x "$CLI_VENV_PY" ] && "$CLI_VENV_PY" -c "import textual" >/dev/null 2>&1; then
  echo "       _tools/cli-venv ya provisionado (uv-managed CPython $UV_PY_VER)."
else
  echo "       Instalando CPython $UV_PY_VER administrado por uv..."
  if ! "$UV_BIN" python install "$UV_PY_VER"; then
    echo "  [ERROR] 'uv python install $UV_PY_VER' fallo. El CLI nativo requiere" >&2
    echo "          Python — instalacion abortada. Verifica conexion a internet y reintenta." >&2
    exit 1
  fi
  echo "       Creando _tools/cli-venv..."
  if ! "$UV_BIN" venv --managed-python --python "$UV_PY_VER" "$CLI_VENV"; then
    echo "  [ERROR] 'uv venv' fallo al crear _tools/cli-venv. Instalacion abortada." >&2
    exit 1
  fi
  echo "       Instalando dependencias del CLI (textual — ver cli/requirements.txt)..."
  if ! "$UV_BIN" pip install --python "$CLI_VENV_PY" -r "$ROOT/cli/requirements.txt"; then
    echo "  [ERROR] 'uv pip install' fallo instalando cli/requirements.txt. Instalacion abortada." >&2
    exit 1
  fi
fi

# Python es ahora load-bearing en Linux tambien (spec installer-provisioning,
# "Python Load-Bearing" — alinea Linux con el hard-fail que este script ya
# aplicaba a `require python3`, ahora extendido al CLI venv que reemplaza
# a menu.sh).
if ! "$CLI_VENV_PY" -c "import textual" >/dev/null 2>&1; then
  echo "  [ERROR] '_tools/cli-venv' no tiene 'textual' instalado tras el" >&2
  echo "          aprovisionamiento. El CLI nativo no puede arrancar sin el. Revisa" >&2
  echo "          la salida de 'uv pip install' arriba, o borra _tools/uv y" >&2
  echo "          _tools/cli-venv y reintenta." >&2
  exit 1
fi
echo "       _tools/cli-venv listo (Python $UV_PY_VER administrado por uv, 'import textual' OK)."
echo

echo "============================================================"
echo " FASHION SCRAPER - TOOLCHAIN LISTO"
echo "============================================================"
echo "  DB  : PostgreSQL 127.0.0.1:$PG_PORT/$PG_DB"
echo "  A continuacion: el CLI nativo compila (build), genera .env y arranca"
echo "  backend (:3000) + frontend (:5173) desde su propio menu."
echo "  Salir : opcion Q del CLI, o Ctrl+C"
echo "============================================================"
echo

# ── native-cli-installer (ADR-004): menu.sh was retired — the native CLI
# (cli/, run on the just-provisioned _tools/cli-venv) now owns build, .env
# generation, and backend+frontend lifecycle. Invoked with `-m cli`
# (module form), NOT `cli/__main__.py` directly: the CLI's modules use
# absolute `cli.*` imports, so it must run as a package with $ROOT as cwd
# (verified empirically — running the .py file path directly raises
# ModuleNotFoundError: No module named 'cli'). Standalone/re-invocable:
# running "_tools/cli-venv/bin/python -m cli" from $ROOT works the same way.
mkdir -p "$PROJECT/logs"
cd "$ROOT"
exec "$CLI_VENV_PY" -m cli
