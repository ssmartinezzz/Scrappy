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
