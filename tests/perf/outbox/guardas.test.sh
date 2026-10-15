#!/usr/bin/env bash
#
#   ./guardas.test.sh      las guardas de la carpeta: ninguna base que no sea perf_* se toca
#
# No es parte de ninguna suite (necesita un Postgres local con el usuario de lib.sh); se corre a mano
# después de tocar lib.sh o un .sql destructivo. Sin Postgres se saltea.
set -uo pipefail
AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PGHOST="${OUTBOX_PGHOST:-127.0.0.1}"; PGPORT="${OUTBOX_PGPORT:-5432}"; PGUSER="${OUTBOX_PGUSER:-postgres}"
fallos=0
ok()   { printf 'ok   %s\n' "$1"; }
falla() { printf 'FAIL %s\n' "$1"; fallos=$((fallos + 1)); }
admin() { psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" postgres -v ON_ERROR_STOP=1 -qtA "$@"; }
en() { local db="$1"; shift; psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" "$db" -v ON_ERROR_STOP=1 -qtA "$@"; }

# lib.sh se niega a cargar con una base que no empiece con perf_
for malo in scraper otra perfecto; do
  if OUTBOX_DB="$malo" bash -c ". '$AQUI/lib.sh'" >/dev/null 2>&1; then falla "lib.sh aceptó OUTBOX_DB='$malo'"; else ok "lib.sh rechaza OUTBOX_DB='$malo'"; fi
done
if OUTBOX_DB=perf_cualquiera bash -c ". '$AQUI/lib.sh'" >/dev/null 2>&1; then ok "lib.sh acepta perf_cualquiera"; else falla "lib.sh rechazó perf_cualquiera"; fi
# db_borrar no puede dropear otra base aunque se la llame directo. Con un psql falso: este test
# jamás le habla a un servidor real con un nombre que no sea suyo.
falso="$(mktemp -d)"; trap 'rm -rf "$falso"' EXIT
printf '#!/usr/bin/env bash\necho "$@" >> "%s/llamadas"\n' "$falso" > "$falso/psql"; chmod +x "$falso/psql"
PATH="$falso:$PATH" OUTBOX_DB=perf_x bash -c ". '$AQUI/lib.sh'; OUTBOX_DB=scraper; db_borrar" >/dev/null 2>&1; rc=$?
if [[ $rc -ne 0 && ! -s "$falso/llamadas" ]]; then ok "db_borrar rechaza scraper sin hablarle al servidor"; else falla "db_borrar aceptó scraper (rc=$rc)"; fi

if ! admin -c 'select 1' >/dev/null 2>&1; then
  echo "skip: sin Postgres en $PGHOST:$PGPORT, no se prueban las guardas SQL"; exit $((fallos > 0))
fi

sufijo="$$"; ajena="guarda_ajena_$sufijo"; perf="perf_guarda_$sufijo"
trap 'rm -rf "$falso"; admin -c "DROP DATABASE IF EXISTS \"$ajena\" WITH (FORCE)" >/dev/null 2>&1; admin -c "DROP DATABASE IF EXISTS \"$perf\" WITH (FORCE)" >/dev/null 2>&1' EXIT
admin -c "CREATE DATABASE \"$ajena\"" && admin -c "CREATE DATABASE \"$perf\"" || { echo "no pude crear las bases"; exit 1; }
en "$ajena" -c "CREATE TABLE notificacion_precio(x int); CREATE TABLE favoritos(x int)"
sembrar() { en "$ajena" -c "TRUNCATE notificacion_precio, favoritos; INSERT INTO notificacion_precio VALUES (1); INSERT INTO favoritos VALUES (1)"; }

for sql in payload.sql una_llamada.sql seed.sql purga-seed.sql; do
  sembrar
  en "$ajena" -f "$AQUI/$sql" -v productos=1 -v vigilados=1 -v usuarios=1 -v sin_vigilantes=1 -v tag=t -v filas=1 -v viejas=0 >/dev/null 2>&1
  restantes="$(en "$ajena" -c 'SELECT (SELECT count(*) FROM notificacion_precio) + (SELECT count(*) FROM favoritos)')"
  if [[ "$restantes" = 2 ]]; then ok "$sql no toca una base que no es perf_*"; else falla "$sql borró datos de una base que no es perf_* (quedan $restantes de 2 filas)"; fi
  if en "$ajena" -f "$AQUI/$sql" -v productos=1 -v vigilados=1 -v usuarios=1 -v sin_vigilantes=1 -v tag=t -v filas=1 -v viejas=0 >/dev/null 2>&1; then
    falla "$sql terminó con éxito contra una base que no es perf_*"; else ok "$sql falla contra una base que no es perf_*"; fi
done

en "$perf" -f "$AQUI/guarda.sql" >/dev/null 2>&1 && ok "guarda.sql pasa en una base perf_*" || falla "guarda.sql rechazó una base perf_*"
exit $((fallos > 0))
