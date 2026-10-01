import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import App from './App';
import { resetSession } from './lib/authSession';

function refreshRejected() {
  return { ok: false, status: 401, json: async () => ({ error: { code: 'refresh_invalido', message: '' } }) };
}

function renderApp(initialPath = '/catalogo') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <App />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  resetSession();
});

afterEach(() => {
  resetSession();
});

function jsonResponse(body, init = {}) {
  return { ok: true, status: 200, json: async () => body, ...init };
}

/** A success response in the API envelope: `{ data, page? }`. */
function ok(data, page) {
  return jsonResponse(page ? { data, page } : { data });
}

function refreshOk() {
  return jsonResponse({ data: { accessToken: 'tok', csrfNonce: 'nonce', expiresIn: 900, tokenType: 'Bearer' } });
}

function meWithRoles(roles) {
  return ok({ username: roles.includes('ADMIN') ? 'admin' : 'viewer', roles });
}

/** Router mock covering everything AppLayout/CronjobsPage/AgentChatPanel touch on mount. */
const SIN_INTERRUMPIDA = { hayInterrumpida: false };

const CON_INTERRUMPIDA = {
  hayInterrumpida: true,
  uuid: '4f1a2b3c-0000-4000-8000-000000000001',
  startedAt: '2026-08-24T18:20:00.049704Z',
  soloFaltaLaPasadaFinal: false,
  atendidos: ['freres', 'vcp'],
  pendientes: ['entreno'],
  salteados: [],
};

function authedRouter({
  roles, tieneData = false,
  // A real backend with data already reports a terminal status, not 'IDLE' —
  // 'IDLE' -> 'IDLE' never trips the [S.scrapeStatus] dependency check, so it
  // couldn't exercise the perf/dedupe-load-requests double-tendencias-fetch
  // bug (T5 below). Overridable for tests that care about a specific value.
  status = tieneData ? 'DONE' : 'IDLE',
  interrumpida = SIN_INTERRUMPIDA,
}) {
  return vi.fn().mockImplementation((url) => {
    const u = String(url);
    if (u.includes('/api/auth/refresh')) return Promise.resolve(refreshOk());
    if (u.includes('/api/auth/me')) return Promise.resolve(meWithRoles(roles));
    if (u.includes('/api/events')) return new Promise(() => {});
    if (u.includes('/api/status')) return Promise.resolve(ok({ tieneData, status, mensaje: '' }));
    if (u.includes('/api/scrape/interrupted')) return Promise.resolve(ok(interrumpida));
    if (u.includes('/api/sitios')) return Promise.resolve(ok({ base: [], extras: [] }));
    if (u.includes('/api/outfits/saved')) return Promise.resolve(ok([]));
    if (u.includes('/api/pcs/saved')) return Promise.resolve(ok([]));
    if (u.includes('/api/ml/estado')) return Promise.resolve(ok({ training: { running: false } }));
    if (u.includes('/api/ml/resultado')) return Promise.resolve(ok({ running: false, done: false }));
    if (u.includes('/api/indices')) return Promise.resolve(ok({ ipc: {}, usd: {}, actualizado: null }));
    if (u.includes('/api/agent/models')) return Promise.resolve(ok({ models: [] }));
    if (u.includes('/api/tendencias')) return Promise.resolve(ok({}));
    if (u.includes('/api/favoritos')) return Promise.resolve(ok([]));
    if (u.includes('/api/facets')) return Promise.resolve(ok({}));
    if (u.includes('/api/data')) return Promise.resolve(ok({ productos: [], meta: {} }, { number: 0, size: 24, total: 0, totalPages: 0 }));
    throw new Error(`unexpected fetch in role-awareness test: ${u}`);
  });
}

describe('App — role-aware UI, hidden not disabled (design D6, spec frontend-role-awareness)', () => {
  it('a VIEWER sees no Cronjobs nav item, no "nuevo scraping" button, and no agent chat FAB', async () => {
    global.fetch = authedRouter({ roles: ['VIEWER'] });

    renderApp('/catalogo');

    await screen.findByText('Catálogo');
    expect(screen.queryByText('Cronjobs')).not.toBeInTheDocument();
    expect(screen.queryByText(/nuevo scraping/i)).not.toBeInTheDocument();
    expect(screen.queryByTitle('Ask Agent')).not.toBeInTheDocument();
  });

  it('an ADMIN sees the Cronjobs nav item, the "nuevo scraping" button, and the agent chat FAB', async () => {
    global.fetch = authedRouter({ roles: ['ADMIN'] });

    renderApp('/catalogo');

    await screen.findByText('Catálogo');
    expect(screen.getByText('Cronjobs')).toBeInTheDocument();
    expect(screen.getByText(/nuevo scraping/i)).toBeInTheDocument();
    // AgentChatPanel is lazy: the FAB mounts one tick after the layout.
    expect(await screen.findByTitle('Ask Agent')).toBeInTheDocument();
  });

  it('a VIEWER deep-linking to /cronjobs renders AccessDenied at that URL — never a redirect', async () => {
    global.fetch = authedRouter({ roles: ['VIEWER'] });

    renderApp('/cronjobs');

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.getByText(/acceso denegado/i)).toBeInTheDocument();
    // CronjobsPage itself never mounted — it would have called GET /api/cron,
    // which authedRouter() doesn't stub, and that would throw. Getting here
    // clean proves RequireRole intercepted before the routed page rendered.
  });

  it('an ADMIN deep-linking to /cronjobs sees the real page, not AccessDenied', async () => {
    global.fetch = vi.fn().mockImplementation((url) => {
      const u = String(url);
      if (u.includes('/api/cron')) return Promise.resolve(ok([]));
      return authedRouter({ roles: ['ADMIN'] })(url);
    });

    renderApp('/cronjobs');

    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
  });

  // apidocs-public-filtered-document: /apidocs lost its RequireRole guard. It
  // is the one route in this file whose access these two tests assert by role
  // and the answer is now "the same for everyone" — what protects the
  // administrative operations is the filtered response body, not the route.
  it.each([['VIEWER'], ['ADMIN']])(
    'a %s deep-linking to /apidocs sees the console, not AccessDenied',
    async (rol) => {
      global.fetch = vi.fn().mockImplementation((url) => {
        const u = String(url);
        if (u.includes('/api/openapi.yaml')) {
          return Promise.resolve({ ok: true, status: 200, text: async () => 'openapi: 3.1.0\npaths: {}\n' });
        }
        return authedRouter({ roles: [rol] })(url);
      });

      renderApp('/apidocs');

      await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
    },
  );

  it('a non-ADMIN landing on /splash (no data yet) sees the empty state, not a scrape button', async () => {
    global.fetch = authedRouter({ roles: ['VIEWER'], tieneData: false });

    renderApp('/splash');

    expect(await screen.findByText(/todavía no hay datos/i)).toBeInTheDocument();
    expect(screen.queryByText(/iniciar scraping/i)).not.toBeInTheDocument();
  });
});

describe('App — a fresh install with no data still knows its ADMIN is an ADMIN', () => {
  it('an ADMIN landing on /splash sees the scrape panel, not the VIEWER empty state', async () => {
    // The path a real first install takes: log in, no products yet, RootGate
    // sends you to /splash. SplashRoute renders its empty state under
    // `!isAdmin` — the screen that tells you to go ask an administrator. An
    // admin reading that about themselves is the whole bug.
    //
    // No test covered this: SplashRoute.test.jsx mocks useAuth to a fixed
    // `isAdmin: true`, and the only /splash case in this file is a VIEWER. The
    // real auth chain landing on that route had never been exercised.
    global.fetch = authedRouter({ roles: ['ADMIN'], tieneData: false });

    renderApp('/');

    expect(await screen.findByText(/iniciar scraping/i)).toBeInTheDocument();
    expect(screen.queryByText(/pedile a un administrador/i)).not.toBeInTheDocument();
  });
});

describe('App — RootGate survives a backend that is not listening', () => {
  it('lands on /splash instead of hanging on the route fallback forever', async () => {
    // `fetchStatus` resolves to null on a non-ok response, but REJECTS when
    // nothing is listening. RootGate only used `.then()`, so a dead backend
    // never reached its callback: `gate` stayed 'checking' and `/` rendered the
    // fallback for as long as the tab stayed open. The entry point of the whole
    // app, bricked by the one failure mode a local install hits most.
    global.fetch = vi.fn().mockImplementation((url) => {
      const u = String(url);
      if (u.includes('/api/auth/refresh')) return Promise.resolve(refreshOk());
      if (u.includes('/api/auth/me')) return Promise.resolve(meWithRoles(['VIEWER']));
      if (u.includes('/api/status')) return Promise.reject(new TypeError('Failed to fetch'));
      if (u.includes('/api/scrape/interrupted')) return Promise.resolve(ok(SIN_INTERRUMPIDA));
      return Promise.resolve(ok({}));
    });

    renderApp('/');

    // Unknowable data means "send them to splash", which already knows how to
    // say the backend is unreachable — not "decide nothing, forever".
    expect(await screen.findByText(/todavía no hay datos/i)).toBeInTheDocument();
  });
});

describe('App — AuthGate wraps the tree above <Routes> (design D5)', () => {
  it('never calls GET /api/status before bootstrap settles — RootGate cannot mount early', async () => {
    let resolveRefresh;
    global.fetch = vi.fn().mockImplementation((url) => {
      if (String(url).includes('/api/auth/refresh')) {
        return new Promise(resolve => { resolveRefresh = resolve; });
      }
      throw new Error(`unexpected fetch before bootstrap settled: ${url}`);
    });

    renderApp('/catalogo');

    await waitFor(() => expect(global.fetch).toHaveBeenCalled());
    const statusCalls = global.fetch.mock.calls.filter(c => String(c[0]).includes('/api/status'));
    expect(statusCalls).toHaveLength(0);

    resolveRefresh(refreshRejected());
  });

  it('an anonymous deep link to a protected route redirects to /login instead of rendering it', async () => {
    global.fetch = vi.fn().mockResolvedValue(refreshRejected());

    renderApp('/catalogo');

    await screen.findByLabelText(/usuario/i);
  });

  it('an anonymous visitor landing on /login sees the login form directly', async () => {
    global.fetch = vi.fn().mockResolvedValue(refreshRejected());

    renderApp('/login');

    await screen.findByLabelText(/usuario/i);
  });
});

describe('App — the interrupted-run banner is ADMIN-only (slice 6, task 6.1/6.2)', () => {
  it('an ADMIN on the catalogue is told a run was left unfinished', async () => {
    // The banner lives at layout level, not on /splash, because /splash is
    // exactly where an ADMIN does NOT land after a crash: the interrupted run
    // committed partial data, so `tieneData` is true and RootGate routes to
    // the catalogue. A banner only /splash carried would never be seen in the
    // one scenario it exists for.
    global.fetch = authedRouter({ roles: ['ADMIN'], tieneData: true, interrumpida: CON_INTERRUMPIDA });

    renderApp('/catalogo');

    expect(await screen.findByText(/quedó una corrida sin terminar/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /retomar/i })).toBeInTheDocument();
  });

  it("a VIEWER's DOM does not contain the banner, and never asks for the offer", async () => {
    global.fetch = authedRouter({ roles: ['VIEWER'], tieneData: true, interrumpida: CON_INTERRUMPIDA });

    renderApp('/catalogo');
    await screen.findByText('Catálogo');

    // Hidden, not disabled — and not even asked. GET /api/scrape/interrupted
    // is ADMIN in ApiRoutePolicy.TABLE, so a VIEWER asking buys a 403 for a
    // question the spec says must never be posed.
    expect(screen.queryByText(/quedó una corrida sin terminar/i)).not.toBeInTheDocument();
    const asked = global.fetch.mock.calls.filter(c => String(c[0]).includes('/api/scrape/interrupted'));
    expect(asked).toHaveLength(0);
  });

  it('shows no banner to an ADMIN when nothing was left unfinished', async () => {
    global.fetch = authedRouter({ roles: ['ADMIN'], tieneData: true });

    renderApp('/catalogo');
    await screen.findByText('Catálogo');

    expect(screen.queryByText(/quedó una corrida sin terminar/i)).not.toBeInTheDocument();
  });

  it('does not redirect the ADMIN anywhere — the catalogue stays put under the banner', async () => {
    // Spec, "ADMIN progress routing without a hard redirect": an ADMIN may be
    // anywhere, including the catalogue, and sees its current partial state.
    // The banner is an offer, never a hijack.
    global.fetch = authedRouter({ roles: ['ADMIN'], tieneData: true, interrumpida: CON_INTERRUMPIDA });

    renderApp('/catalogo');

    await screen.findByText(/quedó una corrida sin terminar/i);
    expect(screen.getByText('Catálogo')).toBeInTheDocument();
    expect(screen.queryByText(/iniciar scraping/i)).not.toBeInTheDocument();
  });
});


describe('App — catalog default order', () => {
  it('the first /api/data request of /catalogo asks for the most expensive products first', async () => {
    global.fetch = authedRouter({ roles: ['VIEWER'], tieneData: true });

    renderApp('/catalogo');

    await waitFor(() => {
      const dataCall = global.fetch.mock.calls.map(([url]) => String(url)).find(u => u.includes('/api/data'));
      expect(dataCall).toBeDefined();
      expect(new URL(dataCall, 'http://x').searchParams.get('orden')).toBe('precio_desc');
    });
  });
});

describe('App — T5: RootGate hands its status to AppLayout (frontend-perf)', () => {
  it('a cold load of "/" with data present drops the AppLayout-side re-read of /api/status', async () => {
    // RootGate already reads /api/status to decide toCatalogo vs toSplash.
    // AppLayout used to read it again on mount to decide whether to load
    // first page/facets/favoritos — two reads of the same fact on one visit.
    // perf/dedupe-load-requests also lifted Topbar's own independent
    // /api/status read up into AppLayout (it now gets the ML banner as a
    // prop instead), so the floor is RootGate alone: RootGate(1) +
    // AppLayout(1) + Topbar(1) = 3 before either fix, RootGate(1) = 1 now.
    global.fetch = authedRouter({ roles: ['ADMIN'], tieneData: true });

    renderApp('/');

    await screen.findByText('Catálogo');

    const statusCalls = global.fetch.mock.calls.filter(c => String(c[0]).includes('/api/status'));
    expect(statusCalls).toHaveLength(1);
  });

  it('a direct load of "/catalogo" (no handed status, e.g. a refresh) still reads status itself', async () => {
    // No RootGate in this path, so nothing is handed — AppLayout still reads
    // it itself. Topbar no longer reads it independently (perf/dedupe-load-
    // requests), so the floor drops from 2 to 1.
    global.fetch = authedRouter({ roles: ['ADMIN'], tieneData: true });

    renderApp('/catalogo');

    await screen.findByText('Catálogo');

    const statusCalls = global.fetch.mock.calls.filter(c => String(c[0]).includes('/api/status'));
    expect(statusCalls).toHaveLength(1);
  });

  it('the no-data path (splash) is unaffected: "/" still lands on splash reading status twice', async () => {
    // RootGate(1) + SplashRoute's useScrapeStatusPolling mount read(1). That
    // second read is the pre-existing "duplicate readStatus()" the task file's
    // Scope section already lists as out of scope — T5 only touches the
    // toCatalogo hand-off, so this path must stay exactly as it was.
    global.fetch = authedRouter({ roles: ['ADMIN'], tieneData: false });

    renderApp('/');

    expect(await screen.findByText(/iniciar scraping/i)).toBeInTheDocument();

    const statusCalls = global.fetch.mock.calls.filter(c => String(c[0]).includes('/api/status'));
    expect(statusCalls).toHaveLength(2);
  });

  it('a cold load of "/" with data present fires /api/ml/estado and /api/tendencias exactly once each', async () => {
    // Two more duplicate reads on the same cold load (perf/dedupe-load-requests):
    // - /api/ml/estado: AppLayout already reads it once (GPU-training
    //   recovery); Topbar read it again on its own mount for the ML banner.
    // - /api/tendencias: the effect keyed on [S.scrapeStatus] fired once for
    //   the reducer's 'IDLE' seed and again when the mount effect set the
    //   real (non-IDLE) status — never a genuine RUNNING -> finished
    //   transition, just the initial read arriving.
    global.fetch = authedRouter({ roles: ['ADMIN'], tieneData: true });

    renderApp('/');

    await screen.findByText('Catálogo');

    const mlEstadoCalls   = global.fetch.mock.calls.filter(c => String(c[0]).includes('/api/ml/estado'));
    const tendenciasCalls = global.fetch.mock.calls.filter(c => String(c[0]).includes('/api/tendencias'));
    expect(mlEstadoCalls).toHaveLength(1);
    expect(tendenciasCalls).toHaveLength(1);
  });
});

describe('App — one status stream for the whole session', () => {
  const eventCalls = () => global.fetch.mock.calls.filter(c => String(c[0]).includes('/api/events'));

  it('opens /api/events once, with the Bearer token and Accept: text/event-stream, however many screens read it', async () => {
    global.fetch = authedRouter({ roles: ['ADMIN'], tieneData: true });

    renderApp('/catalogo');

    await screen.findByText('Catálogo');
    await waitFor(() => expect(eventCalls()).toHaveLength(1));
    const headers = new Headers(eventCalls()[0][1].headers);
    expect(headers.get('Authorization')).toBe('Bearer tok');
    expect(headers.get('Accept')).toBe('text/event-stream');
    expect(eventCalls()[0][1].signal).toBeInstanceOf(AbortSignal);
  });

  it('opens none for an anonymous visitor: there is no token to send', async () => {
    global.fetch = vi.fn().mockResolvedValue(refreshRejected());

    renderApp('/login');

    await screen.findByLabelText(/usuario/i);
    expect(eventCalls()).toHaveLength(0);
  });
});
