// Browser end-to-end suite — frontend-auth-ui, Phase 8, layer 2.
//
// THE TOPOLOGY IS THE POINT, so it is asserted here before a single test is
// collected. These specs drive the SPA as `npm run preview` serves it on
// :5173, built with VITE_API_BASE_URL pointing at the backend on :3000 —
// the cross-origin shape both shipped installs have (portable/POSIX :5173,
// Docker :8080; Engram discovery #926).
//
// `vite dev` is NOT an option and this config cannot accidentally start it.
// Its `server.proxy` makes the SPA same-origin with the backend, which is the
// one topology this project never ships, and under it `Origin` checking,
// `SameSite` and the whole bootstrap admission path stop being observable —
// exactly the blind spot that let a broken `Sec-Fetch-Site: same-origin`
// mechanism get recommended before anybody measured the real topologies.
//
// The `webServer` below runs `vite preview` and reuses an already-running one,
// so tests/e2e/run-e2e.sh (which starts the backend too) stays the one-command
// path. A preview server can still be serving a STALE `dist/` built without
// VITE_API_BASE_URL — that bundle would talk to :5173/api and quietly be
// same-origin — so `e2e/topology.spec.js` proves at runtime that the loaded
// bundle really does address the backend cross-origin.
import { defineConfig, devices } from '@playwright/test';

const APP_ORIGIN = process.env.E2E_APP_ORIGIN || 'http://localhost:5173';
const API_ORIGIN = process.env.E2E_API_BASE_URL || 'http://localhost:3000';

if (new URL(APP_ORIGIN).origin === new URL(API_ORIGIN).origin) {
  throw new Error(
    `SAME-ORIGIN TOPOLOGY DETECTED — refusing to run.\n` +
    `  E2E_APP_ORIGIN   = ${APP_ORIGIN}\n` +
    `  E2E_API_BASE_URL = ${API_ORIGIN}\n` +
    `These must be different origins. Every installation this project ships is\n` +
    `cross-origin; only \`vite dev\`, with its /api proxy, is same-origin, and a\n` +
    `green run there would prove nothing about Origin checking, SameSite, or\n` +
    `the bootstrap admission path.`
  );
}

const APP_PORT = new URL(APP_ORIGIN).port || '5173';

export default defineConfig({
  testDir: './e2e',
  outputDir: './e2e/.artifacts/test-results',

  // Serial, single worker, no retries — on purpose, three times over. These
  // specs share one backend, one Postgres and one set of accounts; they open
  // several tabs inside ONE browser context to exercise cross-tab
  // coordination, which is meaningless if a parallel worker is mutating the
  // same session; and a retry would paper over exactly the flakiness (a lost
  // BroadcastChannel message, a lock not held) that is the behaviour under test.
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  timeout: 45_000,
  expect: { timeout: 10_000 },

  globalSetup: './e2e/global-setup.js',
  globalTeardown: './e2e/global-teardown.js',

  use: {
    baseURL: APP_ORIGIN,
    trace: 'retain-on-failure',
    video: 'off',
    ...devices['Desktop Chrome'],
  },

  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],

  webServer: {
    // `preview`, never `dev`. Vite's preview server serves the built bundle
    // and has no /api proxy, which is what keeps the run cross-origin.
    command: `npm run preview -- --port ${APP_PORT} --strictPort`,
    url: APP_ORIGIN,
    reuseExistingServer: true,
    timeout: 60_000,
    stdout: 'ignore',
    stderr: 'pipe',
  },
});
