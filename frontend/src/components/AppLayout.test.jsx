// perf/dedupe-load-requests: the /api/tendencias effect used to be keyed on
// [S.scrapeStatus] alone, which fired once for the reducer's 'IDLE' seed and
// once more the instant the mount status read arrived — never a real "a
// scrape just finished" signal. These tests exercise the fixed effect
// directly through AppLayout's own outlet-context `set` (the same setter
// startPolling uses at ~AppLayout.jsx:746) rather than through the full
// splash -> catalogo flow, since nothing currently wires AppLayout's own
// startPolling to a route.
import { act, render, screen, waitFor } from '@testing-library/react';
import { fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useOutletContext } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import AppLayout from './AppLayout';
import { useAuth } from '../auth/AuthProvider';

vi.mock('../auth/AuthProvider', () => ({ useAuth: vi.fn() }));

function jsonResponse(body) {
  return { ok: true, status: 200, json: async () => body };
}

function baseRouter() {
  return vi.fn().mockImplementation((url) => {
    const u = String(url);
    if (u.includes('/api/status'))       return Promise.resolve(jsonResponse({ tieneData: false, status: 'IDLE', mensaje: '' }));
    if (u.includes('/api/ml/estado'))    return Promise.resolve(jsonResponse({ training: { running: false } }));
    if (u.includes('/api/ml/resultado')) return Promise.resolve(jsonResponse({ running: false, done: false }));
    if (u.includes('/api/indices'))      return Promise.resolve(jsonResponse({ ipc: {}, usd: {}, actualizado: null }));
    if (u.includes('/api/outfits/saved')) return Promise.resolve(jsonResponse([]));
    if (u.includes('/api/pcs/saved'))     return Promise.resolve(jsonResponse([]));
    if (u.includes('/api/tendencias'))    return Promise.resolve(jsonResponse({}));
    // Load-first-page effect (AppLayout.jsx ~664) fires on every mount since
    // `pag` starts at 1 — unrelated to this file's tendencias-only concern.
    if (u.includes('/api/data'))          return Promise.resolve(jsonResponse({ productos: [], meta: {} }));
    throw new Error(`unexpected fetch in AppLayout test: ${u}`);
  });
}

// Stands in for a real route under AppLayout's <Outlet>, exposing `set` the
// same way startPolling (AppLayout.jsx:746) would call it on a real status
// transition.
function ProbeRoute() {
  const { set } = useOutletContext();
  return (
    <>
      <button onClick={() => set({ scrapeStatus: 'RUNNING' })}>go-running</button>
      <button onClick={() => set({ scrapeStatus: 'DONE' })}>go-done</button>
    </>
  );
}

function renderLayout() {
  return render(
    <MemoryRouter initialEntries={['/catalogo']}>
      <Routes>
        <Route element={<AppLayout />}>
          <Route path="/catalogo" element={<ProbeRoute />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

function tendenciasCallCount(fetchMock) {
  return fetchMock.mock.calls.filter(c => String(c[0]).includes('/api/tendencias')).length;
}

describe('AppLayout — /api/tendencias refetches only on a real RUNNING -> finished transition', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('fetches /api/tendencias exactly once on a cold mount', async () => {
    useAuth.mockReturnValue({ isAdmin: false, identity: null, logout: vi.fn() });
    global.fetch = baseRouter();

    renderLayout();

    await waitFor(() => expect(tendenciasCallCount(global.fetch)).toBe(1));
  });

  it('a plain IDLE -> RUNNING move (not a finish) does not refetch', async () => {
    useAuth.mockReturnValue({ isAdmin: false, identity: null, logout: vi.fn() });
    global.fetch = baseRouter();

    renderLayout();
    await waitFor(() => expect(tendenciasCallCount(global.fetch)).toBe(1));

    fireEvent.click(screen.getByText('go-running'));

    expect(tendenciasCallCount(global.fetch)).toBe(1);
  });

  it('refetches exactly once on a RUNNING -> DONE transition', async () => {
    useAuth.mockReturnValue({ isAdmin: false, identity: null, logout: vi.fn() });
    global.fetch = baseRouter();

    renderLayout();
    await waitFor(() => expect(tendenciasCallCount(global.fetch)).toBe(1));

    fireEvent.click(screen.getByText('go-running'));
    await act(async () => { fireEvent.click(screen.getByText('go-done')); });

    await waitFor(() => expect(tendenciasCallCount(global.fetch)).toBe(2));
  });
});
