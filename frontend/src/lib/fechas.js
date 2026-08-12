// normalize-db-schema-fks-1nf, slice A.4.
//
// One place that knows how a timestamp arrives from the API. Before V8 the
// backend stored these columns as TEXT and every consumer hardcoded whatever
// shape it happened to see: cron fields were parsed assuming they CONTAIN a
// "T" (`.replace('T', ' ')`), saved outfits assuming they contain a SPACE
// (`.replace(' ', 'T')`). Two formats, two assumptions, and each site rendered
// garbage the moment it met the other one's value.
//
// Since V8 every timestamp column is TIMESTAMPTZ and the backend renders UTC
// ISO-8601 to the second ("2026-08-11T20:15:00Z"), which `new Date(...)`
// parses natively. The legacy space-separated shape is still accepted on the
// way in: a page open across the migration keeps holding older payloads, and
// "Invalid Date" is a worse answer than parsing what we understand.

const SIN_DATO = '—';

/** Date, o null si el valor está vacío o no es una fecha. Nunca Invalid Date. */
export function parseFechaApi(valor) {
  if (!valor) return null;
  const texto = String(valor).trim();
  if (!texto || texto === SIN_DATO) return null;
  // "2026-08-11 20:15:00" (legacy, sin offset) -> ISO local.
  const normalizado = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}/.test(texto)
    ? texto.replace(' ', 'T')
    : texto;
  const fecha = new Date(normalizado);
  return Number.isNaN(fecha.getTime()) ? null : fecha;
}

/** dd/mm/aaaa en hora local, o "—". */
export function formatFecha(valor) {
  const fecha = parseFechaApi(valor);
  if (!fecha) return SIN_DATO;
  return fecha.toLocaleDateString('es-AR', { day: '2-digit', month: '2-digit', year: 'numeric' });
}

/** dd/mm/aaaa hh:mm en hora local, o "—". */
export function formatFechaHora(valor) {
  const fecha = parseFechaApi(valor);
  if (!fecha) return SIN_DATO;
  return `${formatFecha(valor)} ${fecha.toLocaleTimeString('es-AR', {
    hour: '2-digit', minute: '2-digit', hour12: false,
  })}`;
}
