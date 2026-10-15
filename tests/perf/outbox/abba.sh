#!/usr/bin/env bash
#
#   ./abba.sh A.sql B.sql [C.sql ...]     ABBA entre cuerpos de sp_upsert_run
#
#   OUTBOX_RONDAS=12   rondas (cada una = 2 llamadas por variante, 24 en total con 12)
#   OUTBOX_SIN_VIGILANTES=1   mismas bajas pero sin favoritos (sólo el costo del chequeo)
#
# Cada argumento es un R__sp_upsert_run.sql (p. ej. `git show master:...` o el del árbol).
# Se alternan con CREATE OR REPLACE sobre la MISMA base y el MISMO lote. Con tres o más
# variantes, cada ronda es un palíndromo (A B C C B A).
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ $# -ge 2 ]] || die "uso: $0 A.sql B.sql [C.sql ...]"
RONDAS="${OUTBOX_RONDAS:-12}"
SIN="${OUTBOX_SIN_VIGILANTES:-0}"
SALIDA="$OUTBOX_WORK/abba.out"; : > "$SALIDA"

db_crear
say "migraciones: levantando el jar una vez para crear el esquema"
jar_arrancar "$OUTBOX_JAR" "$OUTBOX_WORK/boot-esquema.log" NOTIFICACIONES_ENABLED=false
jar_parar

pg -f "$1" >/dev/null
pg -v productos="${OUTBOX_PRODUCTOS:-5000}" -v vigilados="${OUTBOX_VIGILADOS:-500}" -v usuarios="${OUTBOX_USUARIOS:-200}" \
   -f "$AQUI/payload.sql" >/dev/null

medir() {  # medir <indice> <archivo>
  local indice="$1" archivo="$2"
  pg -f "$archivo" >/dev/null
  pg -v sin_vigilantes="$SIN" -f "$AQUI/una_llamada.sql" | awk -v v="$indice" '/^RESULTADO/ {print v, $2, $3}'
}
VARIANTES=("$@")
for i in "${!VARIANTES[@]}"; do medir "$i" "${VARIANTES[$i]}" >/dev/null; done   # calentamiento
for _ in $(seq 1 "$RONDAS"); do
  for i in "${!VARIANTES[@]}"; do medir "$i" "${VARIANTES[$i]}" >> "$SALIDA"; done
  for ((i = ${#VARIANTES[@]} - 1; i >= 0; i--)); do medir "$i" "${VARIANTES[$i]}" >> "$SALIDA"; done
done
python3 "$AQUI/resumen.py" "$SALIDA" "${VARIANTES[@]}"
[[ "${OUTBOX_CONSERVAR_DB:-0}" = 1 ]] || db_borrar
