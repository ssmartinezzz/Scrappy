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
