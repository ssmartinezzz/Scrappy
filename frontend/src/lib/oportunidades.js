import { RUBRO_KEYS } from './rubros';

/**
 * Categories that never are an "oportunidad": the catch-all and food.
 * The single owner of the exclusion; the panel and its drill-down both send it as
 * `excluirCategoria`, so a count and the list behind it cannot disagree.
 */
export const EXCLUIR_CATEGORIAS = ['Otros', 'Alimentos'];

export const RUTA_OPORTUNIDADES = '/analisis/oportunidades';

/** The `?rubro=` of the URL, or '' (Todos) when absent or outside the rubro vocabulary. */
export function rubroDeQuery(searchParams) {
  const rubro = searchParams.get('rubro') || '';
  return RUBRO_KEYS.includes(rubro) ? rubro : '';
}

/** The panel (no badge) or a badge drill-down, both carrying the selected rubro. */
export function rutaOportunidades(rubro, badge) {
  const base = badge ? `${RUTA_OPORTUNIDADES}/${badge}` : RUTA_OPORTUNIDADES;
  return rubro ? `${base}?rubro=${encodeURIComponent(rubro)}` : base;
}

const normalizar = t => (t || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/\s+/g, ' ').trim();

/**
 * One card per (nombre, sitio): the catalog can hold one product under several urls. Keeps the first
 * in server order, so the ranking decides which url survives, and stops at `max`.
 */
export function unicosPorNombreYSitio(productos, max) {
  const vistos = new Set();
  const out = [];
  for (const p of productos || []) {
    const clave = `${normalizar(p.nombre)}|${normalizar(p.sitio)}`;
    if (vistos.has(clave)) continue;
    vistos.add(clave);
    out.push(p);
    if (out.length === max) break;
  }
  return out;
}
