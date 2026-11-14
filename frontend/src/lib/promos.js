// Dueño único del texto de una promo de pago (la card y el panel de detalle son
// callers finos). El servidor ya ordena y elige la mejor (ResumenDePromos): acá
// sólo se formatea, nunca se re-deriva cuál es mejor ni se inventa un dato que
// no llegó (CODE-5: sin dato es silencio, no un valor por defecto).

// Cuántos iconos de medio entran en una card antes del "+N".
export const MEDIOS_VISIBLES = 4;

function pct(n) {
  return `${Number.isInteger(n) ? n : String(n).replace('.', ',')}%`;
}

// mejorPromo de una fila de /api/data: { medio, red, tipo, cuotas, pct }. El
// recargo nunca llega como mejor promo; si llegara, no se pinta.
export function textoPromoCorta(mejor) {
  if (!mejor?.medio) return '';
  const destino = mejor.red ? `${mejor.medio} ${mejor.red}` : mejor.medio;
  if (mejor.tipo === 'cuotas_sin_interes' && mejor.cuotas > 0) return `${mejor.cuotas} sin interés ${destino}`;
  if (mejor.tipo === 'descuento' && mejor.pct > 0) return `${pct(mejor.pct)} ${destino}`;
  return '';
}

// Promo completa del detalle (/api/producto/{key}).promos: el beneficio sin el medio.
export function textoPromoLarga(p) {
  if (p?.tipo === 'cuotas_sin_interes' && p.cuotas > 0) {
    return `${p.cuotas} ${p.cuotas === 1 ? 'cuota' : 'cuotas'} sin interés`;
  }
  if (p?.tipo === 'descuento' && p.pct > 0) return `${pct(p.pct)} de descuento`;
  if (p?.tipo === 'recargo' && p.pct > 0) return `${pct(p.pct)} de recargo`;
  return '';
}

// 'YYYY-MM-DD' -> 'd/m' sin pasar por Date (no hay corrimiento de huso).
function diaMes(iso) {
  const m = /^\d{4}-(\d{2})-(\d{2})$/.exec(iso ?? '');
  return m ? `${Number(m[2])}/${Number(m[1])}` : null;
}

export function vigenciaTexto(p) {
  const desde = diaMes(p?.vigenciaDesde);
  const hasta = diaMes(p?.vigenciaHasta);
  if (desde && hasta) return `Del ${desde} al ${hasta}`;
  if (hasta) return `Hasta el ${hasta}`;
  if (desde) return `Desde el ${desde}`;
  return null;
}
