import { describe, expect, it } from 'vitest';
import { anexarSeries, masBarato, unirSeries, vigentesEn } from './serieComparada';

const D1 = Date.UTC(2026, 10, 1);
const D2 = Date.UTC(2026, 10, 2);
const D3 = Date.UTC(2026, 10, 3);

describe('unirSeries', () => {
  it('une fechas superpuestas en una sola fila con una clave por tienda', () => {
    const filas = unirSeries([
      { id: 'a', puntos: [{ t: D1, precio: 100 }, { t: D2, precio: 90 }] },
      { id: 'b', puntos: [{ t: D2, precio: 95 }, { t: D3, precio: 80 }] },
    ]);
    expect(filas).toEqual([
      { t: D1, a: 100 },
      { t: D2, a: 90, b: 95 },
      { t: D3, b: 80 },
    ]);
  });

  it('fechas disjuntas: cada tienda queda undefined donde no tiene punto', () => {
    const filas = unirSeries([
      { id: 'a', puntos: [{ t: D1, precio: 100 }] },
      { id: 'b', puntos: [{ t: D3, precio: 80 }] },
    ]);
    expect(filas).toEqual([{ t: D1, a: 100 }, { t: D3, b: 80 }]);
    expect(filas[0].b).toBeUndefined();
  });

  it('ordena por fecha aunque lleguen desordenadas', () => {
    const filas = unirSeries([
      { id: 'a', puntos: [{ t: D3, precio: 1 }] },
      { id: 'b', puntos: [{ t: D1, precio: 2 }] },
    ]);
    expect(filas.map(f => f.t)).toEqual([D1, D3]);
  });

  it('hermanos sin puntos no aportan filas ni claves', () => {
    const filas = unirSeries([
      { id: 'a', puntos: [{ t: D1, precio: 100 }] },
      { id: 'b', puntos: [] },
    ]);
    expect(filas).toEqual([{ t: D1, a: 100 }]);
  });

  it('sin tiendas devuelve lista vacía', () => {
    expect(unirSeries([])).toEqual([]);
  });
});

describe('masBarato', () => {
  it('elige el menor precio entre las que tienen stock', () => {
    expect(masBarato([
      { id: 'a', precio: 100, stock: 'en_stock' },
      { id: 'b', precio: 80, stock: 'en_stock' },
    ])).toBe('b');
  });

  it('nunca elige una tienda sin stock, aunque sea la más barata', () => {
    expect(masBarato([
      { id: 'a', precio: 100, stock: 'en_stock' },
      { id: 'b', precio: 50, stock: 'sin_stock' },
    ])).toBe('a');
  });

  it('stock desconocido cuenta como disponible', () => {
    expect(masBarato([
      { id: 'a', precio: 100, stock: 'en_stock' },
      { id: 'b', precio: 70, stock: 'desconocido' },
    ])).toBe('b');
  });

  it('si todas están sin stock no resalta ninguna', () => {
    expect(masBarato([{ id: 'a', precio: 1, stock: 'sin_stock' }])).toBeNull();
  });
});

describe('anexarSeries', () => {
  it('sin tiendas devuelve las filas tal cual', () => {
    const filas = [{ t: D1, precio: 1, vigente: 1 }];
    expect(anexarSeries(filas, [])).toBe(filas);
  });

  it('suma la clave de cada tienda sin tocar los campos de la serie propia', () => {
    const filas = [{ t: D1, precio: 100, vigente: 100 }, { t: D2, precio: null, vigente: 100 }];
    const out = anexarSeries(filas, [{ id: 'h0', puntos: [{ t: D2, precio: 90 }, { t: D3, precio: 80 }] }]);
    expect(out).toEqual([
      { t: D1, precio: 100, vigente: 100 },
      { t: D2, precio: null, vigente: 100, h0: 90 },
      { t: D3, h0: 80 },
    ]);
    expect(filas[1].h0).toBeUndefined();
  });
});

describe('vigentesEn', () => {
  const tiendas = [
    { id: 'h0', puntos: [{ t: D1, precio: 100 }, { t: D3, precio: 80 }] },
    { id: 'h1', puntos: [{ t: D2, precio: 95 }] },
  ];

  it('da el último precio de cada tienda en o antes del instante', () => {
    expect(vigentesEn(tiendas, D2)).toEqual({ h0: 100, h1: 95 });
    expect(vigentesEn(tiendas, D3)).toEqual({ h0: 80, h1: 95 });
  });

  it('antes del primer punto de una tienda no hay precio', () => {
    expect(vigentesEn(tiendas, D1)).toEqual({ h0: 100, h1: null });
  });

  it('una tienda sin puntos queda en null', () => {
    expect(vigentesEn([{ id: 'h2', puntos: [] }], D3)).toEqual({ h2: null });
  });
});
