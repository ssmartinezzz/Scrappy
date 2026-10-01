import { act, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { SplashRoute } from './App';
import { EventStreamProvider } from './hooks/EventStreamProvider';
import { fetchStatus, fetchSitios } from './api';
import { fakeStream } from './test/fakeEventStream';

// Isolated from App.test.jsx on purpose: this one asserts that NO status read repeats, which
// needs fake timers, and the auth bootstrap chain does not drain under them.
vi.mock('./auth/AuthProvider', () => ({
  useAuth: () => ({ isAdmin: true, authenticated: true, identity: { username: 'admin' } }),
}));
vi.mock('./api', () => ({
  fetchStatus: vi.fn(),
  fetchSitios: vi.fn(),
  fetchMlEstado: vi.fn(() => Promise.resolve(null)),
  openEventStream: vi.fn(),
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
const ML = { training: { running: false, phase: 'idle', pct: 0, msg: '', startedAt: '' } };

async function flush(ms) {
  await act(async () => { await vi.advanceTimersByTimeAsync(ms); });
}

let stream;

function renderSplash() {
  stream = fakeStream();
  const open = vi.fn().mockResolvedValue(stream.response);
  return render(
    <MemoryRouter initialEntries={['/splash']}>
      <EventStreamProvider open={open}>
        <Routes>
          <Route path="/splash" element={<SplashRoute/>}/>
          <Route path="/catalogo" element={<div>catalogo abierto</div>}/>
        </Routes>
      </EventStreamProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.useFakeTimers();
  fetchStatus.mockReset();
  fetchSitios.mockReset().mockResolvedValue({ base: [], extras: [] });
});

afterEach(() => { vi.useRealTimers(); });

describe('SplashRoute — follows a run it did not start (slice 6, task 6.3)', () => {
  it('keeps following it when it lands on a run already RUNNING: the stream says it finished and it leaves', async () => {
    // Landing here after a resume: the run is live and this tab never launched it. The mount
    // read wrote RUNNING to the screen and nothing ever told it the run had ended.
    fetchStatus.mockResolvedValue(RUNNING);

    renderSplash();
    await flush(0);
    expect(fetchStatus.mock.calls.length).toBe(1); // the mount read happened; the baseline is real
    expect(screen.queryByText('catalogo abierto')).toBeNull();

    stream.frame('snapshot', { status: RUNNING, ml: ML });
    await flush(0);
    stream.frame('scrape.status', { status: 'DONE', mensaje: 'Listo' });
    await flush(0);

    expect(screen.getByText('catalogo abierto')).toBeInTheDocument();
  });

  it('does not navigate, and reads nothing again, when it lands with no run in flight', async () => {
    fetchStatus.mockResolvedValue(IDLE);

    renderSplash();
    await flush(0);
    expect(fetchStatus.mock.calls.length).toBe(1);
    stream.frame('snapshot', { status: IDLE, ml: ML });
    await flush(0);

    await flush(1800 * 3);

    expect(fetchStatus.mock.calls.length).toBe(1);
    expect(screen.queryByText('catalogo abierto')).toBeNull();
  });

  it('shows the live progress of that run and follows it without a single further status read', async () => {
    fetchStatus.mockResolvedValue(RUNNING);

    renderSplash();
    await flush(0);
    expect(screen.getByText('Scrapeando entreno')).toBeInTheDocument();

    stream.frame('snapshot', { status: RUNNING, ml: ML });
    await flush(0);
    stream.frame('scrape.status', { status: 'RUNNING', mensaje: 'Scrapeando vcp' });
    await flush(1800 * 3);

    expect(screen.getByText('Scrapeando vcp')).toBeInTheDocument();
    expect(fetchStatus.mock.calls.length).toBe(1);
  });
});
