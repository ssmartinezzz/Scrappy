import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  ApiError,
  applyProposal,
  askAgent,
  cambiarRolUsuario,
  createCronJob,
  desactivarUsuario,
  fetchCronExecutions,
  fetchData,
  fetchGrupos,
  fetchRecomendados,
  fetchUsuarios,
  listCronJobs,
  unwrap,
  unwrapPage,
} from '@/api';

const okResponse = (body, status = 200) => ({ ok: true, status, json: async () => body });
const errResponse = (status, error) => ({ ok: false, status, json: async () => ({ error }) });

beforeEach(() => {
  global.fetch = vi.fn();
});

describe('ApiError', () => {
  it('carries code, message, details and status, and is an Error', () => {
    const e = new ApiError({ code: 'no_encontrado', message: 'No existe.', details: { x: 1 }, status: 404 });

    expect(e).toBeInstanceOf(Error);
    expect(e.name).toBe('ApiError');
    expect(e.message).toBe('No existe.');
    expect(e.code).toBe('no_encontrado');
    expect(e.details).toEqual({ x: 1 });
    expect(e.status).toBe(404);
  });
});

describe('unwrap', () => {
  it('returns the envelope data', async () => {
    await expect(unwrap(okResponse({ data: { a: 1 } }))).resolves.toEqual({ a: 1 });
    await expect(unwrap(okResponse({ data: [1, 2] }))).resolves.toEqual([1, 2]);
  });

  it('returns null for a 204 without reading the body', async () => {
    const json = vi.fn();

    await expect(unwrap({ ok: true, status: 204, json })).resolves.toBeNull();
    expect(json).not.toHaveBeenCalled();
  });

  it('returns null when the body is empty or has no data member', async () => {
    await expect(unwrap({ ok: true, status: 200, json: async () => { throw new SyntaxError('empty'); } }))
      .resolves.toBeNull();
    await expect(unwrap(okResponse({}))).resolves.toBeNull();
  });

  it('throws an ApiError built from the error envelope on a non-ok status', async () => {
    const failure = unwrap(errResponse(409, { code: 'scrape_en_curso', message: 'Hay un scraping.', details: { estado: 'RUNNING' } }));

    await expect(failure).rejects.toMatchObject({
      name: 'ApiError',
      code: 'scrape_en_curso',
      message: 'Hay un scraping.',
      details: { estado: 'RUNNING' },
      status: 409,
    });
  });

  it('still throws a typed ApiError when the error body is not an envelope', async () => {
    const failure = unwrap({ ok: false, status: 502, json: async () => { throw new SyntaxError('html'); } });

    await expect(failure).rejects.toMatchObject({ code: 'http_502', status: 502 });
    await expect(failure).rejects.toBeInstanceOf(ApiError);
  });
});

describe('unwrapPage', () => {
  it('returns data and the 0-based page block', async () => {
    const page = { number: 0, size: 24, total: 50, totalPages: 3 };

    await expect(unwrapPage(okResponse({ data: ['a'], page }))).resolves.toEqual({ data: ['a'], page });
  });

  it('returns nulls for a 204', async () => {
    await expect(unwrapPage({ ok: true, status: 204, json: vi.fn() })).resolves.toEqual({ data: null, page: null });
  });

  it('returns a null page when the server sent none', async () => {
    await expect(unwrapPage(okResponse({ data: [] }))).resolves.toEqual({ data: [], page: null });
  });

  it('throws an ApiError on a non-ok status', async () => {
    await expect(unwrapPage(errResponse(400, { code: 'solicitud_invalida', message: 'mal' })))
      .rejects.toMatchObject({ code: 'solicitud_invalida', status: 400 });
  });
});

describe('list readers rebuild the shapes the screens consume', () => {
  it('fetchData folds page into meta (0-based number as pagina) and keeps productos', async () => {
    global.fetch.mockResolvedValue(okResponse({
      data: { meta: { moneda: 'ARS', facets: {} }, productos: [{ url: 'u' }] },
      page: { number: 2, size: 24, total: 60, totalPages: 3 },
    }));

    const r = await fetchData({ page: 2 });

    expect(r.productos).toEqual([{ url: 'u' }]);
    expect(r.meta).toMatchObject({ moneda: 'ARS', total: 60, pagina: 2, pageSize: 24, totalPaginas: 3 });
  });

  it('fetchData resolves null on an error status instead of throwing', async () => {
    global.fetch.mockResolvedValue(errResponse(500, { code: 'error_interno', message: 'x' }));

    await expect(fetchData({})).resolves.toBeNull();
  });

  it('fetchGrupos returns { grupos, total, page, size }', async () => {
    global.fetch.mockResolvedValue(okResponse({
      data: [{ nombre: 'g' }], page: { number: 1, size: 20, total: 41, totalPages: 3 },
    }));

    await expect(fetchGrupos({})).resolves.toEqual({ grupos: [{ nombre: 'g' }], total: 41, page: 1, size: 20 });
  });

  it('fetchRecomendados returns { items, total, page, size } and asks for page 0 by default', async () => {
    global.fetch.mockResolvedValue(okResponse({
      data: [{ url: 'a' }], page: { number: 0, size: 24, total: 1, totalPages: 1 },
    }));

    const r = await fetchRecomendados();

    expect(r).toEqual({ items: [{ url: 'a' }], total: 1, page: 0, size: 24 });
    expect(new URL(global.fetch.mock.calls[0][0], 'http://x').searchParams.get('page')).toBe('0');
  });

  it('listCronJobs and fetchCronExecutions return the bare array', async () => {
    global.fetch.mockResolvedValueOnce(okResponse({ data: [{ id: 1 }] }));
    await expect(listCronJobs()).resolves.toEqual([{ id: 1 }]);

    global.fetch.mockResolvedValueOnce(okResponse({ data: [{ id: 9 }] }));
    await expect(fetchCronExecutions(1)).resolves.toEqual([{ id: 9 }]);
  });

  it('listCronJobs resolves null on a failed call', async () => {
    global.fetch.mockResolvedValue(errResponse(403, { code: 'sin_permiso', message: 'no' }));

    await expect(listCronJobs()).resolves.toBeNull();
  });
});

describe('mutations keep their { ok, mensaje } result', () => {
  it('a failed mutation carries the server message', async () => {
    global.fetch.mockResolvedValue(errResponse(400, { code: 'solicitud_invalida', message: 'cronExpr inválido' }));

    await expect(createCronJob({})).resolves.toEqual({ ok: false, mensaje: 'cronExpr inválido' });
  });

  it('a successful mutation spreads the data next to ok:true', async () => {
    global.fetch.mockResolvedValue(okResponse({ data: { id: 4, name: 'Nightly' } }));

    await expect(createCronJob({})).resolves.toEqual({ ok: true, id: 4, name: 'Nightly' });
  });
});

describe('agent', () => {
  it('askAgent returns the chat response unwrapped', async () => {
    global.fetch.mockResolvedValue(okResponse({ data: { mensaje: 'hola' } }));

    await expect(askAgent([{ role: 'user', text: 'hola' }])).resolves.toEqual({ mensaje: 'hola' });
  });

  it('askAgent maps an error envelope to { error, mensaje, codigo }', async () => {
    global.fetch.mockResolvedValue(errResponse(502, { code: 'proveedor_no_disponible', message: 'Sin proveedor.' }));

    await expect(askAgent([{ role: 'user', text: 'hola' }]))
      .resolves.toEqual({ error: true, mensaje: 'Sin proveedor.', codigo: 'proveedor_no_disponible' });
  });

  it('askAgent surfaces a 409 as { scraping: true }', async () => {
    global.fetch.mockResolvedValue(errResponse(409, { code: 'scrape_en_curso', message: 'x' }));

    await expect(askAgent([])).resolves.toEqual({ scraping: true });
  });

  it('applyProposal reads the stale conflict from error.details.actual', async () => {
    global.fetch.mockResolvedValue(errResponse(422, {
      code: 'conflicto_stale', message: 'Cambió.', details: { actual: 'Buzo' },
    }));

    await expect(applyProposal({ url: 'u' })).resolves.toEqual({
      ok: false, mensaje: 'Cambió.', codigo: 'conflicto_stale', actual: 'Buzo',
    });
  });

  it('applyProposal spreads the success data next to ok:true', async () => {
    global.fetch.mockResolvedValue(okResponse({ data: { mensaje: 'Reclasificación aplicada.' } }));

    await expect(applyProposal({ url: 'u' })).resolves.toEqual({ ok: true, mensaje: 'Reclasificación aplicada.' });
  });
});

describe('usuarios keep { ok, status, body } on success and on error', () => {
  it('fetchUsuarios returns the array from data', async () => {
    global.fetch.mockResolvedValue(okResponse({ data: [{ username: 'ana' }] }));

    await expect(fetchUsuarios()).resolves.toEqual([{ username: 'ana' }]);
  });

  it('an error becomes body { error: code, mensaje } so the admin sees why it was refused', async () => {
    global.fetch.mockResolvedValue(errResponse(409, { code: 'ultimo_admin', message: 'Es el último ADMIN.' }));

    await expect(desactivarUsuario('jefa')).resolves.toEqual({
      ok: false, status: 409, body: { error: 'ultimo_admin', mensaje: 'Es el último ADMIN.' },
    });
  });

  it('a success carries the unwrapped data as body', async () => {
    global.fetch.mockResolvedValue(okResponse({ data: { ok: true } }));

    await expect(cambiarRolUsuario('ana', 'ADMIN')).resolves.toEqual({ ok: true, status: 200, body: { ok: true } });
  });
});
