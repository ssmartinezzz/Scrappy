import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { authedFetch } from './authedFetch';
import { __test, getAccessToken, resetSession } from './authSession';

function jsonResponse(body, init = {}) {
  return { ok: true, status: 200, json: async () => body, ...init };
}

function response401() {
  return { ok: false, status: 401, json: async () => ({ error: 'token_invalido' }) };
}

function response403() {
  return { ok: false, status: 403, json: async () => ({ error: 'sin_permiso' }) };
}

function refreshOk(accessToken = 'new-token', csrfNonce = 'new-nonce') {
  return jsonResponse({ accessToken, csrfNonce, expiresIn: 900, tokenType: 'Bearer' });
}

beforeEach(() => {
  resetSession();
});

afterEach(() => {
  resetSession();
});

describe('authedFetch — 401 triggers exactly one shared refresh per burst', () => {
  it('5 concurrent 401s in one tab produce exactly one POST /api/auth/refresh, and all 5 retry with the new token', async () => {
    __test.setSession({ accessToken: 'expired', nonce: 'n0', receivedAt: 1, expiresAt: 1 });

    global.fetch = vi.fn().mockImplementation(async (url, init) => {
      const u = String(url);
      if (u.includes('/api/auth/refresh')) return refreshOk();
      if (u.includes('/api/auth/me')) return jsonResponse({ username: 'v', roles: ['VIEWER'] });
      // Every data call gets a 401 on the FIRST attempt (no Authorization header yet reflects new token),
      // then succeeds once retried with the refreshed token.
      const auth = (init?.headers instanceof Headers) ? init.headers.get('Authorization') : init?.headers?.Authorization;
      if (auth === 'Bearer new-token') return jsonResponse({ ok: true });
      return response401();
    });

    const calls = Array.from({ length: 5 }, (_, i) => authedFetch(`/api/data/${i}`));
    const results = await Promise.all(calls);

    for (const r of results) {
      expect(r.ok).toBe(true);
    }

    const refreshCalls = global.fetch.mock.calls.filter(c => String(c[0]).includes('/api/auth/refresh'));
    expect(refreshCalls).toHaveLength(1);
    expect(getAccessToken()).toBe('new-token');
  });
});

describe('authedFetch — 401 vs 403', () => {
  it('a 403 never triggers a refresh — passes straight through, untouched', async () => {
    __test.setSession({ accessToken: 'tok', nonce: 'n0', receivedAt: 1, expiresAt: 999999999999 });
    global.fetch = vi.fn().mockResolvedValue(response403());

    const res = await authedFetch('/api/agent/chat');

    expect(res.status).toBe(403);
    const refreshCalls = global.fetch.mock.calls.filter(c => String(c[0]).includes('/api/auth/refresh'));
    expect(refreshCalls).toHaveLength(0);
    expect(global.fetch).toHaveBeenCalledTimes(1); // no retry attempted either
  });

  it('the ORIGINAL 401 response is returned when the refresh itself fails — never a fabricated one', async () => {
    __test.setSession({ accessToken: 'expired', nonce: 'n0', receivedAt: 1, expiresAt: 1 });
    const original401 = response401();
    global.fetch = vi.fn().mockImplementation(async (url) => {
      if (String(url).includes('/api/auth/refresh')) {
        return { ok: false, status: 401, json: async () => ({ error: 'refresh_invalido' }) };
      }
      return original401;
    });

    const res = await authedFetch('/api/data');

    expect(res).toBe(original401); // identity, not just shape
    expect(res.status).toBe(401);
  });

  it('never recurses: exactly one retry attempt even if the retry also comes back 401', async () => {
    __test.setSession({ accessToken: 'expired', nonce: 'n0', receivedAt: 1, expiresAt: 1 });
    global.fetch = vi.fn().mockImplementation(async (url) => {
      if (String(url).includes('/api/auth/refresh')) return refreshOk();
      if (String(url).includes('/api/auth/me')) return jsonResponse({ username: 'v', roles: [] });
      return response401(); // data endpoint always 401s, even after "refresh"
    });

    await authedFetch('/api/data');

    const dataCalls = global.fetch.mock.calls.filter(c => String(c[0]) === '/api/data');
    expect(dataCalls).toHaveLength(2); // original + exactly one retry, never more
  });
});

describe('authedFetch — retry fidelity', () => {
  it('re-sends a string body byte-identical on retry', async () => {
    __test.setSession({ accessToken: 'expired', nonce: 'n0', receivedAt: 1, expiresAt: 1 });
    const payload = JSON.stringify({ nombre: 'Remera Ñandú', cantidad: 3 });

    global.fetch = vi.fn().mockImplementation(async (url) => {
      if (String(url).includes('/api/auth/refresh')) return refreshOk();
      if (String(url).includes('/api/auth/me')) return jsonResponse({ username: 'v', roles: [] });
      return response401();
    });

    await authedFetch('/api/favoritos', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: payload,
    });

    const dataCalls = global.fetch.mock.calls.filter(c => String(c[0]) === '/api/favoritos');
    expect(dataCalls).toHaveLength(2);
    expect(dataCalls[0][1].body).toBe(payload);
    expect(dataCalls[1][1].body).toBe(payload);
    expect(dataCalls[0][1].body).toBe(dataCalls[1][1].body);
  });
});

describe('authedFetch — untouched response contract', () => {
  it('mirrors fetch signature and returns the Response as-is on a plain 200 — no reshaping', async () => {
    __test.setSession({ accessToken: 'tok', nonce: 'n0', receivedAt: 1, expiresAt: 999999999999 });
    const body = { productos: [{ nombre: 'x' }] };
    global.fetch = vi.fn().mockResolvedValue(jsonResponse(body));

    const res = await authedFetch('/api/data');

    expect(res.ok).toBe(true);
    await expect(res.json()).resolves.toEqual(body);
  });
});

// apidocs-public-filtered-document: GET /api/openapi.yaml became PERMIT, so
// the console's loadContract() now runs through authedFetch with no session
// at all. Nothing in authedFetch was changed for it — this pins the behaviour
// that made a special case unnecessary.
describe('authedFetch — anonymous, no session', () => {
  it('sends no Authorization header and never touches /api/auth/refresh on a 200', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, text: async () => 'openapi: 3.1.0' });

    const r = await authedFetch('/api/openapi.yaml');

    expect(r.ok).toBe(true);

    const [, init] = global.fetch.mock.calls[0];
    const auth = (init?.headers instanceof Headers)
      ? init.headers.get('Authorization')
      : init?.headers?.Authorization;
    expect(auth ?? null).toBeNull();

    expect(global.fetch.mock.calls.filter(c => String(c[0]).includes('/api/auth/refresh'))).toHaveLength(0);
  });
});
