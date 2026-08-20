// "I cannot reach the backend" and "you are logged out" are different
// sentences, and the app has to say the right one.
//
// Conflating them costs an hour of misdiagnosis every time: the operator reads
// "session expired", goes looking at tokens and cookies, and the backend was
// simply not running. authSession.performRefresh() keeps them apart on purpose
// — a network error sets `lastFailureReason = 'network_error'` and deliberately
// does NOT clear the session or broadcast `ended`, because nothing about an
// unreachable server says the session is over.
import { expect, test } from '@playwright/test';

import {
  cortarElBackend,
  esperarDeslogueado,
  login,
  readAccounts,
  restaurarElBackend,
} from './helpers.js';

const MENSAJE_RED = /No se pudo contactar al backend/;
const MENSAJE_CREDENCIALES = /Usuario o contraseña incorrectos/;

test('an unreachable backend reads as "cannot reach it", not as "logged out"', async ({
  page,
  context,
}) => {
  const { viewer } = readAccounts();
  await login(page, viewer);

  await cortarElBackend(context);
  await page.reload();

  await esperarDeslogueado(page, 'the app should fall back to the login screen');
  await expect(
    page.getByText(MENSAJE_RED),
    'the app did not say the backend was unreachable. Without this the user ' +
    'sees an ordinary login screen and concludes their session expired, which ' +
    'sends them looking at the wrong thing entirely.'
  ).toBeVisible();

  await restaurarElBackend(context);
});

test('a genuine logout does NOT claim the backend is unreachable', async ({ page }) => {
  // The contrast that makes the previous test mean something: if the network
  // banner were always shown, "it says the right thing" would be free.
  const { viewer } = readAccounts();
  await login(page, viewer);

  await page.getByRole('button', { name: 'Cerrar sesión' }).click();
  await page.waitForURL(/\/login$/);
  await esperarDeslogueado(page, 'logout should land on the login screen');

  await expect(page.getByText(MENSAJE_RED)).toHaveCount(0);
});

test('a login attempt against an unreachable backend blames the network, not the password', async ({
  page,
  context,
}) => {
  const { viewer } = readAccounts();
  await page.goto('/login');
  await cortarElBackend(context);

  await page.fill('#login-username', viewer.username);
  await page.fill('#login-password', viewer.password);
  await page.getByRole('button', { name: 'Ingresar' }).click();

  await expect(page.getByText(MENSAJE_RED)).toBeVisible();
  await expect(
    page.getByText(MENSAJE_CREDENCIALES),
    'correct credentials were reported as wrong because the server was down. ' +
    'Every login failure is deliberately identical, but a network error is the ' +
    'one thing that must stay distinguishable — it is not a failure of theirs.'
  ).toHaveCount(0);

  await restaurarElBackend(context);
});
