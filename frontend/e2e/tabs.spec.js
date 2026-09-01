// Cross-tab coordination: BroadcastChannel for the session, a Web Lock for the
// refresh, and what happens to the tab that did not do the refreshing.
//
// Why "exactly one refresh" is the assertion rather than "everybody survived":
// if two tabs each refresh on their own, the second presents a token the first
// already rotated. That is indistinguishable from theft, so reuse detection
// revokes the family — and the loser's 401 carries a cookie-clearing
// Set-Cookie, which logs BOTH tabs out. The 10-second grace window is a safety
// net for exactly that, not the design; the Web Lock is the design, and a
// window-shaped pass would hide its absence.
import { expect, test } from '@playwright/test';

import {
  contarRefrescos,
  esperarDeslogueado,
  esperarSesionViva,
  login,
  readAccounts,
  recolectar401,
  cerrarSesion,
} from './helpers.js';

test('a second tab adopts the running session instead of refreshing', async ({ context }) => {
  const { viewer } = readAccounts();
  const refrescos = contarRefrescos(context);

  const tabA = await context.newPage();
  await login(tabA, viewer);
  refrescos.reset();

  const tabB = await context.newPage();
  await tabB.goto('/catalogo');
  await esperarSesionViva(tabB, 'the second tab did not inherit the running session');

  expect(
    refrescos.total,
    'the second tab rotated the refresh token instead of asking its sibling. ' +
    'That works today only because of the grace window; the design is the ' +
    'BroadcastChannel probe in authSession.bootstrap().'
  ).toBe(0);
});

test('a tab that adopted a sibling session also knows WHO it is', async ({ context }) => {
  // Adoption copies the access token and the nonce. It must also end with an
  // identity, because `identity.roles` is what every ADMIN affordance in the
  // app is gated on — a tab that is authenticated but role-less renders an
  // ADMIN as if they were a VIEWER, silently.
  const { admin } = readAccounts();

  const tabA = await context.newPage();
  await login(tabA, admin);
  await expect(tabA.getByText('Cronjobs').first()).toBeVisible();

  const tabB = await context.newPage();
  await tabB.goto('/catalogo');
  await esperarSesionViva(tabB, 'the second tab did not inherit the running session');

  await expect(
    tabB.getByText(admin.username).first(),
    'the adopting tab has no identity — authSession.adoptBroadcastSession() ' +
    'copies the token but never fetches /api/auth/me, and nothing broadcasts ' +
    'the identity either'
  ).toBeVisible();

  await expect(
    tabB.getByText('Cronjobs').first(),
    'the second tab of an ADMIN session renders as a VIEWER: no Cronjobs nav ' +
    'node, because identity.roles is empty after adoption'
  ).toBeVisible();
});

test('two tabs cold-starting at once send exactly one refresh', async ({ context }) => {
  const { viewer } = readAccounts();

  const tabA = await context.newPage();
  await login(tabA, viewer);
  const tabB = await context.newPage();
  await tabB.goto('/catalogo');
  await esperarSesionViva(tabB, 'the second tab did not inherit the running session');

  // Both documents are destroyed FIRST, then both are loaded together. A plain
  // `Promise.all([reload, reload])` does not reproduce the race: whichever tab
  // reloads a moment later finds its sibling still alive and simply adopts its
  // session, so zero refreshes happen and the Web Lock is never exercised —
  // a green result that proves nothing. With both blanked, neither can answer
  // the other's probe and the lock is the only thing left to serialize them.
  await Promise.all([tabA.goto('about:blank'), tabB.goto('about:blank')]);

  const refrescos = contarRefrescos(context);
  await Promise.all([tabA.goto('/catalogo'), tabB.goto('/catalogo')]);
  await Promise.all([tabA.waitForLoadState('networkidle'), tabB.waitForLoadState('networkidle')]);

  // Counted before the session is checked, on purpose: if the lock is broken
  // AND the session is broken, the count is the cause and the session is the
  // symptom, and the first failure a reader sees should be the cause.
  expect(
    refrescos.total,
    'more than one refresh reached the backend. The second presents a token ' +
    'the first already rotated, which trips reuse detection against the ' +
    "user's own tabs — and the loser's 401 clears the cookie, logging both out."
  ).toBe(1);

  await esperarSesionViva(tabA, 'tab A did not survive the simultaneous cold start');
  await esperarSesionViva(tabB, 'tab B did not survive the simultaneous cold start');
});

test('logout in one tab closes the other without a doomed request', async ({ context }) => {
  const { viewer } = readAccounts();

  const tabA = await context.newPage();
  await login(tabA, viewer);
  const tabB = await context.newPage();
  await tabB.goto('/catalogo');
  await esperarSesionViva(tabB, 'the second tab did not inherit the running session');

  const golpesEnB = recolectar401(tabB);

  await cerrarSesion(tabA);
  await tabA.waitForURL(/\/login$/);

  await esperarDeslogueado(
    tabB,
    'the sibling tab stayed in the app after the other logged out'
  );

  expect(
    golpesEnB.lista,
    'the sibling only found out it was logged out by making a request and ' +
    'being refused. The `ended` broadcast exists so it never has to.'
  ).toEqual([]);
});
