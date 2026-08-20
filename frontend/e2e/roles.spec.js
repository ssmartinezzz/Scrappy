// Role awareness in the running app, checked against ApiRoutePolicy.TABLE.
//
// The rule the whole surface follows is HIDDEN, not disabled: a control whose
// endpoint is ADMIN is not rendered at all for a VIEWER. A disabled button
// still advertises a capability, and a VIEWER clicking it would collect a 403
// the client is required to treat as terminal.
//
// The symmetric error matters just as much and is easier to commit: hiding a
// read a VIEWER is entitled to. /financiacion is the case in point — every
// mutation on it is ADMIN, but GET /api/financiacion/presets is AUTHENTICATED,
// so the page and its nav entry stay visible to everyone and only the mutation
// controls are gated. Both directions are asserted below.
import { expect, test } from '@playwright/test';

import {
  esperarSesionViva,
  login,
  marcadorApp,
  navegarEnLaApp,
  navegarPorElMenu,
  readAccounts,
} from './helpers.js';

const AFFORDANCES_ADMIN = [
  { nombre: 'the Cronjobs nav entry', localizar: p => p.getByText('Cronjobs').first() },
  { nombre: 'the "nuevo scraping" button', localizar: p => p.getByRole('button', { name: /Nuevo scraping/ }) },
  { nombre: 'the agent chat panel', localizar: p => p.locator('[title="Ask Agent"]') },
  { nombre: 'the GPU-training FAB', localizar: p => p.locator('.gpu-fab') },
  { nombre: 'the per-product delete button', localizar: p => p.locator('.card-delete-btn').first() },
];

async function irAlCatalogo(page) {
  // `login()` already lands here via RootGate. Deliberately NOT a `page.goto`:
  // that is a full document load, and it would make this spec depend on
  // cross-origin session recovery as well as on role gating.
  expect(new URL(page.url()).pathname).toBe('/catalogo');
  await expect(marcadorApp(page)).toBeVisible();
  // The per-product delete button only exists once cards have rendered, so an
  // empty grid would make its absence meaningless for BOTH roles.
  await expect(page.locator('.card').first()).toBeVisible({ timeout: 20_000 });
}

test('a VIEWER sees no ADMIN affordance anywhere', async ({ page }) => {
  const { viewer } = readAccounts();
  await login(page, viewer);
  await irAlCatalogo(page);

  for (const { nombre, localizar } of AFFORDANCES_ADMIN) {
    await expect(
      localizar(page),
      `${nombre} is visible to a VIEWER. Every one of these calls an ADMIN row ` +
      'in ApiRoutePolicy.TABLE, so clicking it can only ever produce a 403.'
    ).toHaveCount(0);
  }
});

test('an ADMIN sees all of them', async ({ page }) => {
  // Without this, "a VIEWER sees none of them" is satisfied by an app that
  // renders none of them for anybody — including after a selector goes stale.
  const { admin } = readAccounts();
  await login(page, admin);
  await irAlCatalogo(page);

  for (const { nombre, localizar } of AFFORDANCES_ADMIN) {
    await expect(
      localizar(page),
      `${nombre} is missing for an ADMIN — either the gate is inverted or this ` +
      "spec's selector no longer matches anything, which would silently turn " +
      'the VIEWER test into a tautology.'
    ).toBeVisible();
  }
});

test('/financiacion: a VIEWER keeps the read and loses the mutations', async ({ page }) => {
  const { viewer } = readAccounts();
  await login(page, viewer);
  await navegarPorElMenu(page, 'Análisis', 'Cuotas');
  await esperarSesionViva(page, '/financiacion never came up');

  await expect(
    page.getByText(/Preset activo/),
    'the VIEWER-entitled read disappeared. GET /api/financiacion/presets is ' +
    'AUTHENTICATED — hiding the whole page to hide its buttons is the ' +
    'symmetric error, and costs a VIEWER something they are allowed to see.'
  ).toBeVisible();

  await expect(page.locator('[title="Editar"]')).toHaveCount(0);
  await expect(page.locator('[title="Eliminar"]')).toHaveCount(0);
  await expect(page.getByText('+ Nuevo preset')).toHaveCount(0);
});

test('/financiacion: an ADMIN gets the mutations too', async ({ page }) => {
  const { admin } = readAccounts();
  await login(page, admin);
  await navegarPorElMenu(page, 'Análisis', 'Cuotas');
  await esperarSesionViva(page, '/financiacion never came up');

  await expect(page.getByText(/Preset activo/)).toBeVisible();
  await expect(page.locator('[title="Editar"]').first()).toBeVisible();
  await expect(page.locator('[title="Eliminar"]').first()).toBeVisible();
  await expect(page.getByText('+ Nuevo preset')).toBeVisible();
});

test('a VIEWER reaching /cronjobs in-app is refused explicitly, not redirected', async ({ page }) => {
  // A VIEWER has no Cronjobs nav entry, so this route is only ever reached by
  // URL. Driven here through react-router's own popstate path so the assertion
  // is about RequireRole and nothing else; the cold deep link — the way a
  // bookmark or a shared link actually arrives — is the next test.
  const { viewer } = readAccounts();
  await login(page, viewer);
  await navegarEnLaApp(page, '/cronjobs');

  await expect(page.getByRole('alert')).toContainText('Acceso denegado');
  expect(
    new URL(page.url()).pathname,
    'the VIEWER was redirected away from /cronjobs. A silent redirect makes a ' +
    'permission gap look like a broken link — and a bookmarked or shared URL ' +
    'then behaves differently depending on who opens it, with no explanation.'
  ).toBe('/cronjobs');
});

test('a VIEWER cold-opening /cronjobs is refused explicitly, not redirected', async ({ page }) => {
  // The real deep link: a fresh document load at that URL, which is how a
  // bookmark, a pasted link or a typed address arrives. It needs the session to
  // survive a cold start, so it also depends on login-cookie.spec.js passing.
  const { viewer } = readAccounts();
  await login(page, viewer);
  await page.goto('/cronjobs');

  await expect(page.getByRole('alert')).toContainText('Acceso denegado');
  expect(new URL(page.url()).pathname).toBe('/cronjobs');
});

test('an ADMIN reaching /cronjobs gets the page', async ({ page }) => {
  const { admin } = readAccounts();
  await login(page, admin);
  await navegarEnLaApp(page, '/cronjobs');

  await expect(page.getByRole('alert')).toHaveCount(0);
  expect(new URL(page.url()).pathname).toBe('/cronjobs');
});
