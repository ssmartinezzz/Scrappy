import { describe, expect, it } from 'vitest';
import { RANGOS, contextoCategoria, dominioPrecio, serieEnRango, ticksTiempo } from './serieHistorial';

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

  it('Todo arranca en el primer registro y estira el último precio hasta hoy', () => {
    const s = serieEnRango(PUNTOS, 'Todo', HOY);

    expect(s.desde).toBe(dia('2026-01-10'));
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

  it('un rango más largo que la serie empieza en el primer registro', () => {
    expect(serieEnRango(PUNTOS.slice(1), '1A', HOY).desde).toBe(dia('2026-08-01'));
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
