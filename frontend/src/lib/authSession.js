// frontend-auth-ui, Phase 4 (design D2/D3). Module scope, NO React.
//
// This module owns the WHOLE session: the in-memory access token, the CSRF
// nonce, the identity, the in-tab single-flight refresh promise, cross-tab
// coordination, and the one and only place `credentials: 'include'` appears
// in the entire frontend. Everything else — authedFetch.js, AuthProvider,
// route guards, login/logout — is a consumer of this module.
//
// Deliberately NOT `useState`: useState is per-component-tree, so two
// components would each get their own `refreshPromise` and the single-flight
// guarantee would silently evaporate while every test still passed.
// authedFetch.js is not a component and cannot read React state at all.

// decouple-services-postgres design D6 — mirrors api.js's own BASE
// resolution (`import.meta.env.VITE_API_BASE_URL || ''`). Kept as a literal
// one-line duplicate here rather than a shared constant: it is a single
// expression, not an algorithm that can drift (unlike the CorsConfig/
// AllowedOrigins case), and api.js's BASE stays untouched per the "55-site
// rename is mechanical, don't restructure callers" instruction.
const BASE = import.meta.env.VITE_API_BASE_URL || '';

const CHANNEL_NAME = 'scrappy-auth';
const LOCK_NAME = 'scrappy-auth-refresh';
const SIBLING_PROBE_TIMEOUT_MS = 150;
const WAKE_REFRESH_THRESHOLD_MS = 60_000;

// ─── Feature detection (module load, no polyfill) ───────────────────────────
const hasBroadcastChannel = typeof BroadcastChannel !== 'undefined';
const hasLocks = typeof navigator !== 'undefined'
  && navigator.locks
  && typeof navigator.locks.request === 'function';

let channel = null;
if (hasBroadcastChannel) {
  try {
    channel = new BroadcastChannel(CHANNEL_NAME);
    channel.onmessage = event => handleBroadcastMessage(event.data);
  } catch {
    channel = null; // some environments throw rather than omit the constructor
  }
}

const tabId = `${Date.now()}-${Math.random().toString(36).slice(2)}`;

// ─── Module state ────────────────────────────────────────────────────────
let session = { accessToken: null, nonce: null, receivedAt: 0, expiresAt: 0 };
let identity = null;           // { username, roles: [...] }
let refreshPromise = null;     // in-tab single flight
let ended = false;             // true after logout / a terminal rejection
let lastFailureReason = null;  // 'network_error' | 'refresh_invalido' | 'sesion_invalidada' | 'csrf_invalido' | ...
const listeners = new Set();

function notify() {
  const snapshot = getSnapshot();
  listeners.forEach(fn => fn(snapshot));
}

export function subscribe(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

export function getSnapshot() {
  return { authenticated: !!session.accessToken, identity, ended };
}

export function getAccessToken() {
  return session.accessToken;
}

export function getIdentity() {
  return identity;
}

export function getLastFailureReason() {
  return lastFailureReason;
}

export function isAuthenticated() {
  return !!session.accessToken;
}

export function resetSession() {
  session = { accessToken: null, nonce: null, receivedAt: 0, expiresAt: 0 };
  identity = null;
  refreshPromise = null;
  ended = false;
  lastFailureReason = null;
  notify();
}

// ─── Cross-tab transport ────────────────────────────────────────────────
function broadcast(msg) {
  if (!channel) return;
  try { channel.postMessage(msg); } catch { /* best-effort */ }
}

function adoptBroadcastSession(msg) {
  if (msg.receivedAt <= session.receivedAt) return; // adopt-only-if-newer
  session = {
    accessToken: msg.accessToken,
    nonce: msg.nonce,
    receivedAt: msg.receivedAt,
    expiresAt: msg.expiresAt,
  };
  // The identity travels WITH the session. Without it an adopting tab holds a
  // perfectly valid token and still does not know who it is — and because the
  // role rule is hide-not-disable, an ADMIN's second tab silently loses every
  // ADMIN affordance. It rides along rather than being re-fetched because the
  // sibling already resolved it for this exact token.
  if (msg.identity !== undefined) identity = msg.identity;
  ended = false;
  lastFailureReason = null;
  notify();
}

function handleBroadcastMessage(msg) {
  if (!msg || typeof msg !== 'object') return;
  if (msg.type === 'session') {
    adoptBroadcastSession(msg);
  } else if (msg.type === 'ended') {
    session = { accessToken: null, nonce: null, receivedAt: Date.now(), expiresAt: 0 };
    identity = null;
    ended = true;
    lastFailureReason = msg.reason || 'ended';
    notify();
  } else if (msg.type === 'who-has-current') {
    if (session.accessToken && msg.id !== tabId) {
      broadcast({
        type: 'session',
        receivedAt: session.receivedAt,
        accessToken: session.accessToken,
        nonce: session.nonce,
        expiresAt: session.expiresAt,
        identity,
      });
    }
  }
}

/** Ask any live sibling tab for its current session; resolves true if one adopted. */
function probeSiblings(timeoutMs = SIBLING_PROBE_TIMEOUT_MS) {
  return new Promise(resolve => {
    if (!channel) return resolve(false);
    let settled = false;
    const before = session.receivedAt;
    const onMessage = event => {
      const msg = event.data;
      if (msg?.type === 'session' && msg.receivedAt > before && !settled) {
        settled = true;
        channel.removeEventListener('message', onMessage);
        resolve(true);
      }
    };
    channel.addEventListener('message', onMessage);
    broadcast({ type: 'who-has-current', id: tabId });
    setTimeout(() => {
      if (settled) return;
      settled = true;
      channel.removeEventListener('message', onMessage);
      resolve(false);
    }, timeoutMs);
  });
}

async function withLock(fn) {
  if (hasLocks) {
    return navigator.locks.request(LOCK_NAME, () => fn());
  }
  return fn(); // degrade: no cross-tab mutex, in-tab single flight is still enforced below
}

// ─── Session transitions ────────────────────────────────────────────────
function adoptSession(data) {
  session = {
    accessToken: data.accessToken,
    nonce: data.csrfNonce,
    receivedAt: Date.now(),
    expiresAt: Date.now() + (Number(data.expiresIn) > 0 ? Number(data.expiresIn) * 1000 : 15 * 60 * 1000),
  };
  ended = false;
  lastFailureReason = null;
  broadcast({
    type: 'session',
    receivedAt: session.receivedAt,
    accessToken: session.accessToken,
    nonce: session.nonce,
    expiresAt: session.expiresAt,
    // May legitimately be null here: this fires the moment a token is stored,
    // and /api/auth/me has not necessarily answered yet. That is why adopting
    // is belt-and-braces — see adoptIdentityIfMissing().
    identity,
  });
  notify();
}

function endSession(reason) {
  session = { accessToken: null, nonce: null, receivedAt: Date.now(), expiresAt: 0 };
  identity = null;
  ended = true;
  lastFailureReason = reason;
  broadcast({ type: 'ended', reason });
  notify();
}

async function fetchIdentity() {
  try {
    const res = await fetch(`${BASE}/api/auth/me`, {
      headers: { Authorization: `Bearer ${session.accessToken}` },
    });
    identity = res.ok ? await res.json() : null;
  } catch {
    identity = null;
  }
  notify();
}

// ─── Refresh (D1: bootstrap-CSRF admission + D2: coordination) ─────────────
// The ONLY place `credentials: 'include'` appears in the entire frontend
// (design D3) — both refresh (POST, rotate) and logout (DELETE, revoke) hit
// this exact path, and both need the HttpOnly refresh cookie attached.
// Credentialed CORS is scoped to this path alone (RefreshCookie.PATH,
// CorsConfig.java:85-96); anywhere else it would be silently useless
// cross-origin and a needless widening same-origin.
async function refreshCookieFetch(method, nonce) {
  const headers = {};
  if (nonce) headers['X-Refresh-CSRF'] = nonce;
  return fetch(`${BASE}/api/auth/refresh`, { method, credentials: 'include', headers });
}

async function callRefresh(nonce) {
  return refreshCookieFetch('POST', nonce);
}

/**
 * One refresh attempt, honouring D1's bounded nonce-less carve-out: a stale
 * (but present) nonce gets exactly one nonce-less retry — never a loop, and
 * never for a request that already had no nonce to begin with.
 */
async function attemptRefresh() {
  const hadNonce = !!session.nonce;
  let res;
  try {
    res = await callRefresh(session.nonce);
  } catch {
    return { ok: false, networkError: true };
  }

  if (res.status === 403) {
    if (hadNonce) {
      let retry;
      try {
        retry = await callRefresh(null);
      } catch {
        return { ok: false, networkError: true };
      }
      if (retry.ok) return { ok: true, data: await retry.json() };
      const body = await retry.json().catch(() => ({}));
      return { ok: false, reason: body.error || 'csrf_invalido' };
    }
    const body = await res.json().catch(() => ({}));
    return { ok: false, reason: body.error || 'csrf_invalido' };
  }

  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    return { ok: false, reason: body.error || `http_${res.status}` };
  }

  return { ok: true, data: await res.json() };
}

async function performRefresh() {
  const startedAt = session.receivedAt;
  return withLock(async () => {
    // Double-checked: a sibling's broadcast may have already delivered a
    // newer session while we were waiting for the lock.
    if (session.receivedAt > startedAt) return true;

    const result = await attemptRefresh();
    if (!result.ok) {
      if (result.networkError) {
        // A stopped/unreachable backend is NOT "you were logged out" — do
        // not clear the session or broadcast 'ended'; just fail this attempt.
        lastFailureReason = 'network_error';
        return false;
      }
      endSession(result.reason);
      return false;
    }

    adoptSession(result.data);
    await fetchIdentity();
    return true;
  });
}

/**
 * THE one entry point every caller (authedFetch, route guards, app
 * bootstrap) shares. In-tab single flight: N concurrent callers await the
 * SAME promise, so exactly one network refresh happens per tab per attempt.
 */
export async function ensureFreshSession(_opts = {}) {
  if (ended) return false; // a sibling already declared the session over — no hopeless retry
  if (!refreshPromise) {
    refreshPromise = performRefresh().finally(() => { refreshPromise = null; });
  }
  return refreshPromise;
}

/**
 * A sibling normally sends its identity along with the session, but it can
 * legitimately have none yet — it may have answered the probe in the window
 * between storing a token and /api/auth/me replying. Adopting a session and
 * then never learning who we are is the failure this closes: the tab would be
 * fully authenticated and still render as roleless, which under hide-not-
 * disable looks exactly like a demotion.
 */
async function adoptIdentityIfMissing() {
  if (session.accessToken && !identity) await fetchIdentity();
}

/** Probe siblings first; only refresh over the network if none answers. */
export async function bootstrap() {
  const adopted = await probeSiblings();
  if (adopted) {
    await adoptIdentityIfMissing();
    return true;
  }
  return ensureFreshSession({ reason: 'bootstrap' });
}

// ─── Login / logout ──────────────────────────────────────────────────────
export async function login(username, password) {
  let res;
  try {
    // `credentials: 'include'` is REQUIRED here, not optional, and the reason
    // is easy to get backwards: this response carries the Set-Cookie that
    // plants the refresh cookie. Cross-origin — which is every shipped
    // topology — a response to a request made without credentials mode has its
    // Set-Cookie discarded, so the cookie is never stored and the session can
    // never be recovered on reload. FRONTEND_AUTH_CONTRACT.md used to say
    // credentials belonged on /api/auth/refresh "and only there"; that was
    // wrong, and it only looked right under `vite dev`, where login is
    // same-origin. The backend allows credentials on this path for the same
    // reason (CorsConfig.LOGIN_PATH).
    res = await fetch(`${BASE}/api/auth/login`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });
  } catch {
    return { ok: false, networkError: true };
  }
  if (!res.ok) return { ok: false };
  const data = await res.json();
  adoptSession(data);
  await fetchIdentity();
  return { ok: true };
}

export async function logout() {
  const nonce = session.nonce;
  try {
    await refreshCookieFetch('DELETE', nonce);
  } catch {
    // Clear local state regardless of network failure — spec requirement.
  }
  endSession('logout');
}

// ─── bfcache / background-tab revalidation (D2) ─────────────────────────
if (typeof window !== 'undefined') {
  window.addEventListener('pageshow', event => {
    if (!event.persisted) return;
    probeSiblings().then(adopted => {
      if (!adopted) return ensureFreshSession({ reason: 'bfcache' });
      return adoptIdentityIfMissing();
    });
  });

  if (typeof document !== 'undefined' && document.addEventListener) {
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState !== 'visible') return;
      probeSiblings().then(adopted => {
        if (adopted) return adoptIdentityIfMissing();
        if (session.accessToken && Date.now() > session.expiresAt - WAKE_REFRESH_THRESHOLD_MS) {
          return ensureFreshSession({ reason: 'wake' });
        }
        return undefined;
      });
    });
  }
}

// ─── Test seam (design D3) ───────────────────────────────────────────────
// vi.restoreAllMocks() does NOT reset a module-level `let` — without this,
// session state leaks between tests and the leak looks like a bug elsewhere.
export const __test = {
  setSession(partial) {
    session = { ...session, ...partial };
    notify();
  },
  setIdentity(id) {
    identity = id;
    notify();
  },
  /** Feeds a message straight into the same handler real transport delivers to — not a bypass of the adoption algorithm, only of the wire. */
  receiveBroadcast(msg) {
    handleBroadcastMessage(msg);
  },
};
