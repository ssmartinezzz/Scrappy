// A progress bar that keeps advancing over a backend that stopped answering is
// worse than no progress bar: it is a screen that lies, and it lies for as long
// as the tab stays open. Someone watched one all night.
//
// This is the browser-level half of slice 0. The unit tests prove the hook's
// state machine; only a real browser proves the thing that actually broke — a
// `fetch` REJECTING, not resolving to a non-ok response. `fetchStatus` returns
// null on a non-ok response, so the old `if (!st) return;` looked like it
// covered this. It did not: with nothing listening, `authedFetch`'s raw `fetch`
// rejects, the interval callback dies with an unhandled rejection, and the last
// good RUNNING stays frozen on screen forever.
import { expect, test } from '@playwright/test';

import {
  cortarElBackend,
  login,
  navegarEnLaApp,
  readAccounts,
  restaurarElBackend,
} from './helpers.js';

const POLL_INTERVAL_MS = 1800;
const MENSAJE_RED = /No se pudo contactar al servidor/;
const MENSAJE_PROGRESO = 'Scrapeando el sitio de prueba';

const IDLE = { status: 'IDLE', mensaje: '', progreso: null, tieneData: true };
const RUNNING = {
  status: 'RUNNING',
  mensaje: MENSAJE_PROGRESO,
  progreso: { total: 3, completados: 1, sitios: [] },
  tieneData: true,
};

test('the splash leaves RUNNING within one poll when the backend stops answering', async ({
  page,
  context,
}) => {
  const { admin } = readAccounts();

  // POST /api/scrape is stubbed because this test is about the poller, not
  // about scraping: the real endpoint would go out and crawl 26 live stores.
  // GET /api/status is stubbed so the run reaches RUNNING deterministically
  // instead of depending on how fast a real crawl gets going.
  let status = IDLE;
  await context.route('**/api/status*', route => route.fulfill({ json: status }));
  await context.route('**/api/scrape*', route => route.fulfill({ json: { ok: true } }));

  await login(page, admin);
  await navegarEnLaApp(page, '/splash');

  status = RUNNING;
  await page.getByRole('button', { name: /Iniciar scraping/ }).click();
  await expect(
    page.getByText(MENSAJE_PROGRESO),
    'the run never reached RUNNING, so the rest of this test would prove nothing'
  ).toBeVisible();

  await cortarElBackend(context);

  await expect(
    page.getByText(MENSAJE_RED),
    'the backend stopped answering and the screen never said so'
  ).toBeVisible({ timeout: POLL_INTERVAL_MS * 3 });
  await expect(
    page.getByText(MENSAJE_PROGRESO),
    'the progress line survived the backend. This is the bug: the last good ' +
    'status stays frozen on screen, still claiming a run is advancing.'
  ).toHaveCount(0);

  // Unreachable is a state, not a dead end — the interval deliberately keeps
  // running so the screen repairs itself the moment the backend comes back.
  await restaurarElBackend(context);
  await expect(page.getByText(MENSAJE_PROGRESO)).toBeVisible({ timeout: POLL_INTERVAL_MS * 3 });
  await expect(page.getByText(MENSAJE_RED)).toHaveCount(0);
});
