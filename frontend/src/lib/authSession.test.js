import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  installFakeCoordinationPrimitives,
  uninstallCoordinationPrimitives,
} from '../test/fakeAuthPrimitives';

function jsonResponse(body, init = {}) {
  return { ok: true, status: 200, json: async () => body, ...init };
}

function refreshOk(accessToken, csrfNonce, expiresIn = 900) {
  return jsonResponse({ accessToken, csrfNonce, expiresIn, tokenType: 'Bearer' });
}

/** Fresh, isolated module instance (own closure state) — used to simulate a second tab. */
async function freshAuthSession() {
  vi.resetModules();
  return import('./authSession');
}

describe('authSession — no coordination primitives available', () => {
  beforeEach(() => {
    uninstallCoordinationPrimitives();
    vi.resetModules();
    global.fetch = vi.fn();
  });

  it('module import does not throw when BroadcastChannel and navigator.locks are both missing', async () => {
    await expect(import('./authSession')).resolves.toBeDefined();
  });

  it('still refreshes and grants a session in degraded (per-tab-only) mode', async () => {
    global.fetch.mockResolvedValueOnce(refreshOk('tok-degraded', 'nonce-degraded'));
    global.fetch.mockResolvedValueOnce(jsonResponse({ username: 'valeria', roles: ['VIEWER'] }));

    const authSession = await import('./authSession');
    const ok = await authSession.ensureFreshSession({ reason: 'test' });

    expect(ok).toBe(true);
    expect(authSession.getAccessToken()).toBe('tok-degraded');
  });

  it('a reuse-detection 401 (sesion_invalidada) still performs a clean local logout with no retry', async () => {
    global.fetch.mockResolvedValueOnce({
      ok: false, status: 401, json: async () => ({ error: 'sesion_invalidada' }),
    });

    const authSession = await import('./authSession');
    authSession.__test.setSession({ accessToken: 'stale', nonce: 'n', receivedAt: 1, expiresAt: 9999999999999 });

    const ok = await authSession.ensureFreshSession({ reason: 'test' });

    expect(ok).toBe(false);
    expect(authSession.getAccessToken()).toBeNull();
    expect(global.fetch).toHaveBeenCalledTimes(1); // no retry loop
  });
});

describe('authSession — resetSession()', () => {
  beforeEach(() => {
    uninstallCoordinationPrimitives();
    vi.resetModules();
  });

  it('clears the in-memory token, nonce and identity set via the test seam', async () => {
    const authSession = await import('./authSession');
    authSession.__test.setSession({ accessToken: 'abc', nonce: 'n1', receivedAt: 5, expiresAt: 999999999999 });
    authSession.__test.setIdentity({ username: 'valeria', roles: ['ADMIN'] });

    expect(authSession.getAccessToken()).toBe('abc');
    expect(authSession.getIdentity()).toEqual({ username: 'valeria', roles: ['ADMIN'] });

    authSession.resetSession();

    expect(authSession.getAccessToken()).toBeNull();
    expect(authSession.getIdentity()).toBeNull();
  });

  it('clears the in-tab refresh promise so a subsequent 401 triggers a fresh network refresh, not a stale one', async () => {
    global.fetch = vi.fn().mockResolvedValue(refreshOk('after-reset', 'nonce-after-reset'));
    const authSession = await import('./authSession');

    // Simulate a hung refresh from a previous "test" by seeding state, then resetting.
    authSession.__test.setSession({ accessToken: 'leftover', nonce: 'leftover-nonce', receivedAt: 1, expiresAt: 1 });
    authSession.resetSession();

    const ok = await authSession.ensureFreshSession({ reason: 'test' });
    expect(ok).toBe(true);
    expect(authSession.getAccessToken()).toBe('after-reset');
  });
});

describe('authSession — cross-tab coordination', () => {
  beforeEach(() => {
    installFakeCoordinationPrimitives();
  });

  afterEach(() => {
    uninstallCoordinationPrimitives();
  });

  it('adopts a broadcast session only if strictly newer than the one it already holds', async () => {
    vi.resetModules();
    const tabB = await import('./authSession');

    // tabB already holds something "fresher" than what's about to arrive.
    tabB.__test.setSession({ accessToken: 'tabB-own', nonce: 'n-b', receivedAt: 5000, expiresAt: 999999999999 });

    // An older broadcast arrives (simulates a delayed message from a stale rotation).
    tabB.__test.receiveBroadcast({ type: 'session', accessToken: 'stale-broadcast', nonce: 'n-old', receivedAt: 1000, expiresAt: 1 });
    expect(tabB.getAccessToken()).toBe('tabB-own'); // NOT adopted — older

    // A genuinely newer broadcast arrives.
    tabB.__test.receiveBroadcast({ type: 'session', accessToken: 'fresh-broadcast', nonce: 'n-new', receivedAt: 9000, expiresAt: 999999999999 });
    expect(tabB.getAccessToken()).toBe('fresh-broadcast'); // adopted — newer
  });

  it('two real module instances: a refresh in tab A is observed and adopted by tab B, with zero network calls from B', async () => {
    global.fetch = vi.fn().mockImplementation(async (url) => {
      if (String(url).includes('/api/auth/refresh')) return refreshOk('tokA', 'nonceA');
      if (String(url).includes('/api/auth/me')) return jsonResponse({ username: 'valeria', roles: ['VIEWER'] });
      throw new Error(`unexpected fetch: ${url}`);
    });

    vi.resetModules();
    const tabA = await import('./authSession');
    vi.resetModules();
    const tabB = await import('./authSession');

    const okA = await tabA.ensureFreshSession({ reason: 'test' });
    expect(okA).toBe(true);

    // Let the microtask-queued broadcast delivery to tabB settle.
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(tabB.getAccessToken()).toBe('tokA');
    // Only tabA's refresh (+ its /me call) hit the network — tabB made none.
    const refreshCalls = global.fetch.mock.calls.filter(c => String(c[0]).includes('/api/auth/refresh'));
    expect(refreshCalls).toHaveLength(1);
  });

  it('a tab that adopts a sibling session also learns WHO it is, not just the token', async () => {
    // Found by the Phase 8 browser suite: the session payload carried the token
    // and nonce but not the identity, and nothing re-fetched /me afterwards. The
    // adopting tab was fully authenticated and still roleless — which, under
    // hide-not-disable, strips every ADMIN affordance from an admin's second tab
    // and reads as a demotion rather than a bug.
    global.fetch = vi.fn().mockImplementation(async (url) => {
      if (String(url).includes('/api/auth/refresh')) return refreshOk('tokA', 'nonceA');
      if (String(url).includes('/api/auth/me')) return jsonResponse({ username: 'e2e-admin', roles: ['ADMIN'] });
      throw new Error(`unexpected fetch: ${url}`);
    });

    vi.resetModules();
    const tabA = await import('./authSession');
    vi.resetModules();
    const tabB = await import('./authSession');

    expect(await tabA.ensureFreshSession({ reason: 'test' })).toBe(true);
    await new Promise(resolve => setTimeout(resolve, 0));

    // tabB answers the probe path the same way a cold-starting tab would.
    expect(await tabB.bootstrap()).toBe(true);

    expect(tabB.getAccessToken()).toBe('tokA');
    expect(tabB.getIdentity()).toEqual({ username: 'e2e-admin', roles: ['ADMIN'] });
  });

  it('an "ended" broadcast stops a sibling tab from firing its own refresh', async () => {
    global.fetch = vi.fn();

    vi.resetModules();
    const tabA = await import('./authSession');
    vi.resetModules();
    const tabB = await import('./authSession');

    tabB.__test.setSession({ accessToken: 'tabB-tok', nonce: 'n', receivedAt: 1, expiresAt: 999999999999 });

    tabA.__test.receiveBroadcast({ type: 'ended', reason: 'sesion_invalidada' }); // simulate as if A broadcast it
    // Directly simulate delivery to B (real transport would carry this over BroadcastChannel):
    tabB.__test.receiveBroadcast({ type: 'ended', reason: 'sesion_invalidada' });

    expect(tabB.getAccessToken()).toBeNull();

    const ok = await tabB.ensureFreshSession({ reason: 'test' });
    expect(ok).toBe(false);
    expect(global.fetch).not.toHaveBeenCalled();
  });
});

describe('authSession — role is fetched from /me, never decoded from the token (design D4, spec frontend-role-awareness)', () => {
  beforeEach(() => {
    uninstallCoordinationPrimitives();
    vi.resetModules();
  });

  it('GET /api/auth/me is called after a successful bootstrap refresh, and the identity is stored', async () => {
    global.fetch = vi.fn().mockImplementation(async (url) => {
      const u = String(url);
      if (u.includes('/api/auth/refresh')) return refreshOk('tok', 'nonce');
      if (u.includes('/api/auth/me')) return jsonResponse({ username: 'valeria', roles: ['ADMIN'] });
      throw new Error(`unexpected fetch: ${u}`);
    });

    const authSession = await import('./authSession');
    const ok = await authSession.bootstrap();

    expect(ok).toBe(true);
    expect(authSession.getIdentity()).toEqual({ username: 'valeria', roles: ['ADMIN'] });
    const meCalls = global.fetch.mock.calls.filter(c => String(c[0]).includes('/api/auth/me'));
    expect(meCalls).toHaveLength(1);
  });

  it('GET /api/auth/me is called again after every subsequent successful refresh — not just bootstrap', async () => {
    global.fetch = vi.fn().mockImplementation(async (url) => {
      const u = String(url);
      if (u.includes('/api/auth/refresh')) return refreshOk('tok2', 'nonce2');
      if (u.includes('/api/auth/me')) return jsonResponse({ username: 'valeria', roles: ['VIEWER'] });
      throw new Error(`unexpected fetch: ${u}`);
    });

    const authSession = await import('./authSession');
    authSession.__test.setSession({ accessToken: 'old', nonce: 'old-nonce', receivedAt: 1, expiresAt: 1 });

    await authSession.ensureFreshSession({ reason: 'test' });

    const meCalls = global.fetch.mock.calls.filter(c => String(c[0]).includes('/api/auth/me'));
    expect(meCalls).toHaveLength(1); // this refresh's own /me call, not zero
  });

  it('GET /api/auth/me is called after login and the identity is stored', async () => {
    global.fetch = vi.fn().mockImplementation(async (url) => {
      const u = String(url);
      if (u.includes('/api/auth/login')) return refreshOk('tok3', 'nonce3');
      if (u.includes('/api/auth/me')) return jsonResponse({ username: 'admin', roles: ['ADMIN'] });
      throw new Error(`unexpected fetch: ${u}`);
    });

    const authSession = await import('./authSession');
    const result = await authSession.login('admin', 'hunter2');

    expect(result.ok).toBe(true);
    expect(authSession.getIdentity()).toEqual({ username: 'admin', roles: ['ADMIN'] });
  });

  it('source assertion: authSession.js never decodes a role/claim out of the JWT itself', async () => {
    const path = await import('node:path');
    const fs = await import('node:fs/promises');
    const src = await fs.readFile(path.resolve(process.cwd(), 'src/lib/authSession.js'), 'utf-8');
    // The only legitimate source of `roles` is the /me response body — never
    // a base64/JWT-payload decode of accessToken.
    expect(src).not.toMatch(/atob\(/);
    expect(src).not.toMatch(/jwt-decode/);
    expect(src).not.toMatch(/accessToken\.split\(['"]\.['"]\)/);
  });
});

describe('authSession — network error vs rejected session', () => {
  beforeEach(() => {
    uninstallCoordinationPrimitives();
    vi.resetModules();
  });

  it('a network error (fetch rejects) is reported distinctly from a backend-rejected session', async () => {
    global.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    const authSession = await import('./authSession');

    const ok = await authSession.ensureFreshSession({ reason: 'bootstrap' });

    expect(ok).toBe(false);
    expect(authSession.getLastFailureReason()).toBe('network_error');
  });

  it('a rejected refresh (refresh_invalido) is reported with its own reason, not network_error', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false, status: 401, json: async () => ({ error: 'refresh_invalido' }),
    });
    const authSession = await import('./authSession');

    const ok = await authSession.ensureFreshSession({ reason: 'bootstrap' });

    expect(ok).toBe(false);
    expect(authSession.getLastFailureReason()).toBe('refresh_invalido');
  });
});

// ─── bfcache / wake revalidation (design D2, spec frontend-auth-session) ────
// Added after sdd-verify found these two handlers had ZERO covering tests —
// unit or e2e. The logic read as sound, but a spec scenario without a passing
// test is assumed, not verified. The browser suite cannot reach them either:
// its back/forward test does full document loads, which is not bfcache.
describe('authSession — a tab resumed from bfcache or from the background revalidates', () => {
  beforeEach(() => {
    installFakeCoordinationPrimitives();
    vi.resetModules();
  });

  afterEach(() => {
    uninstallCoordinationPrimitives();
    vi.restoreAllMocks();
  });

  // Every assertion here — the NEGATIVE ones included — must outlast the 150 ms
  // sibling probe (SIBLING_PROBE_TIMEOUT_MS). Asserting "no fetch" after 5 ms
  // only proves the probe had not timed out yet, which is not the same thing as
  // proving nothing happens. Two of these tests were vacuous exactly that way.
  const esperarAlSondeo = () => new Promise(resolve => setTimeout(resolve, 250));

  /** jsdom builds a PageTransitionEvent poorly; a plain Event with the flag set is enough. */
  function firePageshow(persisted) {
    const event = new Event('pageshow');
    Object.defineProperty(event, 'persisted', { value: persisted });
    window.dispatchEvent(event);
  }

  it('a restored tab with no live sibling refreshes rather than presenting its stale token', async () => {
    global.fetch = vi.fn().mockImplementation(async (url) => {
      if (String(url).includes('/api/auth/refresh')) return refreshOk('tokFresco', 'nonceFresco');
      if (String(url).includes('/api/auth/me')) return jsonResponse({ username: 'e2e-admin', roles: ['ADMIN'] });
      throw new Error(`unexpected fetch: ${url}`);
    });

    const tab = await import('./authSession');
    tab.__test.setSession({ accessToken: 'tokViejo', nonce: 'n', receivedAt: 1, expiresAt: 2 });

    firePageshow(true);
    await esperarAlSondeo();

    expect(tab.getAccessToken()).toBe('tokFresco');
  });

  it('a NON-persisted pageshow is an ordinary load and triggers no revalidation at all', async () => {
    global.fetch = vi.fn();

    const tab = await import('./authSession');
    tab.__test.setSession({ accessToken: 'tok', nonce: 'n', receivedAt: 1, expiresAt: Date.now() + 900000 });

    firePageshow(false);
    await esperarAlSondeo();

    expect(global.fetch).not.toHaveBeenCalled();
  });

  it('waking with a token that is still comfortably fresh does NOT mint a new session', async () => {
    // Restoring five backgrounded tabs must not produce five refreshes.
    global.fetch = vi.fn();

    const tab = await import('./authSession');
    tab.__test.setSession({
      accessToken: 'tok',
      nonce: 'n',
      receivedAt: Date.now(),
      expiresAt: Date.now() + 900_000,
    });

    document.dispatchEvent(new Event('visibilitychange'));
    await esperarAlSondeo();

    expect(global.fetch).not.toHaveBeenCalled();
  });

  it('waking with a token about to expire refreshes it', async () => {
    global.fetch = vi.fn().mockImplementation(async (url) => {
      if (String(url).includes('/api/auth/refresh')) return refreshOk('tokRenovado', 'nonceRenovado');
      if (String(url).includes('/api/auth/me')) return jsonResponse({ username: 'e2e-admin', roles: ['ADMIN'] });
      throw new Error(`unexpected fetch: ${url}`);
    });

    const tab = await import('./authSession');
    tab.__test.setSession({
      accessToken: 'tokPorVencer',
      nonce: 'n',
      receivedAt: Date.now(),
      expiresAt: Date.now() + 1_000, // inside the wake threshold
    });

    document.dispatchEvent(new Event('visibilitychange'));
    await esperarAlSondeo();

    expect(tab.getAccessToken()).toBe('tokRenovado');
  });
});

// ─── transient server faults are not auth verdicts ──────────────────────────
// Found by the PR #152 adversarial review. Only 401/403 are decisions about who
// you are; a 500 from a database blip with Tomcat still up was being routed to
// endSession(), which cleared the session, broadcast 'ended' to every tab and
// latched `ended` so no retry could recover — all with a refresh cookie still
// valid for fourteen days. Connection-refused was absorbed correctly; the case
// next door was not.
describe('authSession — a 5xx during refresh is transient, not a logout', () => {
  beforeEach(() => {
    uninstallCoordinationPrimitives();
    vi.resetModules();
  });

  it('keeps the session on a 500 and can still recover on the next attempt', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce({ ok: false, status: 500, json: async () => ({}) })
      .mockResolvedValueOnce(refreshOk('tokDespuesDelHipo', 'nonceNuevo'))
      .mockResolvedValueOnce(jsonResponse({ username: 'ana', roles: ['ADMIN'] }));

    const authSession = await import('./authSession');
    authSession.__test.setSession({
      accessToken: 'tokPrevio', nonce: 'n', receivedAt: 1, expiresAt: 9999999999999,
    });

    expect(await authSession.ensureFreshSession({ reason: 'test' })).toBe(false);
    expect(authSession.getAccessToken())
      .toBe('tokPrevio'); // NOT cleared — the cookie is still good

    // The blip passes and the very next attempt succeeds. If `ended` had latched,
    // ensureFreshSession would short-circuit and never reach the network.
    expect(await authSession.ensureFreshSession({ reason: 'test' })).toBe(true);
    expect(authSession.getAccessToken()).toBe('tokDespuesDelHipo');
  });

  it('still ends the session on a 401 — a real auth verdict is terminal', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false, status: 401, json: async () => ({ error: 'refresh_invalido' }),
    });

    const authSession = await import('./authSession');
    authSession.__test.setSession({
      accessToken: 'tokPrevio', nonce: 'n', receivedAt: 1, expiresAt: 9999999999999,
    });

    expect(await authSession.ensureFreshSession({ reason: 'test' })).toBe(false);
    expect(authSession.getAccessToken())
      .toBeNull(); // the backend said who you are is no longer valid — that IS terminal
  });
});
