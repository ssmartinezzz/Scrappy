import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import App from './App';
import { resetSession } from './lib/authSession';

function refreshRejected() {
  return { ok: false, status: 401, json: async () => ({ error: 'refresh_invalido' }) };
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

function refreshOk() {
  return jsonResponse({ accessToken: 'tok', csrfNonce: 'nonce', expiresIn: 900, tokenType: 'Bearer' });
}

function meWithRoles(roles) {
  return jsonResponse({ username: roles.includes('ADMIN') ? 'admin' : 'viewer', roles });
}

/** Router mock covering everything AppLayout/CronjobsPage/AgentChatPanel touch on mount. */
const SIN_INTERRUMPIDA = { hayInterrumpida: false };

const CON_INTERRUMPIDA = {
  hayInterrumpida: true,
  uuid: '4f1a2b3c-0000-4000-8000-000000000001',
  startedAt: '2026-08-24T18:20:00Z',
  soloFaltaLaPasadaFinal: false,
  atendidos: ['freres', 'vcp'],
  pendientes: ['entreno'],
  salteados: [],
};

function authedRouter({ roles, tieneData = false, interrumpida = SIN_INTERRUMPIDA }) {
  return vi.fn().mockImplementation((url) => {
    const u = String(url);
    if (u.includes('/api/auth/refresh')) return Promise.resolve(refreshOk());
    if (u.includes('/api/auth/me')) return Promise.resolve(meWithRoles(roles));
    if (u.includes('/api/status')) return Promise.resolve(jsonResponse({ tieneData, status: 'IDLE', mensaje: '' }));
    if (u.includes('/api/scrape/interrupted')) return Promise.resolve(jsonResponse(interrumpida));
    if (u.includes('/api/sitios')) return Promise.resolve(jsonResponse({ base: [], extras: [] }));
    if (u.includes('/api/outfits/saved')) return Promise.resolve(jsonResponse([]));
    if (u.includes('/api/ml/estado')) return Promise.resolve(jsonResponse({ training: { running: false } }));
    if (u.includes('/api/ml/resultado')) return Promise.resolve(jsonResponse({ running: false, done: false }));
    if (u.includes('/api/inflacion')) return Promise.resolve(jsonResponse({}));
    if (u.includes('/api/agent/models')) return Promise.resolve(jsonResponse({ models: [] }));
    if (u.includes('/api/tendencias')) return Promise.resolve(jsonResponse({}));
    if (u.includes('/api/favoritos')) return Promise.resolve(jsonResponse([]));
    if (u.includes('/api/facets')) return Promise.resolve(jsonResponse({}));
    if (u.includes('/api/data')) return Promise.resolve(jsonResponse({ productos: [], meta: {} }));
    throw new Error(`unexpected fetch in role-awareness test: ${u}`);
  });
}

describe('App — role-aware UI, hidden not disabled (design D6, spec frontend-role-awareness)', () => {
  it('a VIEWER sees no Cronjobs nav item, no "nuevo scraping" button, and no agent chat FAB', async () => {
    global.fetch = authedRouter({ roles: ['VIEWER'] });

    renderApp('/catalogo');

    await waitFor(() => expect(screen.getByText('Catálogo')).toBeInTheDocument());
    expect(screen.queryByText('Cronjobs')).not.toBeInTheDocument();
    expect(screen.queryByText(/nuevo scraping/i)).not.toBeInTheDocument();
    expect(screen.queryByTitle('Ask Agent')).not.toBeInTheDocument();
  });

  it('an ADMIN sees the Cronjobs nav item, the "nuevo scraping" button, and the agent chat FAB', async () => {
    global.fetch = authedRouter({ roles: ['ADMIN'] });

    renderApp('/catalogo');

    await waitFor(() => expect(screen.getByText('Catálogo')).toBeInTheDocument());
    expect(screen.getByText('Cronjobs')).toBeInTheDocument();
    expect(screen.getByText(/nuevo scraping/i)).toBeInTheDocument();
    expect(screen.getByTitle('Ask Agent')).toBeInTheDocument();
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
      if (u.includes('/api/cron')) return Promise.resolve(jsonResponse([]));
      return authedRouter({ roles: ['ADMIN'] })(url);
    });

    renderApp('/cronjobs');

    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
  });

  it('a non-ADMIN landing on /splash (no data yet) sees the empty state, not a scrape button', async () => {
    global.fetch = authedRouter({ roles: ['VIEWER'], tieneData: false });

    renderApp('/splash');

    expect(await screen.findByText(/todavía no hay datos/i)).toBeInTheDocument();
    expect(screen.queryByText(/iniciar scraping/i)).not.toBeInTheDocument();
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

    await waitFor(() => expect(screen.getByLabelText(/usuario/i)).toBeInTheDocument());
  });

  it('an anonymous visitor landing on /login sees the login form directly', async () => {
    global.fetch = vi.fn().mockResolvedValue(refreshRejected());

    renderApp('/login');

    await waitFor(() => expect(screen.getByLabelText(/usuario/i)).toBeInTheDocument());
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
    await waitFor(() => expect(screen.getByText('Catálogo')).toBeInTheDocument());

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
    await waitFor(() => expect(screen.getByText('Catálogo')).toBeInTheDocument());

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

