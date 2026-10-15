#!/usr/bin/env bash
#
#   ./rafaga-abba.sh <jarA> <jarB> [VAR=valor ...]    A, B, B, A sobre la misma base
#
# Cada corrida usa una base NUEVA (si no, un jar con menos migraciones rechaza el esquema del otro).
# Cada jar vuelve a aplicar su propio R__sp_upsert_run al arrancar (Flyway repetible), así
# que cada lado encola con su propia versión de la función.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
[[ $# -ge 2 ]] || die "uso: $0 <jarA> <jarB> [VAR=valor ...]"
A="$1"; B="$2"; shift 2
trap 'db_borrar' EXIT
n=0
for lado in "A:$A" "B:$B" "B:$B" "A:$A"; do
  n=$((n + 1))
  db_borrar; db_crear   # base nueva por corrida: un jar viejo no valida el esquema que migró uno nuevo
  "$AQUI/rafaga.sh" "${lado#*:}" "${lado%%:*}$n" "$@"
done
