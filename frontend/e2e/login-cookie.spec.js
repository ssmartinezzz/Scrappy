// The root-cause pin for cross-origin session persistence.
//
// Everything about session recovery — the bootstrap admission path, rotation,
// the cross-tab Web Lock — starts from one premise: that the browser actually
// KEPT the refresh cookie the login response set. That premise is invisible
// same-origin (`vite dev`), where a cookie is stored no matter what
// `credentials` says and no matter what CORS allows. Cross-origin it is a real,
// separate requirement, and this spec is the one place it is checked directly
// rather than inferred from a downstream symptom.
//
// Kept apart from session.spec.js on purpose: when this fails, the reload,
// two-tab and back/forward specs fail too, and a reader needs one test whose
// name says which of them is the cause and which are consequences.
import { expect, test } from '@playwright/test';

import { API_ORIGIN, login, readAccounts } from './helpers.js';

test('the login response\'s refresh cookie is actually stored by the browser', async ({
  page,
  context,
}) => {
  const { viewer } = readAccounts();
  await login(page, viewer);

  const galletas = await context.cookies();
  const refresh = galletas.find(c => c.name === 'refresh');

  expect(
    refresh,
    'The browser did not keep the refresh cookie that POST /api/auth/login set.\n' +
    '\n' +
    'A cross-origin response can only set a cookie when BOTH halves of\n' +
    'credentialed CORS agree, and on this route neither does:\n' +
    `  - the request must be sent with credentials:'include'\n` +
    '    (frontend/src/lib/authSession.js `login()` sends the default, which\n' +
    "    for a cross-origin request means 'omit' — Set-Cookie is discarded);\n" +
    '  - the response must carry Access-Control-Allow-Credentials: true\n' +
    '    (CorsConfig.java grants that to RefreshCookie.PATH only, so the\n' +
    "    /** mapping answers /api/auth/login with allowCredentials(false),\n" +
    "    and a credentials:'include' request there fails CORS outright).\n" +
    '\n' +
    'Consequence: login appears to work — the access token is in memory and\n' +
    'valid for 15 minutes — but there is no refresh cookie, so a reload cannot\n' +
    'recover the session and the token can never be rotated. Same-origin\n' +
    '(`vite dev`) hides this completely; BOTH shipped topologies have it.'
  ).toBeDefined();

  expect(refresh.path).toBe('/api/auth/refresh');
  expect(refresh.httpOnly).toBe(true);
  expect(refresh.sameSite).toBe('Strict');
  expect(refresh.domain).toBe(new URL(API_ORIGIN).hostname);
});
