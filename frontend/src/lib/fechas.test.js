import { describe, it, expect } from 'vitest';
import { parseFechaApi, formatFecha, formatFechaHora } from './fechas';

// normalize-db-schema-fks-1nf, slice A.4. Since V8 every timestamp column is
// TIMESTAMPTZ and the backend renders it as UTC ISO-8601 ("2026-08-11T20:15:00Z").
// Before this slice the frontend hardcoded TWO different assumptions about the
// wire format — cron fields "contain a T", saved outfits "contain a space" —
// and each site broke on the other one's shape.

describe('parseFechaApi', () => {
  it('parses the UTC ISO-8601 the API emits now', () => {
    const d = parseFechaApi('2026-08-11T20:15:00Z');
    expect(d).toBeInstanceOf(Date);
    expect(d.toISOString()).toBe('2026-08-11T20:15:00.000Z');
  });

  it('still parses the legacy space-separated format', () => {
    // Rows written before V8 keep flowing through the same components while a
    // page is open; a value the API used to emit must not render "Invalid Date".
    const d = parseFechaApi('2026-08-11 20:15:00');
    expect(d).toBeInstanceOf(Date);
    expect(d.getFullYear()).toBe(2026);
  });

  it('parses an offset-less local ISO string as local time', () => {
    const d = parseFechaApi('2026-08-11T20:15:00');
    expect(d.getHours()).toBe(20);
  });

  it('returns null for empty, null and unparseable values instead of Invalid Date', () => {
    expect(parseFechaApi(null)).toBeNull();
    expect(parseFechaApi('')).toBeNull();
    expect(parseFechaApi('—')).toBeNull();
  });
});

describe('formatFecha / formatFechaHora', () => {
  it('formats a UTC instant in the local timezone', () => {
    const esperado = new Date('2026-08-11T20:15:00Z');
    expect(formatFecha('2026-08-11T20:15:00Z'))
      .toBe(esperado.toLocaleDateString('es-AR', { day: '2-digit', month: '2-digit', year: 'numeric' }));
  });

  it('renders a dash for a missing value, never "Invalid Date"', () => {
    expect(formatFecha(null)).toBe('—');
    expect(formatFechaHora(undefined)).toBe('—');
    expect(formatFechaHora('nada de esto es una fecha')).toBe('—');
  });

  it('includes the time, and never leaks the raw wire format', () => {
    const salida = formatFechaHora('2026-08-11T20:15:00Z');
    expect(salida).not.toContain('T');
    expect(salida).not.toContain('Z');
    expect(salida).toMatch(/\d{2}:\d{2}/);
  });
});
