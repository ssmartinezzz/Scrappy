// The assertion that makes every other spec in this directory mean something.
//
// playwright.config.js already refuses to run when the two configured origins
// are equal. That is not enough: a preview server can be serving a `dist/`
// built WITHOUT `VITE_API_BASE_URL`, in which case `api.js`'s
// `BASE = import.meta.env.VITE_API_BASE_URL || ''` falls back to a relative
// path and the bundle addresses `:5173/api` — same-origin, from a browser's
// point of view, with none of the `Origin`/`SameSite`/bootstrap behaviour this
// suite exists to exercise. The config cannot see that; only a loaded page can.
import { expect, test } from '@playwright/test';

import { API_ORIGIN, APP_ORIGIN, login, readAccounts } from './helpers.js';

test('the served bundle addresses the backend cross-origin', async ({ page, context }) => {
  const { viewer } = readAccounts();

  const alBackend = [];
  const alFrontend = [];
  context.on('request', req => {
    const url = req.url();
    if (url.startsWith(`${API_ORIGIN}/api/`)) alBackend.push(`${req.method()} ${url}`);
    if (url.startsWith(`${APP_ORIGIN}/api/`)) alFrontend.push(`${req.method()} ${url}`);
  });

  await login(page, viewer);

  expect(
    alFrontend,
    'The bundle sent API requests to its OWN origin. That is a same-origin\n' +
    'build — `dist/` was built without VITE_API_BASE_URL, so api.js fell back\n' +
    'to a relative path. Every auth assertion in this directory would then be\n' +
    'testing a topology this project never ships. Rebuild with\n' +
    `VITE_API_BASE_URL=${API_ORIGIN} (tests/e2e/run-e2e.sh does it for you).`
  ).toEqual([]);

  expect(
    alBackend.length,
    `no request reached ${API_ORIGIN} at all — is the bundle talking to somewhere else entirely?`
  ).toBeGreaterThan(0);
});

test('a legitimate refresh is cross-origin but same-site', async ({ page, context }) => {
  // The distinction the shipped mechanism turns on. `Sec-Fetch-Site` is set by
  // the browser and cannot be forged, so this is the only place it can be
  // observed honestly. It must be `same-site` — `same-origin` happens only
  // under `vite dev`, and requiring it would have 403'd the cold start on both
  // shipped installs (Engram discovery #926).
  const { viewer } = readAccounts();

  const refrescos = [];
  context.on('request', req => {
    if (req.method() === 'POST' && req.url().startsWith(`${API_ORIGIN}/api/auth/refresh`)) {
      refrescos.push(req);
    }
  });

  await login(page, viewer);
  await page.reload();
  await expect(page.locator('#login-username')).toHaveCount(0);

  expect(refrescos.length, 'the reload sent no refresh at all').toBeGreaterThan(0);

  const cabeceras = await refrescos[refrescos.length - 1].allHeaders();
  expect(cabeceras['origin']).toBe(APP_ORIGIN);
  expect(
    cabeceras['sec-fetch-site'],
    'the browser did not report same-site. If this says same-origin, the run ' +
    'is going through a proxy and is not the shipped topology.'
  ).toBe('same-site');
});
