#!/usr/bin/env bash
#
#   ./rafaga.sh <jar> <etiqueta> [VAR=valor ...]    una ráfaga contra Mailpit
#
# Levanta <jar>, encola OUTBOX_BAJAS bajas (1000) de OUTBOX_USUARIOS usuarios (200) con UNA
# llamada a sp_upsert_run y espera a que la cola se vacíe. Las VAR=valor se pasan al backend
# (NOTIFICACIONES_INTERVALO_MS=1000, NOTIFICACIONES_LOTE=50, NOTIFICACIONES_MAX_POR_MINUTO=60...).
# Imprime pared, mails/s, filas/s y p50/p95/p99/max de enviada_at - creada_at.
# Reutiliza la base si ya existe (OUTBOX_DB); rafaga-abba.sh la crea y la borra.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

JAR="${1:?uso: $0 <jar> <etiqueta> [VAR=valor ...]}"; ETIQUETA="${2:?etiqueta}"; shift 2
BAJAS="${OUTBOX_BAJAS:-1000}"; USUARIOS="${OUTBOX_USUARIOS:-200}"; ESPERA="${OUTBOX_ESPERA_S:-300}"
ARGS=(-v tag="$ETIQUETA" -v productos="$BAJAS" -v usuarios="$USUARIOS")

jar_arrancar "$JAR" "$OUTBOX_WORK/boot-$ETIQUETA.log" "$@"
trap jar_parar EXIT
pg "${ARGS[@]}" -f "$AQUI/seed.sql" >/dev/null
pg -c "TRUNCATE notificacion_precio" ; mailpit_vaciar
pg "${ARGS[@]}" -v pct=10 -f "$AQUI/drop.sql" >/dev/null

for _ in $(seq 1 "$ESPERA"); do
  [[ "$(pg -tA -c "SELECT count(*) FROM notificacion_precio WHERE estado = 'PENDIENTE'")" = 0 ]] && break
  sleep 1
  if [[ -n "${OUTBOX_MIRAR_PRIMER_MINUTO:-}" && "$SECONDS" -ge 60 ]]; then break; fi
done
echo "== $ETIQUETA ($*)"
pg -f "$AQUI/stats.sql"
ENVIADAS="$(pg -tA -c "SELECT count(*) FROM notificacion_precio WHERE estado = 'ENVIADA'")"
PARED="$(pg -tA -c "SELECT extract(epoch FROM (max(enviada_at) - min(creada_at))) FROM notificacion_precio WHERE estado = 'ENVIADA'")"
python3 - "$ENVIADAS" "$PARED" "$(mailpit_total)" <<'PY'
import sys
filas, pared, mails = int(sys.argv[1]), float(sys.argv[2] or 0), int(sys.argv[3])
print(f"filas ENVIADA={filas}  mails en Mailpit={mails}  pared={pared:.2f}s  "
      f"mails/s={mails / pared if pared else 0:.1f}  filas/s={filas / pared if pared else 0:.1f}")
PY
