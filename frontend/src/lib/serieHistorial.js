// precio_historico writes a row only when the price changes, and `fecha` is a
// DATE: the series is a step function. A window that starts between two records
// opens at the price in force then, and every window closes today at the
// current price. All instants are UTC midnights so a date never shifts by the
// viewer's timezone.

const DIA = 86_400_000;

export const RANGOS = [
  { id: '1D', dias: 1 },
  { id: '1S', dias: 7 },
  { id: '1M', meses: 1 },
  { id: '3M', meses: 3 },
  { id: '6M', meses: 6 },
  { id: '1A', meses: 12 },
  { id: 'Todo' },
];

function aInstante(iso) {
  const [y, m, d] = String(iso).split('-').map(Number);
  return Date.UTC(y, m - 1, d);
}

function hoyLocal() {
  const h = new Date();
  return Date.UTC(h.getFullYear(), h.getMonth(), h.getDate());
}

function restarMeses(t, n) {
  const f = new Date(t);
  const y = f.getUTCFullYear();
  const m = f.getUTCMonth() - n;
  const ultimo = new Date(Date.UTC(y, m + 1, 0)).getUTCDate();
  return Date.UTC(y, m, Math.min(f.getUTCDate(), ultimo));
}

function inicioDeRango(rango, hasta) {
  if (rango?.dias) return hasta - rango.dias * DIA;
  if (rango?.meses) return restarMeses(hasta, rango.meses);
  return -Infinity;
}

// A price that held for six months must outweigh one that held for two days.
function promedioEnElTiempo(serie, valores) {
  let pesado = 0;
  for (let i = 0; i < serie.length - 1; i++) {
    pesado += serie[i].precio * ((serie[i + 1].t - serie[i].t) / DIA);
  }
  const dias = (serie.at(-1).t - serie[0].t) / DIA;
  return dias > 0 ? pesado / dias : valores.reduce((a, b) => a + b, 0) / valores.length;
}

export function serieEnRango(puntos, rangoId, hoyIso) {
  const todos = puntos.map(p => ({ t: aInstante(p.fecha), precio: p.precio, real: true }));
  const ultimo = todos.at(-1);
  const hasta = Math.max(hoyIso ? aInstante(hoyIso) : hoyLocal(), ultimo.t);
  const desde = Math.max(inicioDeRango(RANGOS.find(r => r.id === rangoId), hasta), todos[0].t);

  const previo = todos.findLast(p => p.t < desde);
  const enVentana = todos.filter(p => p.t >= desde && p.t <= hasta);
  const serie = [...enVentana];
  if (previo && (!serie.length || serie[0].t > desde)) {
    serie.unshift({ t: desde, precio: previo.precio, real: false });
  }

  const valores = serie.map(p => p.precio);
  if (serie.at(-1).t < hasta) serie.push({ t: hasta, precio: serie.at(-1).precio, real: false });

  const primero = valores[0];
  const final = valores.at(-1);
  return {
    desde,
    hasta,
    puntos: serie,
    stats: {
      min: Math.min(...valores),
      max: Math.max(...valores),
      avg: promedioEnElTiempo(serie, valores),
      deltaPct: primero > 0 ? Math.round((final - primero) / primero * 1000) / 10 || 0 : 0,
      registros: enVentana.length,
    },
  };
}

function pasoRedondo(crudo) {
  const magnitud = 10 ** Math.floor(Math.log10(crudo));
  return [1, 2, 5, 10].find(f => f * magnitud >= crudo) * magnitud;
}

/**
 * `referencia` (the category median) is pulled into the scale only when it sits
 * within one span of the series; farther out it would flatten the series.
 */
export function dominioPrecio(min, max, referencia) {
  const propio = max - min || max * 0.1 || 1;
  const incluyeReferencia = referencia > 0 && referencia >= min - propio && referencia <= max + propio;
  if (incluyeReferencia) {
    min = Math.min(min, referencia);
    max = Math.max(max, referencia);
  }
  const rango = max - min || max * 0.1 || 1;
  const paso = pasoRedondo((rango * 1.2) / 4);
  const bajo = Math.max(0, Math.floor((min - rango * 0.1) / paso) * paso);
  const alto = Math.ceil((max + rango * 0.1) / paso) * paso;
  const ticks = [];
  for (let v = bajo; v <= alto; v += paso) ticks.push(v);
  return { domain: [bajo, alto], ticks, incluyeReferencia };
}

export function ticksTiempo(desde, hasta) {
  const dias = (hasta - desde) / DIA;
  if (dias <= 2) return [desde, hasta];

  const crudo = dias / 6;
  if (crudo < 20) {
    const paso = [1, 2, 3, 7, 14].find(p => p >= crudo) ?? 14;
    const ticks = [];
    for (let t = desde; t <= hasta; t += paso * DIA) ticks.push(t);
    return ticks;
  }

  const meses = crudo / 30.44;
  const paso = [1, 2, 3, 6, 12].find(p => p >= meses) ?? Math.ceil(meses / 12) * 12;
  const f = new Date(desde);
  let t = Date.UTC(f.getUTCFullYear(), f.getUTCMonth() + (f.getUTCDate() > 1 ? 1 : 0), 1);
  const ticks = [];
  for (; t <= hasta; t = restarMeses(t, -paso)) ticks.push(t);
  return ticks;
}

/**
 * Median and quartiles of the product's category, from `distribucionCategorias`.
 * categoria_stats holds UNIT prices, so a pack is compared against as many units.
 */
export function contextoCategoria(distribucion, producto) {
  const s = distribucion?.[producto?.categoria];
  if (!s || s.n < 5) return null;
  const u = Math.max(1, producto.cantidadUnidades || 1);
  return { nombre: producto.categoria, n: s.n, mediana: s.median * u, q1: s.q1 * u, q3: s.q3 * u };
}

const MESES = ['ene', 'feb', 'mar', 'abr', 'may', 'jun', 'jul', 'ago', 'sep', 'oct', 'nov', 'dic'];

/** '10 oct' en ventanas cortas; 'oct 26' cuando la ventana pasa los 120 días. */
export function etiquetaTiempo(t, desde, hasta) {
  const f = new Date(t);
  const mes = MESES[f.getUTCMonth()];
  if (hasta - desde > 120 * DIA) return `${mes} ${String(f.getUTCFullYear()).slice(2)}`;
  return `${f.getUTCDate()} ${mes}`;
}

/** '10 oct 2026', para el tooltip. */
export function etiquetaDia(t) {
  const f = new Date(t);
  return `${f.getUTCDate()} ${MESES[f.getUTCMonth()]} ${f.getUTCFullYear()}`;
}
