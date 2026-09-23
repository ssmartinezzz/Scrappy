#!/usr/bin/env bash
#
# Crea la cuenta que usan las suites de performance y deja sus credenciales en
# tests/perf/.perf-credentials.env (gitignored, modo 600).
#
#   tests/perf/perf-user.sh                      # usa un admin del entorno
#   ADMIN_USERNAME=admin ADMIN_PASSWORD=... tests/perf/perf-user.sh
#
# La cuenta se crea por la API real (POST /api/usuarios), no con SQL: el punto
# de estas suites es ejercitar lo que se despliega, y una fila insertada por
# atrás no demuestra que el endpoint funcione.
#
# El username lleva un sufijo único por corrida. No es capricho: la API no
# expone cambiarle la password a otra cuenta —eso es deliberado— así que una
# cuenta fija sería irrecuperable el día que se pierda el archivo. Las cuentas
# viejas quedan desactivadas, que es lo que hace DELETE /api/usuarios/{u}.
#
# Rol VIEWER, no ADMIN: todos los endpoints que se miden son AUTHENTICATED.
# Una suite de carga no necesita poder borrar el catálogo.
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ARCHIVO="$AQUI/.perf-credentials.env"
HOST="${PERF_API_BASE_URL:-http://localhost:3000}"

die() { printf '\n\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }
say() { printf '\n\033[1;36m▸ %s\033[0m\n' "$*"; }

curl -sf -o /dev/null "$HOST/" || die "no hay backend en $HOST"

ADMIN_USERNAME="${ADMIN_USERNAME:-}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-}"
if [ -z "$ADMIN_USERNAME" ] && [ -f "$AQUI/../e2e/.e2e-secrets.env" ]; then
  # shellcheck disable=SC1090
  set -a; . "$AQUI/../e2e/.e2e-secrets.env"; set +a
  ADMIN_USERNAME="${ADMIN_BOOTSTRAP_USERNAME:-}"
  ADMIN_PASSWORD="${ADMIN_BOOTSTRAP_PASSWORD:-}"
fi
[ -n "$ADMIN_USERNAME" ] && [ -n "$ADMIN_PASSWORD" ] || die \
"hace falta una cuenta ADMIN para crear la de performance.

   ADMIN_USERNAME=<vos> ADMIN_PASSWORD=<tu password> $0

   No hay default: un default sería una password commiteada."

say "entrando como $ADMIN_USERNAME"
TOKEN=$(curl -s -X POST "$HOST/api/auth/login" -H 'Content-Type: application/json' \
  -d "$(printf '{"username":"%s","password":"%s"}' "$ADMIN_USERNAME" "$ADMIN_PASSWORD")" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("accessToken",""))')
[ -n "$TOKEN" ] || die "el login de $ADMIN_USERNAME no devolvió token — ¿password correcta?"

USUARIO="perf-$(head -c 6 /dev/urandom | od -An -tx1 | tr -d ' \n')"
PASSWORD="$(head -c 24 /dev/urandom | base64 | tr -d '\n=/+')"

say "creando $USUARIO (VIEWER)"
CODIGO=$(curl -s -o /tmp/perf-user-resp.$$ -w '%{http_code}' -X POST "$HOST/api/usuarios" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d "$(printf '{"username":"%s","password":"%s","role":"VIEWER"}' "$USUARIO" "$PASSWORD")")
if [ "$CODIGO" != "201" ]; then
  RESP=$(cat /tmp/perf-user-resp.$$); rm -f /tmp/perf-user-resp.$$
  die "POST /api/usuarios contestó $CODIGO: $RESP"
fi
rm -f /tmp/perf-user-resp.$$

umask 077
cat > "$ARCHIVO" <<EOF
# Generado por tests/perf/perf-user.sh el $(date -Iseconds). Gitignored.
# Cargalo con:  set -a; . tests/perf/.perf-credentials.env; set +a
PERF_USERNAME=$USUARIO
PERF_PASSWORD=$PASSWORD
EOF
chmod 600 "$ARCHIVO"

say "listo — credenciales en $ARCHIVO"
cat <<EOF

  set -a; . tests/perf/.perf-credentials.env; set +a

Y con eso corren las dos suites:

  cd tests/perf/locust && uv run pytest
  tests/perf/jmeter/run.sh smoke
EOF
