// A progress bar that keeps advancing over a backend that stopped answering is
// worse than no progress bar: it is a screen that lies, and it lies for as long
// as the tab stays open. Someone watched one all night.
//
// The status used to be POLLED, and this spec guarded the poller against a `fetch` that REJECTS
// instead of resolving non-ok. The server now PUSHES it over /api/events, so the same failure is
// a stream that breaks, and the spec asserts the same two things: the screen says it cannot reach
// the server, and it stops claiming progress. It also asserts the new claim — that a pushed event
// moves the screen with no status request behind it.
//
// Only the events endpoint is a stand-in (see event-stream-stub.js); POST /api/scrape and the
// one-shot GET /api/status are stubbed because this is about the UI, not about crawling 26 live
// stores.
import { expect, test } from '@playwright/test';

import {
  conexionesAbiertas, cortarElStream, empujar, fijarElSnapshot, instalarElStreamFalso,
  restaurarElStream, SNAPSHOT_IDLE,
} from './event-stream-stub.js';
import {
  cortarElBackend,
  login,
  navegarEnLaApp,
  readAccounts,
  restaurarElBackend,
} from './helpers.js';

const MENSAJE_RED = /No se pudo contactar al servidor/;
const MENSAJE_PROGRESO = 'Scrapeando el sitio de prueba';
const BACKOFF_MAXIMO_MS = 2_000; // 1 s for the first retry, 2 s for the second, plus the round trip

const IDLE = { status: 'IDLE', mensaje: '', progreso: null, tieneData: true };
const RUNNING = {
  status: 'RUNNING',
  mensaje: MENSAJE_PROGRESO,
  progreso: { total: 3, completados: 1, productos: 12, sitios: [] },
  tieneData: true,
};

async function prepararElSplash({ page, context }) {
  const { admin } = readAccounts();
  const lecturas = { status: 0 };
  let status = IDLE;

  await instalarElStreamFalso(context);
  await context.route('**/api/status*', route => {
    lecturas.status++;
    return route.fulfill({ json: { data: status } });
  });
  await context.route('**/api/scrape*', route => route.fulfill({ json: { data: { ok: true } } }));

  await login(page, admin);
  await navegarEnLaApp(page, '/splash');
  const iniciar = page.getByRole('button', { name: /Iniciar scraping/ });
  await expect(iniciar).toBeEnabled();

  return { iniciar, lecturas, correr: () => { status = RUNNING; } };
}

test('the splash follows a run from pushed events, without asking for the status again', async ({ page, context }) => {
  const { iniciar, lecturas, correr } = await prepararElSplash({ page, context });

  const alLlegar = lecturas.status;
  correr();
  await iniciar.click();
  await empujar(page, 'scrape.status', { status: 'RUNNING', mensaje: MENSAJE_PROGRESO });
  await expect(
    page.getByText(MENSAJE_PROGRESO),
    'the run never reached RUNNING, so the rest of this test would prove nothing'
  ).toBeVisible();

  // Launching costs ONE reconcile read, because a rejected POST pushes nothing. From here on
  // the screen must move on pushes alone.
  await expect.poll(() => lecturas.status).toBeGreaterThan(alLlegar);
  const antes = lecturas.status;
  await empujar(page, 'scrape.status', { status: 'RUNNING', mensaje: 'Scrapeando vcp' });
  await expect(page.getByText('Scrapeando vcp')).toBeVisible();
  await empujar(page, 'scrape.progress', { total: 3, completados: 2, productos: 40, sitios: [] });
  await page.waitForTimeout(4_000); // longer than the 1.8 s interval this used to tick on

  expect(
    lecturas.status,
    'something asked for /api/status while a run was being followed: the poll is back'
  ).toBe(antes);

  await empujar(page, 'scrape.status', { status: 'DONE', mensaje: 'Listo' });
  await page.waitForURL(/\/catalogo/);
});

test('the splash leaves RUNNING within the backoff when the backend stops answering', async ({ page, context }) => {
  const { iniciar, correr } = await prepararElSplash({ page, context });

  correr();
  await iniciar.click();
  await empujar(page, 'scrape.status', { status: 'RUNNING', mensaje: MENSAJE_PROGRESO });
  await expect(
    page.getByText(MENSAJE_PROGRESO),
    'the run never reached RUNNING, so the rest of this test would prove nothing'
  ).toBeVisible();

  await cortarElBackend(context);
  await cortarElStream(page);

  await expect(
    page.getByText(MENSAJE_RED),
    'the backend stopped answering and the screen never said so'
  ).toBeVisible({ timeout: BACKOFF_MAXIMO_MS * 3 });
  await expect(
    page.getByText(MENSAJE_PROGRESO),
    'the progress line survived the backend. This is the bug: the last good ' +
    'status stays frozen on screen, still claiming a run is advancing.'
  ).toHaveCount(0);

  // Unreachable is a state, not a dead end: the stream keeps retrying, so the screen repairs
  // itself with the snapshot of the first connection that gets through.
  await fijarElSnapshot(page, { ...SNAPSHOT_IDLE, status: { ...RUNNING } });
  await restaurarElBackend(context);
  await restaurarElStream(page);
  await expect(page.getByText(MENSAJE_PROGRESO)).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText(MENSAJE_RED)).toHaveCount(0);
  expect(await conexionesAbiertas(page)).toBeGreaterThan(1);
});
