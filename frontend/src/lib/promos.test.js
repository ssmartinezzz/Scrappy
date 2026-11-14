import { describe, expect, it } from 'vitest';
import { MEDIOS_VISIBLES, textoPromoCorta, textoPromoLarga, vigenciaTexto } from './promos';

describe('textoPromoCorta (mejorPromo de una fila del catálogo)', () => {
  it('descuento: "15% Transferencia"', () => {
    expect(textoPromoCorta({ medio: 'Transferencia', red: null, tipo: 'descuento', cuotas: null, pct: 15 }))
      .toBe('15% Transferencia');
  });
  it('cuotas sin interés con red: "12 sin interés Santander Visa"', () => {
    expect(textoPromoCorta({ medio: 'Santander', red: 'Visa', tipo: 'cuotas_sin_interes', cuotas: 12, pct: null }))
      .toBe('12 sin interés Santander Visa');
  });
  it('cuotas sin red: no inventa una red', () => {
    expect(textoPromoCorta({ medio: 'Mercado Pago', red: null, tipo: 'cuotas_sin_interes', cuotas: 6, pct: null }))
      .toBe('6 sin interés Mercado Pago');
  });
  it('un pct decimal usa coma es-AR', () => {
    expect(textoPromoCorta({ medio: 'Galicia', red: null, tipo: 'descuento', cuotas: null, pct: 7.5 }))
      .toBe('7,5% Galicia');
  });
  it('sin promo o tipo desconocido: cadena vacía (abstención)', () => {
    expect(textoPromoCorta(undefined)).toBe('');
    expect(textoPromoCorta({ medio: 'X', tipo: 'recargo', pct: 5 })).toBe('');
  });
});

describe('textoPromoLarga (promo completa del detalle)', () => {
  const base = { sitio: 'S', medio: { nombre: 'Santander', tipo: 'banco' }, red: { nombre: 'Visa' },
    alcance: 'sitio', vigenciaDesde: null, vigenciaHasta: null };
  it('cuotas', () => {
    expect(textoPromoLarga({ ...base, tipo: 'cuotas_sin_interes', cuotas: 12, pct: null }))
      .toBe('12 cuotas sin interés');
  });
  it('una sola cuota se dice en singular', () => {
    expect(textoPromoLarga({ ...base, tipo: 'cuotas_sin_interes', cuotas: 1, pct: null }))
      .toBe('1 cuota sin interés');
  });
  it('descuento y recargo', () => {
    expect(textoPromoLarga({ ...base, tipo: 'descuento', cuotas: null, pct: 15 })).toBe('15% de descuento');
    expect(textoPromoLarga({ ...base, tipo: 'recargo', cuotas: null, pct: 10 })).toBe('10% de recargo');
  });
});

describe('vigenciaTexto', () => {
  it('sin fechas: null', () => {
    expect(vigenciaTexto({ vigenciaDesde: null, vigenciaHasta: null })).toBeNull();
  });
  it('sólo hasta', () => {
    expect(vigenciaTexto({ vigenciaDesde: null, vigenciaHasta: '2026-11-30' })).toBe('Hasta el 30/11');
  });
  it('sólo desde', () => {
    expect(vigenciaTexto({ vigenciaDesde: '2026-11-03', vigenciaHasta: null })).toBe('Desde el 3/11');
  });
  it('ventana', () => {
    expect(vigenciaTexto({ vigenciaDesde: '2026-11-03', vigenciaHasta: '2026-11-30' })).toBe('Del 3/11 al 30/11');
  });
});

describe('tope de iconos', () => {
  it('son 4', () => expect(MEDIOS_VISIBLES).toBe(4));
});
