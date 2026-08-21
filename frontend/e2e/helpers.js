// Shared drivers for the browser suite.
import { expect } from '@playwright/test';

import { API_ORIGIN, APP_ORIGIN, readAccounts } from './accounts.js';

export { API_ORIGIN, APP_ORIGIN, readAccounts };

/**
 * Either half of the only two states the app ever settles into.
 *
 * Waiting for one of these before asserting is not politeness, it is
 * correctness: while `AuthGate` is in its `booting` state it renders a
 * fallback with NEITHER the login form nor the topbar, so a bare
 * `expect(loginField).toHaveCount(0)` passes during the boot window and then
 * the app drops to /login a moment later. That false pass hid a real failure
 * for the first draft of this suite — every assertion about "am I logged in"
 * goes through `esperarQueAsiente` for that reason.
 */
export const CAMPO_LOGIN = '#login-username';

/**
 * The app shell is up — deliberately NOT the user menu.
 *
 * The user menu only renders when `identity` is set, and there is a state in
 * which the app is running with a valid access token and no identity at all
 * (a tab that adopted a sibling's session; see tabs.spec.js). Settling on the
 * user menu would make every one of those tests die here, on a timeout, instead
 * of at the assertion that names what is wrong.
 */
export function marcadorApp(page) {
  return page.getByText('Catálogo').first();
}

export function marcadorAutenticado(page) {
  return page.getByRole('button', { name: 'Cerrar sesión' });
}

export async function esperarQueAsiente(page) {
  await expect(
    page.locator(CAMPO_LOGIN).or(marcadorApp(page)).first()
  ).toBeVisible({ timeout: 25_000 });
}

/** Settle, then insist the app — not the login screen — is what came up. */
export async function esperarSesionViva(page, mensaje) {
  await esperarQueAsiente(page);
  await expect(page.locator(CAMPO_LOGIN), mensaje).toHaveCount(0);
}

/** Settle, then insist the app is showing the login screen. */
export async function esperarDeslogueado(page, mensaje) {
  await esperarQueAsiente(page);
  await expect(page.locator(CAMPO_LOGIN), mensaje).toHaveCount(1);
}

/** Log in through the real form and wait until the app itself has taken over. */
export async function login(page, cuenta) {
  await page.goto('/login');
  await page.fill('#login-username', cuenta.username);
  await page.fill('#login-password', cuenta.password);
  await page.getByRole('button', { name: 'Ingresar' }).click();

  // RootGate decides where a fresh visit lands: /catalogo when the database
  // already has products, /splash when it does not. Accept either — this
  // helper's contract is "logged in", not "on a particular screen".
  await page.waitForURL(url => !url.pathname.startsWith('/login'), { timeout: 25_000 });
  await esperarSesionViva(page, 'the login form submitted but the app never came up');
  // A tab that logged in for itself always has an identity: `login()` awaits
  // `fetchIdentity()`. Asserted here so the tab specs can attribute a MISSING
  // identity to adoption and to nothing else.
  await expect(marcadorAutenticado(page)).toBeVisible();
}

/** Every request the whole context sends to POST /api/auth/refresh. */
export function contarRefrescos(context) {
  const urls = [];
  context.on('request', req => {
    if (req.method() === 'POST' && req.url().startsWith(`${API_ORIGIN}/api/auth/refresh`)) {
      urls.push(req.url());
    }
  });
  return {
    get total() { return urls.length; },
    reset() { urls.length = 0; },
  };
}

/** Every 401 the page receives. "Doomed request" means exactly one of these. */
export function recolectar401(page) {
  const golpes = [];
  page.on('response', res => {
    if (res.status() === 401) golpes.push(`${res.request().method()} ${res.url()}`);
  });
  return {
    get lista() { return [...golpes]; },
    reset() { golpes.length = 0; },
  };
}

/**
 * Cuts the page off from the backend the way a stopped process does.
 *
 * `connectionrefused` is what the OS answers when nothing is listening, so the
 * page's `fetch` rejects with the identical TypeError it gets from a killed
 * backend. Aborting a route rather than actually stopping the backend keeps
 * one test from taking the whole shared stack down with it; the code path
 * under test — `performRefresh`'s network-error branch — cannot tell the
 * difference, because it only ever sees the rejected promise.
 */
export async function cortarElBackend(context) {
  await context.route(`${API_ORIGIN}/**`, route => route.abort('connectionrefused'));
}

export async function restaurarElBackend(context) {
  await context.unroute(`${API_ORIGIN}/**`);
}

/**
 * Client-side navigation through the real menubar.
 *
 * Used wherever the subject under test is NOT "can this URL be opened cold".
 * `page.goto` is a full document load: it destroys the tab's in-memory session
 * and makes every assertion downstream of it depend on cross-origin session
 * recovery as well as on whatever the test is actually about. Clicking through
 * the nav is both what a user does and the narrower experiment.
 */
export async function navegarPorElMenu(page, menu, item) {
  await page.getByText(menu).first().click();
  await page.getByRole('menuitem', { name: item }).click();
}

/**
 * A history navigation that stays inside the loaded document.
 *
 * react-router's BrowserRouter renders from `popstate`, so pushing an entry and
 * dispatching the event is a genuine in-app route change — the same code path
 * a back/forward button takes — without the full reload `page.goto` forces.
 */
export async function navegarEnLaApp(page, ruta) {
  await page.evaluate(r => {
    window.history.pushState({}, '', r);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, ruta);
}
