import { act, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { SplashRoute } from './App';
import { fetchStatus, fetchSitios } from './api';
import { POLL_INTERVAL_MS } from './hooks/useScrapeStatusPolling';

// Isolated from App.test.jsx on purpose: this one measures the POLL INTERVAL,
// which needs fake timers, and the auth bootstrap chain does not drain under
// them — the baseline read zero and the assertion passed on the mount read
// alone, measuring nothing.
vi.mock('./auth/AuthProvider', () => ({ useAuth: () => ({ isAdmin: true }) }));
vi.mock('./api', () => ({
  fetchStatus: vi.fn(),
  fetchSitios: vi.fn(),
  startScrape: vi.fn(),
  limpiarCatalogo: vi.fn(),
  limpiarMl: vi.fn(),
  fetchInterrumpida: vi.fn(() => Promise.resolve({ hayInterrumpida: false })),
  retomarScrape: vi.fn(),
}));
vi.mock('./components/MlStatusPanel', () => ({ default: () => null }));

const RUNNING = {
  status: 'RUNNING', mensaje: 'Scrapeando entreno', tieneData: true,
  progreso: { total: 3, completados: 1, sitios: [] },
};
const IDLE = { status: 'IDLE', mensaje: '', tieneData: true };

async function flush(ms) {
  await act(async () => { await vi.advanceTimersByTimeAsync(ms); });
}

function renderSplash() {
  return render(<MemoryRouter initialEntries={['/splash']}><SplashRoute/></MemoryRouter>);
}

beforeEach(() => {
  vi.useFakeTimers();
  fetchStatus.mockReset();
  fetchSitios.mockReset().mockResolvedValue({ base: [], extras: [] });
});

afterEach(() => { vi.useRealTimers(); });

describe('SplashRoute — polls a run it did not start (slice 6, task 6.3)', () => {
  it('keeps polling when it lands on a run already RUNNING', async () => {
    // Landing here after a resume: the run is live and this tab never launched
    // it. Only handleScrape used to arm the poller, so the mount read wrote
    // RUNNING to the screen and stopped — a frozen status with no progress and
    // no completion, for as long as the tab stayed open.
    fetchStatus.mockResolvedValue(RUNNING);

    renderSplash();
    await flush(0);
    const afterMount = fetchStatus.mock.calls.length;
    expect(afterMount).toBe(1); // the mount read happened; the baseline is real

    await flush(POLL_INTERVAL_MS * 3);

    expect(fetchStatus.mock.calls.length).toBeGreaterThan(afterMount);
  });

  it('does not poll when it lands with no run in flight', async () => {
    fetchStatus.mockResolvedValue(IDLE);

    renderSplash();
    await flush(0);
    expect(fetchStatus.mock.calls.length).toBe(1);

    await flush(POLL_INTERVAL_MS * 3);

    expect(fetchStatus.mock.calls.length).toBe(1);
  });

  it('shows the live progress of that run instead of a frozen status line', async () => {
    fetchStatus.mockResolvedValue(RUNNING);

    renderSplash();
    await flush(POLL_INTERVAL_MS);

    expect(screen.getByText('Scrapeando entreno')).toBeInTheDocument();
  });
});
