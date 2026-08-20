// The reset link carries a live credential in the URL FRAGMENT, and the screen
// has to get rid of it before it touches the network.
//
// A fragment is never sent to a server, so it stays out of access logs — but it
// IS part of `document.URL`, and therefore part of the `Referer` the browser
// attaches to every subresource and every cross-origin request the page makes.
// One third-party font, one analytics beacon, and a single-use password-reset
// token is in somebody else's log. `history.replaceState` on mount is what
// closes that, and "on mount" only counts if it really is before the first
// request.
import { expect, test } from '@playwright/test';

import { API_ORIGIN } from './helpers.js';

// Distinctive enough that finding it anywhere is unambiguous, and shaped like
// the real thing (Base64-url, no padding — PasswordResetService.aleatorio).
const TOKEN = 'e2e-fragment-canary-Ab3xZ9_kQ1-token';

test('the token is stripped from the fragment before any network call', async ({ page }) => {
  const urlsVistas = [];
  const referers = [];
  page.on('request', req => {
    urlsVistas.push(req.url());
    const ref = req.headers()['referer'];
    if (ref) referers.push(ref);
  });

  await page.goto(`/reset-password#token=${TOKEN}`);

  // The form rendering at all is the proof the token was READ before it was
  // erased — the screen shows "este enlace no es válido" when it finds none.
  await expect(page.locator('#reset-password')).toBeVisible();

  expect(
    await page.evaluate(() => window.location.hash),
    'the token is still in the address bar. It will ride along in the Referer ' +
    'of every request this page makes from here on.'
  ).toBe('');
  expect(await page.evaluate(() => window.location.pathname)).toBe('/reset-password');

  const filtradas = urlsVistas.filter(u => u.includes(TOKEN));
  expect(filtradas, 'a request URL carried the reset token').toEqual([]);

  const referersConToken = referers.filter(r => r.includes(TOKEN));
  expect(
    referersConToken,
    'a Referer header carried the reset token to another origin'
  ).toEqual([]);
});

test('the stripped token is still the one submitted, and only in the body', async ({ page }) => {
  // Stripping the fragment must not lose the token: a screen that erased it
  // and then posted `null` would satisfy the previous test perfectly while
  // being completely broken.
  const confirmaciones = [];
  page.on('request', req => {
    if (req.url() === `${API_ORIGIN}/api/auth/password-reset/confirm`) confirmaciones.push(req);
  });

  await page.goto(`/reset-password#token=${TOKEN}`);
  await expect(page.locator('#reset-password')).toBeVisible();

  const nueva = 'una-password-larga-de-verdad';
  await page.fill('#reset-password', nueva);
  await page.fill('#reset-confirm-password', nueva);
  await page.getByRole('button', { name: 'Cambiar contraseña' }).click();

  await expect
    .poll(() => confirmaciones.length, { message: 'confirm was never called' })
    .toBe(1);

  const peticion = confirmaciones[0];
  const cuerpo = JSON.parse(peticion.postData());
  expect(cuerpo.token, 'the fragment strip lost the token instead of keeping it').toBe(TOKEN);
  expect(cuerpo.password).toBe(nueva);

  expect(peticion.url()).not.toContain(TOKEN);
  expect(peticion.headers()['referer'] || '').not.toContain(TOKEN);

  // The backend refuses this made-up token, and the screen must not pretend to
  // know which of the three reasons applies — the backend deliberately does not
  // distinguish unknown, expired and already-used.
  await expect(page.getByRole('alert')).toContainText(/no sirve, ya fue usado o venció/);
});

test('arriving with no fragment at all says so instead of posting nothing', async ({ page }) => {
  await page.goto('/reset-password');
  await expect(page.getByRole('alert')).toContainText(/enlace no es válido/);
  await expect(page.locator('#reset-password')).toHaveCount(0);
});
