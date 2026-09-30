// The real /api/events, through the real SPA, cross-origin. The unit suites cannot see what this
// checks: that the endpoint's CORS answer lets the browser send Authorization on a streaming
// GET, that the stream opens only for a session that has a token, that an expired token is
// refreshed BEFORE the stream starts, and that logging out closes it.
import { expect, test } from '@playwright/test';

import {
  APP_ORIGIN, CAMPO_LOGIN, cerrarSesion, contarRefrescos, esperarQueAsiente, login, navegarEnLaApp, readAccounts,
} from './helpers.js';

const esEventos = url => new URL(url).pathname === '/api/events';

test('a logged-in session gets the stream from the real backend, unbuffered', async ({ page }) => {
  const { viewer } = readAccounts();
  const respuesta = page.waitForResponse(r => esEventos(r.url()));

  await login(page, viewer);

  const r = await respuesta;
  expect(r.status()).toBe(200);
  expect(r.headers()['content-type']).toMatch(/text\/event-stream/);
  expect(
    r.headers()['x-accel-buffering'],
    'without this a proxy in front of the backend buffers the stream and pushes arrive late'
  ).toBe('no');
});

test('an anonymous visitor opens no stream', async ({ page }) => {
  const pedidos = [];
  page.on('request', req => { if (esEventos(req.url())) pedidos.push(req.url()); });

  await page.goto('/login');
  await esperarQueAsiente(page);
  await page.waitForTimeout(1_000);

  expect(pedidos).toEqual([]);
});

test('an expired token is refreshed once before the stream opens, and the stream then connects', async ({ page, context }) => {
  const { viewer } = readAccounts();
  const refrescos = contarRefrescos(context);
  const estados = [];
  page.on('response', r => { if (esEventos(r.url())) estados.push(r.status()); });

  // The first connection is refused the way an expired access token is.
  let rechazado = false;
  await context.route('**/api/events', route => {
    if (rechazado) return route.continue();
    rechazado = true;
    return route.fulfill({
      status: 401,
      contentType: 'application/json',
      headers: { 'Access-Control-Allow-Origin': APP_ORIGIN, 'Access-Control-Allow-Credentials': 'true' },
      body: JSON.stringify({ error: { code: 'token_invalido', message: 'expired' } }),
    });
  });

  await page.goto('/login');
  await expect(page.locator(CAMPO_LOGIN)).toBeVisible();
  await page.fill('#login-username', viewer.username);
  await page.fill('#login-password', viewer.password);
  refrescos.reset();
  await page.getByRole('button', { name: 'Ingresar' }).click();

  await expect.poll(() => estados, { timeout: 15_000 }).toEqual([401, 200]);
  expect(refrescos.total, 'the 401 must trigger exactly one refresh, not none and not a loop').toBe(1);
});

test('logging out closes the stream', async ({ page }) => {
  const { viewer } = readAccounts();
  const respuesta = page.waitForResponse(r => esEventos(r.url()));
  await login(page, viewer);
  await respuesta;
  const cerrados = [];
  page.on('requestfailed', req => { if (esEventos(req.url())) cerrados.push(req.failure()?.errorText); });

  // In-app, not page.goto: a reload would close the stream by itself and prove nothing about logout.
  await navegarEnLaApp(page, '/catalogo');
  await expect(page.getByRole('button', { name: /^Sesión de / })).toBeVisible();
  await cerrarSesion(page);
  await page.waitForURL(/\/login$/);

  await expect.poll(() => cerrados.length, {
    message: 'the stream stayed open after logout',
    timeout: 10_000,
  }).toBeGreaterThan(0);
});
