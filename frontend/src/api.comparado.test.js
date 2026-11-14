import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchComparado } from '@/api';

beforeEach(() => { global.fetch = vi.fn(); });
afterEach(() => { vi.restoreAllMocks(); });

describe('fetchComparado', () => {
  it('pide /api/producto/{key}/comparado y desenvuelve data', async () => {
    const data = [{ sitio: 'X', url: 'https://x/1', key: 'k1', precio: 10, stock: 'en_stock', historial: { puntos: [] } }];
    global.fetch.mockResolvedValue({ ok: true, status: 200, json: async () => ({ data }) });

    const r = await fetchComparado('a1b2c3d4e5f60718');

    expect(new URL(global.fetch.mock.calls[0][0], 'http://localhost').pathname)
      .toBe('/api/producto/a1b2c3d4e5f60718/comparado');
    expect(r).toEqual(data);
  });

  it('un 404 da null, no una excepción', async () => {
    global.fetch.mockResolvedValue({
      ok: false, status: 404, json: async () => ({ error: { code: 'not_found', message: 'x' } }),
    });
    expect(await fetchComparado('nope')).toBeNull();
  });
});
