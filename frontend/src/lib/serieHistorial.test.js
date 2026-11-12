import { describe, expect, it } from 'vitest';
import { RANGOS, contextoCategoria, dominioPrecio, serieDiaria, serieEnRango, ticksTiempo } from './serieHistorial';

const dia = iso => Date.UTC(...iso.split('-').map((n, i) => (i === 1 ? Number(n) - 1 : Number(n))));

const PUNTOS = [
  { fecha: '2026-01-10', precio: 20000 },
  { fecha: '2026-08-01', precio: 18000 },
  { fecha: '2026-09-20', precio: 16000 },
];
const HOY = '2026-10-10';

describe('serieEnRango', () => {
  it('ofrece los rangos de un gráfico de trading, de corto a largo', () => {
    expect(RANGOS.map(r => r.id)).toEqual(['1D', '1S', '1M', '3M', '6M', '1A', 'Todo']);
  });

  it('Todo arranca en el primer registro con un margen del 10% de la historia', () => {
    const s = serieEnRango(PUNTOS, 'Todo', HOY);

    // 273 días de datos: 27 de margen, no un año vacío a la izquierda.
    expect(s.desde).toBe(dia('2025-12-14'));
    expect(s.desdeDatos).toBe(dia('2026-01-10'));
    expect(s.hasta).toBe(dia(HOY));
    expect(s.puntos.map(p => [p.t, p.precio, p.real])).toEqual([
      [dia('2026-01-10'), 20000, true],
      [dia('2026-08-01'), 18000, true],
      [dia('2026-09-20'), 16000, true],
      [dia(HOY), 16000, false],
    ]);
    // 20000 rigió 203 días, 18000 50 y 16000 20: el promedio pesa por tiempo, no por registro.
    expect(s.stats).toEqual({
      min: 16000, max: 20000, avg: (20000 * 203 + 18000 * 50 + 16000 * 20) / 273, deltaPct: -20, registros: 3,
    });
  });

  it('una ventana que empieza entre registros arrastra el precio anterior', () => {
    const s = serieEnRango(PUNTOS, '3M', HOY);

    expect(s.desde).toBe(dia('2026-07-10'));
    expect(s.puntos[0]).toEqual({ t: dia('2026-07-10'), precio: 20000, real: false });
    expect(s.puntos.filter(p => p.real).map(p => p.precio)).toEqual([18000, 16000]);
    expect(s.stats.max).toBe(20000);
    expect(s.stats.deltaPct).toBe(-20);
    expect(s.stats.registros).toBe(2);
    expect(s.stats.avg).toBeCloseTo((20000 * 22 + 18000 * 50 + 16000 * 20) / 92, 6);
  });

  it('1D sobre datos diarios sin cambios es una recta en el precio vigente', () => {
    const s = serieEnRango(PUNTOS, '1D', HOY);

    expect(s.puntos.map(p => p.precio)).toEqual([16000, 16000]);
    expect(s.stats).toEqual({ min: 16000, max: 16000, avg: 16000, deltaPct: 0, registros: 0 });
  });

  it('restar un mes a un 31 cae en el último día del mes anterior, no lo desborda', () => {
    const viejos = [{ fecha: '2025-12-01', precio: 1 }, { fecha: '2026-01-15', precio: 2 }];
    expect(serieEnRango(viejos, '1M', '2026-03-31').desde).toBe(dia('2026-02-28'));
  });

  it('un rango más largo que la serie no se recorta: los datos arrancan adentro', () => {
    const s = serieEnRango(PUNTOS.slice(1), '1A', HOY);
    expect(s.desde).toBe(dia('2025-10-10'));
    expect(s.desdeDatos).toBe(dia('2026-08-01'));
    expect(s.stats.registros).toBe(2);
  });

  it('Todo sobre un producto de una semana no deja la curva pegada a la derecha', () => {
    const s = serieEnRango([{ fecha: '2026-10-03', precio: 1 }, { fecha: '2026-10-06', precio: 2 }], 'Todo', HOY);
    expect(s.desdeDatos).toBe(dia('2026-10-03'));
    expect(s.desde).toBe(dia('2026-10-02'));
  });

  it('Todo con historia vieja también deja el margen proporcional', () => {
    const viejos = [{ fecha: '2024-03-01', precio: 1 }, ...PUNTOS];
    const s = serieEnRango(viejos, 'Todo', HOY);
    // 953 días de datos: 95 de margen.
    expect(s.desde).toBe(dia('2023-11-27'));
    expect(s.desdeDatos).toBe(dia('2024-03-01'));
  });
});

describe('serieEnRango con un solo registro', () => {
  it('un precio que nunca cambió es una recta desde su registro hasta hoy', () => {
    const s = serieEnRango([{ fecha: '2026-10-01', precio: 5000 }], 'Todo', HOY);

    expect(s.puntos.map(p => [p.t, p.precio, p.real])).toEqual([
      [dia('2026-10-01'), 5000, true],
      [dia(HOY), 5000, false],
    ]);
    expect(s.stats).toEqual({ min: 5000, max: 5000, avg: 5000, deltaPct: 0, registros: 1 });
  });
});

describe('serieDiaria', () => {
  const s = serieEnRango(
    [{ fecha: '2026-10-05', precio: 100 }, { fecha: '2026-10-08', precio: 120 }], '1S', HOY);
  const filas = serieDiaria(s);

  it('tiene una fila por día de la ventana, extremos incluidos', () => {
    expect(filas.map(f => f.t)).toEqual(
      ['03', '04', '05', '06', '07', '08', '09', '10'].map(d => dia(`2026-10-${d}`)));
  });

  it('cada día lleva el precio que rigió; antes del primer registro no hay precio', () => {
    expect(filas.map(f => f.vigente)).toEqual([null, null, 100, 100, 100, 120, 120, 120]);
  });

  it('la curva sólo se ancla en los cambios y en los extremos de los datos', () => {
    expect(filas.map(f => f.precio)).toEqual([null, null, 100, null, null, 120, null, 120]);
    expect(filas.map(f => f.real)).toEqual([false, false, true, false, false, true, false, false]);
  });
});

describe('dominioPrecio', () => {
  it('deja aire arriba y abajo y usa ticks redondos', () => {
    const { domain, ticks } = dominioPrecio(16000, 20000);

    expect(domain[0]).toBeLessThan(16000);
    expect(domain[1]).toBeGreaterThan(20000);
    expect(ticks[0]).toBe(domain[0]);
    expect(ticks.at(-1)).toBe(domain[1]);
    const paso = ticks[1] - ticks[0];
    expect(ticks.every(t => t % paso === 0)).toBe(true);
    expect(ticks.length).toBeGreaterThanOrEqual(3);
    expect(ticks.length).toBeLessThanOrEqual(7);
  });

  it('una serie plana igual tiene un rango visible', () => {
    const { domain } = dominioPrecio(16000, 16000);
    expect(domain[0]).toBeLessThan(16000);
    expect(domain[1]).toBeGreaterThan(16000);
  });

  it('nunca baja de cero', () => {
    expect(dominioPrecio(100, 90000).domain[0]).toBe(0);
  });

  it('una referencia cercana entra en la escala', () => {
    const { domain, incluyeReferencia } = dominioPrecio(16000, 20000, 23000);
    expect(incluyeReferencia).toBe(true);
    expect(domain[1]).toBeGreaterThan(23000);
  });

  it('una referencia lejana no aplasta la serie: queda fuera de escala', () => {
    const conLejana = dominioPrecio(16000, 20000, 90000);
    expect(conLejana.incluyeReferencia).toBe(false);
    expect(conLejana.domain).toEqual(dominioPrecio(16000, 20000).domain);
  });
});

describe('ticksTiempo', () => {
  it('reparte pocas marcas dentro de la ventana', () => {
    const desde = dia('2026-01-10');
    const hasta = dia(HOY);
    const ticks = ticksTiempo(desde, hasta);

    expect(ticks.length).toBeGreaterThanOrEqual(2);
    expect(ticks.length).toBeLessThanOrEqual(7);
    expect(ticks.every(t => t >= desde && t <= hasta)).toBe(true);
  });
});

describe('contextoCategoria', () => {
  const distribucion = { Remeras: { n: 120, median: 15000, q1: 11000, q3: 21000 } };

  it('toma mediana y cuartiles de la categoría del producto', () => {
    expect(contextoCategoria(distribucion, { categoria: 'Remeras', cantidadUnidades: 1 }))
      .toEqual({ nombre: 'Remeras', n: 120, mediana: 15000, q1: 11000, q3: 21000 });
  });

  it('las stats son por unidad: un pack de 3 se compara contra tres unidades', () => {
    expect(contextoCategoria(distribucion, { categoria: 'Remeras', cantidadUnidades: 3 }))
      .toMatchObject({ mediana: 45000, q1: 33000, q3: 63000 });
  });

  it('se abstiene sin categoría, sin stats o con muestra chica', () => {
    expect(contextoCategoria(distribucion, { categoria: '' })).toBeNull();
    expect(contextoCategoria(distribucion, { categoria: 'Medias' })).toBeNull();
    expect(contextoCategoria(null, { categoria: 'Remeras' })).toBeNull();
    expect(contextoCategoria({ Remeras: { n: 4, median: 1, q1: 1, q3: 1 } }, { categoria: 'Remeras' })).toBeNull();
  });
});
