import { describe, expect, it } from 'vitest';

import { clasificarFallidos, esBloqueo } from './blockedSites';

describe('esBloqueo', () => {
  it.each([
    'paginación cortada en p1: HTTP 403',
    'catálogo cortado en categoría notebooks: HTTP 403',
    'paginación cortada en p1: bloqueado por la tienda (HTTP 429)',
    'HTTP 429 en https://x',
  ])('treats %s as a block', error => {
    expect(esBloqueo({ error })).toBe(true);
  });

  it.each([
    'Error {\n  message=\'net::ERR_SSL_VERSION_OR_CIPHER_MISMATCH at https://a\'',
    'paginación cortada en p1: HTTP 500',
    'HTTP 4030',
    'Deadline global',
    '',
    null,
    undefined,
  ])('keeps %s retryable', error => {
    expect(esBloqueo({ error })).toBe(false);
  });
});

describe('clasificarFallidos', () => {
  it('splits blocked from retryable preserving order', () => {
    const a = { sitio: 'a', error: 'HTTP 403' };
    const b = { sitio: 'b', error: 'timeout' };
    const c = { sitio: 'c', error: 'HTTP 429' };

    expect(clasificarFallidos([a, b, c])).toEqual({ bloqueados: [a, c], reintentables: [b] });
  });

  it('tolerates a missing list', () => {
    expect(clasificarFallidos(undefined)).toEqual({ bloqueados: [], reintentables: [] });
  });
});
