const TERMINAL = new Set(['done', 'error', 'skipped']);

/** Sites the run is finished with, out of the total. Follows the data: it can go down. */
export function sitiosTerminados(progreso) {
  const total = progreso?.total ?? 0;
  if (!(total > 0)) return { terminados: 0, total: 0, pct: 0 };
  const terminados = Array.isArray(progreso.sitios)
    ? progreso.sitios.filter(s => TERMINAL.has(s.estado)).length
    : progreso.completados ?? 0;
  return { terminados, total, pct: Math.min(100, (terminados / total) * 100) };
}
