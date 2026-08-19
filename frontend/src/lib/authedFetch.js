// frontend-auth-ui, Phase 4 (design D3). Drop-in replacement for `fetch`
// used by every one of api.js's call sites. Mirrors fetch's exact signature
// and returns the Response UNTOUCHED — no r.ok, no .json(), no reshaping.
// All 57 existing callers already do their own status handling.
import { ensureFreshSession, getAccessToken } from './authSession';

function withAuth(init) {
  const token = getAccessToken();
  if (!token) return init;
  const headers = new Headers(init?.headers || {});
  headers.set('Authorization', `Bearer ${token}`);
  return { ...init, headers };
}

/**
 * On 401: refresh once (single-flight, cross-tab coordinated — see
 * authSession.js) and retry once. NEVER recursive. If the refresh fails,
 * returns the ORIGINAL 401 response, not a new one.
 *
 * 403 passes straight through and NEVER triggers a refresh — see
 * docs/FRONTEND_AUTH_CONTRACT.md trap #2: 401 means "reauthenticate", 403
 * means "reauthenticating will not help".
 */
export async function authedFetch(input, init) {
  const first = await fetch(input, withAuth(init));
  if (first.status !== 401) return first;

  const refreshed = await ensureFreshSession({ reason: '401' });
  if (!refreshed) return first;

  return fetch(input, withAuth(init));
}
