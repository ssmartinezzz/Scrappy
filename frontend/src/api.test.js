import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  fetchData,
  fetchInflacion,
  fetchRecomendacion,
  fetchTendencias,
  startScrape,
} from '@/api';

/** Last URL that was passed to fetch(). */
function calledUrl() {
  return new URL(global.fetch.mock.calls.at(-1)[0], 'http://localhost');
}

function jsonResponse(body, init = {}) {
  return { ok: true, status: 200, json: async () => body, ...init };
}

beforeEach(() => {
  global.fetch = vi.fn().mockResolvedValue(jsonResponse({}));
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.resetModules();
});

describe('BASE resolution from import.meta.env', () => {
  // These stub the env explicitly rather than reading whatever the developer's
  // shell exports. VITE_API_BASE_URL is genuinely set on some machines here,
  // which would otherwise make the relative-path case pass or fail by accident.
  it('stays relative when VITE_API_BASE_URL is unset so the dev proxy keeps working', async () => {
    vi.stubEnv('VITE_API_BASE_URL', '');
    vi.resetModules();
    const { fetchData: freshFetchData } = await import('@/api');

    await freshFetchData({});

    expect(global.fetch.mock.calls.at(-1)[0]).toMatch(/^\/api\/data/);
  });

  it('prefixes the configured backend origin when VITE_API_BASE_URL is set', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://api.example.test');
    vi.resetModules();
    const { fetchData: freshFetchData } = await import('@/api');

    await freshFetchData({});

    expect(global.fetch.mock.calls.at(-1)[0])
      .toMatch(/^https:\/\/api\.example\.test\/api\/data/);
  });
});

describe('fetchData query serialisation', () => {
  it('repeats a key per element for array filters', async () => {
    await fetchData({ categoria: ['Remeras', 'Medias'] });

    expect(calledUrl().searchParams.getAll('categoria')).toEqual(['Remeras', 'Medias']);
  });

  it('omits empty, null and undefined filters instead of sending blanks', async () => {
    await fetchData({ q: '', genero: null, marca: undefined, sitio: 'midway' });

    const params = calledUrl().searchParams;
    expect(params.has('q')).toBe(false);
    expect(params.has('genero')).toBe(false);
    expect(params.has('marca')).toBe(false);
    expect(params.get('sitio')).toBe('midway');
  });

  it('keeps a zero price bound, which is a real filter and not an empty value', async () => {
    await fetchData({ precioMin: 0 });

    expect(calledUrl().searchParams.get('precioMin')).toBe('0');
  });

  it('returns null instead of throwing when the backend answers not-ok', async () => {
    global.fetch.mockResolvedValue({ ok: false, status: 500 });

    await expect(fetchData({})).resolves.toBeNull();
  });
});

describe('fetchTendencias status mapping', () => {
  it.each([
    ['ok', { ok: true, status: 200, json: async () => ({ hay: 'datos' }) }, 'ok'],
    ['no content', { ok: false, status: 204 }, 'empty'],
    ['ml pipeline failure', { ok: false, status: 503 }, 'failed'],
    ['unexpected status', { ok: false, status: 418 }, 'failed'],
  ])('maps %s to state "%s"', async (_label, response, expected) => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    global.fetch.mockResolvedValue(response);

    await expect(fetchTendencias()).resolves.toMatchObject({ state: expected });
  });

  it('maps a rejected fetch to "error", not "failed", so offline is distinguishable', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    global.fetch.mockRejectedValue(new TypeError('Failed to fetch'));

    await expect(fetchTendencias()).resolves.toEqual({ state: 'error', data: null });
  });
});

describe('fetchInflacion / fetchRecomendacion', () => {
  it('encodes the product URL so query strings in it do not corrupt the request', async () => {
    await fetchRecomendacion('https://tienda.test/p?id=1&x=2');

    expect(calledUrl().searchParams.get('url')).toBe('https://tienda.test/p?id=1&x=2');
  });

  it('returns null on a not-ok response rather than surfacing a rejected promise', async () => {
    global.fetch.mockResolvedValue({ ok: false, status: 500 });

    await expect(fetchInflacion()).resolves.toBeNull();
    await expect(fetchRecomendacion('https://tienda.test/p')).resolves.toBeNull();
  });
});

describe('startScrape', () => {
  it('appends one sitios param per site and omits forceRetrain when false', async () => {
    await startScrape({ precioMin: 100, precioMax: 200, sitios: ['midway', 'barnes'] });

    const params = calledUrl().searchParams;
    expect(params.getAll('sitios')).toEqual(['midway', 'barnes']);
    expect(params.has('forceRetrain')).toBe(false);
  });

  it('issues a POST, never a GET', async () => {
    await startScrape({ precioMin: 0, precioMax: 1, sitios: [] });

    expect(global.fetch.mock.calls.at(-1)[1]).toMatchObject({ method: 'POST' });
  });
});
