// Session continuity: the checks the dead escape hatch actually broke.
//
// FRONTEND_AUTH_CONTRACT.md used to describe a nonce-less refresh admitted
// "only if the row had no nonce" — a rule that could never fire, because
// RefreshTokenService.emitir() mints a nonce on EVERY emission. Following it
// literally meant a cold page load always got 403 csrf_invalido and the user
// was thrown back to the login screen on every F5. Nothing in the JVM or
// Vitest suites can see that: it only exists where a real browser, a real
// HttpOnly cookie and two real origins meet.
//
// If these fail together with e2e/login-cookie.spec.js, that spec is the cause
// and these are the symptom — the cookie is never stored in the first place.
import { expect, test } from '@playwright/test';

import {
  API_ORIGIN,
  CAMPO_LOGIN,
  esperarDeslogueado,
  esperarSesionViva,
  login,
  readAccounts,
  recolectar401,
} from './helpers.js';

test('a cold reload recovers the session with no login prompt', async ({ page }) => {
  const { viewer } = readAccounts();
  await login(page, viewer);
  const dondeEstaba = new URL(page.url()).pathname;

  // The access token and the nonce both live in module memory, and the reload
  // destroys both. Everything that follows has to come from the HttpOnly
  // cookie plus the bootstrap admission path.
  await page.reload();

  await esperarSesionViva(
    page,
    'the reload landed on the login form. Session recovery is the single ' +
    'failure the bootstrap admission path exists to prevent, and it is the ' +
    'one a user meets on every F5.'
  );
  expect(new URL(page.url()).pathname).toBe(dondeEstaba);
  await expect(page.getByText(viewer.username).first()).toBeVisible();
});

test('reloading repeatedly keeps working (the nonce rotates every time)', async ({ page }) => {
  // Each recovery hands back a NEW refresh token and a NEW nonce. If any step
  // re-presented a spent token, reuse detection would revoke the family and
  // the next reload would land on /login — so three in a row is the assertion.
  const { viewer } = readAccounts();
  await login(page, viewer);

  for (let i = 1; i <= 3; i += 1) {
    await page.reload();
    await esperarSesionViva(page, `reload #${i} was bounced to the login form`);
  }
});

test('back and forward navigation restores a working session', async ({ page }) => {
  const { viewer } = readAccounts();
  const golpes = recolectar401(page);
  await login(page, viewer);

  // Full document loads, not client-side routing: the interesting case is the
  // one where the tab's in-memory session was destroyed and rebuilt, which
  // client-side navigation never does.
  await page.goto('/favoritos');
  await esperarSesionViva(page, 'a full navigation to /favoritos lost the session');

  golpes.reset();
  await page.goBack();
  await esperarSesionViva(page, 'going back lost the session');
  await page.goForward();
  await esperarSesionViva(page, 'going forward lost the session');

  expect(
    golpes.lista,
    'a stale credential was presented during back/forward navigation. The app ' +
    'recovers from a 401 by refreshing and retrying, so the UI can still look ' +
    'fine — which is exactly why this is asserted on the wire and not on the ' +
    'screen.'
  ).toEqual([]);
});

test('logging out and reloading does NOT silently restore the session', async ({ page }) => {
  // The mirror image of the first test, and the reason it means something: if
  // a reload recovered a session that had been explicitly ended, "recovery"
  // would just be "the cookie is never checked".
  const { viewer } = readAccounts();
  await login(page, viewer);

  await page.getByRole('button', { name: 'Cerrar sesión' }).click();
  await page.waitForURL(/\/login$/);

  await page.reload();
  await esperarDeslogueado(page, 'a reload resurrected a session that had been logged out');
});

test('an anonymous visitor is sent to the login screen, not into the app', async ({ page }) => {
  // No cookie at all: the bootstrap refresh 401s and the app must say so
  // rather than rendering a shell that 401s on every call.
  const golpes = recolectar401(page);
  await page.goto('/catalogo');
  await esperarDeslogueado(page, 'an anonymous visitor reached /catalogo');

  const refrescos = golpes.lista.filter(g => g.includes('/api/auth/refresh'));
  expect(refrescos.length, 'the bootstrap refresh should have been attempted once').toBe(1);
  expect(
    golpes.lista.filter(g => !g.includes('/api/auth/refresh')),
    'the app fired authenticated requests before auth had settled — AuthGate ' +
    'exists precisely to stop RootGate/AppLayout mounting that early'
  ).toEqual([]);
});
