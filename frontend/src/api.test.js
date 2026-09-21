import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  deleteSavedPc,
  fetchData,
  fetchIndices,
  fetchPcPreferencia,
  fetchPcsBuilder,
  fetchRecomendacion,
  fetchSavedPcs,
  fetchSuplementosTipos,
  fetchTendencias,
  renamePc,
  savePc,
  savePcPreferencia,
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
  delete window.__API_BASE__;
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

  // Runtime override: the bundle is built once and the launcher decides which
  // backend it talks to, so a single dist/ serves local, LAN and a deploy.
  it('prefers the runtime origin over the baked-in one', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://baked.example.test');
    window.__API_BASE__ = 'https://runtime.example.test';
    vi.resetModules();
    const { fetchData: freshFetchData } = await import('@/api');

    await freshFetchData({});

    expect(global.fetch.mock.calls.at(-1)[0])
      .toMatch(/^https:\/\/runtime\.example\.test\/api\/data/);
  });

  // An unreplaced placeholder must not become a literal request host.
  it('falls back to the baked-in origin when the runtime one is blank', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://baked.example.test');
    window.__API_BASE__ = '';
    vi.resetModules();
    const { fetchData: freshFetchData } = await import('@/api');

    await freshFetchData({});

    expect(global.fetch.mock.calls.at(-1)[0])
      .toMatch(/^https:\/\/baked\.example\.test\/api\/data/);
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

describe('fetchSuplementosTipos', () => {
  it('returns the server list', async () => {
    global.fetch = vi.fn().mockResolvedValue(
      jsonResponse({ tipos: [{ tipo: 'Proteína en Polvo', grupo: 'Proteína' }] }),
    );

    await expect(fetchSuplementosTipos()).resolves.toEqual([
      { tipo: 'Proteína en Polvo', grupo: 'Proteína' },
    ]);
    expect(calledUrl().pathname).toBe('/api/suplementos/tipos');
  });

  it.each([
    ['a non-ok response', { ok: false, status: 500, json: async () => ({}) }],
    ['a body with no tipos array', jsonResponse({})],
    ['a tipos field that is not an array', jsonResponse({ tipos: 'Proteína' })],
  ])('returns [] for %s rather than throwing', async (_label, response) => {
    // The selector renders from this list, so a malformed body must degrade to an empty
    // selector instead of taking the whole panel down.
    global.fetch = vi.fn().mockResolvedValue(response);

    await expect(fetchSuplementosTipos()).resolves.toEqual([]);
  });
});

describe('fetchPcsBuilder', () => {
  it('hits /api/pcs/builder with no query params by default', async () => {
    await fetchPcsBuilder({});

    const url = calledUrl();
    expect(url.pathname).toBe('/api/pcs/builder');
    expect(url.searchParams.has('presupuesto')).toBe(false);
    expect(url.searchParams.has('conGpu')).toBe(false);
    expect(url.searchParams.has('excluir')).toBe(false);
  });

  it('sends presupuesto only when greater than 0', async () => {
    await fetchPcsBuilder({ presupuesto: 0 });
    expect(calledUrl().searchParams.has('presupuesto')).toBe(false);

    await fetchPcsBuilder({ presupuesto: 500000 });
    expect(calledUrl().searchParams.get('presupuesto')).toBe('500000');
  });

  it('sends conGpu only when true', async () => {
    await fetchPcsBuilder({ conGpu: false });
    expect(calledUrl().searchParams.has('conGpu')).toBe(false);

    await fetchPcsBuilder({ conGpu: true });
    expect(calledUrl().searchParams.get('conGpu')).toBe('true');
  });

  it('joins excluir with commas, and omits it when empty', async () => {
    await fetchPcsBuilder({ excluir: [] });
    expect(calledUrl().searchParams.has('excluir')).toBe(false);

    await fetchPcsBuilder({ excluir: ['https://a', 'https://b'] });
    expect(calledUrl().searchParams.get('excluir')).toBe('https://a,https://b');
  });

  it('sends gama only when set', async () => {
    await fetchPcsBuilder({ gama: '' });
    expect(calledUrl().searchParams.has('gama')).toBe(false);

    await fetchPcsBuilder({ gama: 'alta' });
    expect(calledUrl().searchParams.get('gama')).toBe('alta');
  });

  it('returns null on a 204 (no scrape run yet)', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true, status: 204, json: async () => ({}) });

    await expect(fetchPcsBuilder({})).resolves.toBeNull();
  });

  it('returns null when the response is not ok', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: false, status: 500, json: async () => ({}) });

    await expect(fetchPcsBuilder({})).resolves.toBeNull();
  });
});

describe('PC builder preference', () => {
  it('fetchPcPreferencia GETs /api/pcs/preferencia and returns the parsed body', async () => {
    global.fetch = vi.fn().mockResolvedValue(jsonResponse({ gama: 'alta', presupuesto: 900000, conGpu: true }));

    await expect(fetchPcPreferencia()).resolves.toEqual({ gama: 'alta', presupuesto: 900000, conGpu: true });
    expect(calledUrl().pathname).toBe('/api/pcs/preferencia');
  });

  it('fetchPcPreferencia returns null on 204 (never saved) and when not ok', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true, status: 204, json: async () => ({}) });
    await expect(fetchPcPreferencia()).resolves.toBeNull();

    global.fetch = vi.fn().mockResolvedValue({ ok: false, status: 500, json: async () => ({}) });
    await expect(fetchPcPreferencia()).resolves.toBeNull();
  });

  it('savePcPreferencia PUTs the body and returns the parsed response', async () => {
    global.fetch = vi.fn().mockResolvedValue(jsonResponse({ gama: 'media', presupuesto: null, conGpu: false }));
    const body = { gama: 'media', presupuesto: null, conGpu: false };

    await expect(savePcPreferencia(body)).resolves.toEqual(body);
    const [, init] = global.fetch.mock.calls.at(-1);
    expect(calledUrl().pathname).toBe('/api/pcs/preferencia');
    expect(init.method).toBe('PUT');
    expect(JSON.parse(init.body)).toEqual(body);
  });

  it('savePcPreferencia returns null when the response is not ok', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: false, status: 400, json: async () => ({}) });

    await expect(savePcPreferencia({ gama: 'x' })).resolves.toBeNull();
  });
});

describe('Saved PCs', () => {
  it('savePc POSTs to /api/pcs/save with the body and returns the parsed response', async () => {
    global.fetch = vi.fn().mockResolvedValue(jsonResponse({ ok: true, id: 3, nombre: 'PC $100.000', totalEstimado: 100000 }));
    const body = { nombre: 'PC $100.000', picks: [], presupuesto: 0, conGpu: false, totalEstimado: 100000 };

    const result = await savePc(body);

    expect(calledUrl().pathname).toBe('/api/pcs/save');
    const [, init] = global.fetch.mock.calls.at(-1);
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body)).toEqual(body);
    expect(result).toEqual({ ok: true, id: 3, nombre: 'PC $100.000', totalEstimado: 100000 });
  });

  it('savePc returns null when the response is not ok', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: false, status: 500, json: async () => ({}) });

    await expect(savePc({})).resolves.toBeNull();
  });

  it('fetchSavedPcs hits /api/pcs/saved and returns the parsed list', async () => {
    global.fetch = vi.fn().mockResolvedValue(jsonResponse([{ id: 1 }]));

    await expect(fetchSavedPcs()).resolves.toEqual([{ id: 1 }]);
    expect(calledUrl().pathname).toBe('/api/pcs/saved');
  });

  it('fetchSavedPcs returns [] when the response is not ok', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: false, status: 500, json: async () => ({}) });

    await expect(fetchSavedPcs()).resolves.toEqual([]);
  });

  it('deleteSavedPc DELETEs /api/pcs/saved/:id and reports ok', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({}) });

    await expect(deleteSavedPc(7)).resolves.toBe(true);
    expect(calledUrl().pathname).toBe('/api/pcs/saved/7');
    expect(global.fetch.mock.calls.at(-1)[1].method).toBe('DELETE');
  });

  it('renamePc PATCHes /api/pcs/saved/:id/nombre with the new name', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({}) });

    await expect(renamePc(7, 'Nuevo nombre')).resolves.toBe(true);
    const [, init] = global.fetch.mock.calls.at(-1);
    expect(calledUrl().pathname).toBe('/api/pcs/saved/7/nombre');
    expect(init.method).toBe('PATCH');
    expect(JSON.parse(init.body)).toEqual({ nombre: 'Nuevo nombre' });
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

describe('fetchIndices / fetchRecomendacion', () => {
  it('encodes the product URL so query strings in it do not corrupt the request', async () => {
    await fetchRecomendacion('https://tienda.test/p?id=1&x=2');

    expect(calledUrl().searchParams.get('url')).toBe('https://tienda.test/p?id=1&x=2');
  });

  it('returns null on a not-ok response rather than surfacing a rejected promise', async () => {
    global.fetch.mockResolvedValue({ ok: false, status: 500 });

    await expect(fetchIndices()).resolves.toBeNull();
    await expect(fetchRecomendacion('https://tienda.test/p')).resolves.toBeNull();
  });

  it('hits /api/indices, not the removed /api/inflacion', async () => {
    await fetchIndices();

    expect(calledUrl().pathname).toBe('/api/indices');
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

describe('interrupted run: reading the offer and taking it (slice 6)', () => {
  it('reads the interrupted run from GET /api/scrape/interrupted', async () => {
    const { fetchInterrumpida } = await import('@/api');
    global.fetch.mockResolvedValue(jsonResponse({
      hayInterrumpida: true, atendidos: ['freres'], pendientes: ['vcp'], salteados: [],
    }));

    const r = await fetchInterrumpida();

    expect(calledUrl().pathname).toBe('/api/scrape/interrupted');
    expect(global.fetch.mock.calls.at(-1)[1]?.method ?? 'GET').toBe('GET');
    expect(r.hayInterrumpida).toBe(true);
  });

  it('resolves null instead of throwing when the offer cannot be read', async () => {
    // A VIEWER gets 403 here and an expired token gets 401. Neither is an
    // interrupted run, and neither may take the screen down: the banner is an
    // additive notice on top of a page that has its own job.
    const { fetchInterrumpida } = await import('@/api');
    global.fetch.mockResolvedValue({ ok: false, status: 403, json: async () => ({}) });

    await expect(fetchInterrumpida()).resolves.toBeNull();
  });

  it('takes the offer with POST /api/scrape/resume', async () => {
    const { retomarScrape } = await import('@/api');
    global.fetch.mockResolvedValue(jsonResponse({ retomando: true, mensaje: 'Retomando…' }));

    const r = await retomarScrape();

    expect(calledUrl().pathname).toBe('/api/scrape/resume');
    expect(global.fetch.mock.calls.at(-1)[1].method).toBe('POST');
    expect(r.retomando).toBe(true);
  });

  it('reports a refused resume as retomando:false, never as a silent success', async () => {
    // The backend answers 200 with `retomando:false` when another scrape got
    // in first. Reading only r.ok would navigate to a progress screen for a
    // run that was never started.
    const { retomarScrape } = await import('@/api');
    global.fetch.mockResolvedValue(jsonResponse({ retomando: false, mensaje: 'ya hay un scraping en curso' }));

    await expect(retomarScrape()).resolves.toMatchObject({ retomando: false });
  });
});
