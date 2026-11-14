// Une las series de varias tiendas en un solo dataset para recharts: una fila
// por instante, una clave por tienda. Donde una tienda no tiene punto la clave
// queda ausente (undefined) y la línea la salta con connectNulls.
export function unirSeries(tiendas) {
  const filas = new Map();
  for (const { id, puntos } of tiendas) {
    for (const { t, precio } of puntos) {
      const fila = filas.get(t) ?? { t };
      fila[id] = precio;
      filas.set(t, fila);
    }
  }
  return [...filas.values()].sort((a, b) => a.t - b.t);
}

// Suma las series de otras tiendas a filas ya armadas (la serie propia) sin
// tocar sus campos; un instante que la base no tiene agrega una fila nueva.
export function anexarSeries(filas, tiendas) {
  if (!tiendas.length) return filas;
  const porT = new Map(filas.map(f => [f.t, { ...f }]));
  for (const extra of unirSeries(tiendas)) {
    const { t, ...valores } = extra;
    porT.set(t, Object.assign(porT.get(t) ?? { t }, valores));
  }
  return [...porT.values()].sort((a, b) => a.t - b.t);
}

// Id de la tienda más barata entre las que se pueden comprar; una sin stock
// jamás gana. Con stock desconocido se asume disponible.
export function masBarato(tiendas) {
  let mejor = null;
  for (const t of tiendas) {
    if (t.stock === 'sin_stock') continue;
    if (!mejor || t.precio < mejor.precio) mejor = t;
  }
  return mejor ? mejor.id : null;
}

// Precio que regía en cada tienda en el instante `t`: su último punto en o
// antes de `t`; null si la tienda todavía no tenía precio.
export function vigentesEn(tiendas, t) {
  const out = {};
  for (const { id, puntos } of tiendas) {
    let vigente = null;
    for (const p of puntos) {
      if (p.t > t) break;
      vigente = p.precio;
    }
    out[id] = vigente;
  }
  return out;
}
