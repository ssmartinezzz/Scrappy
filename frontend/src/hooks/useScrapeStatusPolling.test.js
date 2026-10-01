import { createElement } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { EventStreamProvider } from './EventStreamProvider';
import { useScrapeStatusPolling } from './useScrapeStatusPolling';
import { fetchStatus } from '../api';
import { useAuth } from '../auth/AuthProvider';
import { fakeStream, httpResponse } from '../test/fakeEventStream';

vi.mock('../api', () => ({ fetchStatus: vi.fn(), fetchMlEstado: vi.fn(), openEventStream: vi.fn() }));
vi.mock('../auth/AuthProvider', () => ({ useAuth: vi.fn() }));

const RUNNING = { status: 'RUNNING', mensaje: 'Scrapeando…', progreso: { total: 3, completados: 1 }, tieneData: false };
const DONE    = { status: 'DONE',    mensaje: 'Listo',       progreso: { total: 3, completados: 3 }, tieneData: true  };

const ml = { training: { running: false, phase: 'idle', pct: 0, msg: '', startedAt: '' } };
const snapshot = status => ({ status, ml });

let sleeps;
let options;

beforeEach(() => {
  vi.clearAllMocks();
  fetchStatus.mockReset();
  useAuth.mockReturnValue({ authenticated: true, identity: { username: 'santi' } });
  sleeps = [];
  options = { sleep: (ms, signal) => new Promise(resolve => { sleeps.push(resolve); signal?.addEventListener('abort', resolve, { once: true }); }), random: () => 0 };
});

/** Mounts the hook under a provider fed by `open`, and settles the status read it fires on mount. */
async function mountHook(open) {
  const wrapper = ({ children }) => createElement(EventStreamProvider, { open, connectOptions: options }, children);
  const view = renderHook(() => useScrapeStatusPolling(), { wrapper });
  await act(async () => { await Promise.resolve(); });
  return view;
}

function silentStream() {
  const s = fakeStream();
  return { s, open: vi.fn().mockResolvedValue(s.response) };
}

describe('useScrapeStatusPolling — one callback per run, nothing left running (slice 0, task 0.1/0.3/0.4)', () => {
  it('watching a run a second time replaces the first callback instead of leaving two to fire', async () => {
    fetchStatus.mockResolvedValue(RUNNING);
    const { s, open } = silentStream();
    const { result } = await mountHook(open);
    const first = vi.fn();
    const second = vi.fn();

    act(() => { result.current.watchRun(first); });
    act(() => { result.current.watchRun(second); });
    await act(async () => { s.frame('snapshot', snapshot(DONE)); });

    expect(second).toHaveBeenCalledTimes(1);
    expect(first).not.toHaveBeenCalled();
  });

  it('makes no status read and leaves the stream aborted once unmounted — nothing outlives the component', async () => {
    fetchStatus.mockResolvedValue(RUNNING);
    const { s, open } = silentStream();
    const { result, unmount } = await mountHook(open);
    act(() => { result.current.watchRun(vi.fn()); });
    await act(async () => { s.frame('snapshot', snapshot(RUNNING)); });

    fetchStatus.mockClear();
    unmount();
    await act(async () => { await new Promise(r => setTimeout(r, 50)); });

    expect(open.mock.calls[0][0].signal.aborted).toBe(true);
    expect(fetchStatus).not.toHaveBeenCalled();
  });

  it('calls onDone exactly once when the run reaches DONE, and reads /api/status at most for the closing extras', async () => {
    fetchStatus.mockResolvedValue(RUNNING);
    const { s, open } = silentStream();
    const { result } = await mountHook(open);
    const onDone = vi.fn();

    act(() => { result.current.watchRun(onDone); });
    await act(async () => { s.frame('snapshot', snapshot(RUNNING)); });
    fetchStatus.mockClear();
    await act(async () => { s.frame('scrape.status', { status: 'DONE', mensaje: 'Listo' }); });
    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
    expect(result.current.status).toBe('DONE');

    await act(async () => { s.frame('scrape.status', { status: 'DONE', mensaje: 'otra vez' }); });
    await act(async () => { await new Promise(r => setTimeout(r, 30)); });

    expect(onDone).toHaveBeenCalledTimes(1);
    expect(fetchStatus.mock.calls.length).toBeLessThanOrEqual(2);
  });

  it('follows progress events into `progreso` as they arrive, with no timer involved', async () => {
    fetchStatus.mockResolvedValue(RUNNING);
    const { s, open } = silentStream();
    const { result } = await mountHook(open);
    await act(async () => { s.frame('snapshot', snapshot(RUNNING)); });

    await act(async () => { s.frame('scrape.progress', { total: 3, completados: 2, productos: 40, sitios: [] }); });

    await waitFor(() => expect(result.current.progreso.completados).toBe(2));
  });
});

describe('useScrapeStatusPolling — an unreachable backend is a state, not silence (task 0.2/0.5)', () => {
  it('reports the backend unreachable when the stream breaks, instead of freezing on the last RUNNING', async () => {
    fetchStatus.mockResolvedValue(RUNNING);
    const { s, open } = silentStream();
    const { result } = await mountHook(open);
    await act(async () => { s.frame('snapshot', snapshot(RUNNING)); });
    await waitFor(() => expect(result.current.status).toBe('RUNNING'));
    expect(result.current.backendUnreachable).toBe(false);

    // The backend dies: the read REJECTS, it does not resolve to a non-ok response.
    await act(async () => { s.fail(); });

    await waitFor(() => expect(result.current.backendUnreachable).toBe(true));
  });

  it('reports the backend unreachable when the stream answers with a non-ok status', async () => {
    fetchStatus.mockResolvedValue(RUNNING);
    const open = vi.fn().mockResolvedValue(httpResponse(503));

    const { result } = await mountHook(open);

    await waitFor(() => expect(result.current.backendUnreachable).toBe(true));
  });

  it('reports it when the status read after a launch resolves null (a non-ok response)', async () => {
    fetchStatus.mockResolvedValueOnce({ status: 'IDLE', mensaje: '', tieneData: true });
    const { open } = silentStream();
    const { result } = await mountHook(open);
    expect(result.current.backendUnreachable).toBe(false);

    fetchStatus.mockResolvedValueOnce(null);
    await act(async () => { result.current.watchRun(vi.fn(), { reconcile: true }); });

    await waitFor(() => expect(result.current.backendUnreachable).toBe(true));
  });

  it('keeps reconnecting after a failure and clears the flag as soon as the backend answers again', async () => {
    fetchStatus.mockResolvedValue(RUNNING);
    const first = fakeStream();
    const second = fakeStream();
    const open = vi.fn().mockResolvedValueOnce(first.response).mockResolvedValueOnce(second.response);
    const { result } = await mountHook(open);
    await act(async () => { first.frame('snapshot', snapshot(RUNNING)); });

    await act(async () => { first.fail(); });
    await waitFor(() => expect(result.current.backendUnreachable).toBe(true));

    await act(async () => { sleeps[0](); });
    await act(async () => { second.frame('snapshot', snapshot(RUNNING)); });

    await waitFor(() => expect(result.current.backendUnreachable).toBe(false));
    expect(result.current.status).toBe('RUNNING');
    expect(open).toHaveBeenCalledTimes(2);
  });

  it('reports the backend unreachable when the status read on mount fails', async () => {
    fetchStatus.mockRejectedValue(new TypeError('Failed to fetch'));

    const { result } = await mountHook(vi.fn(() => new Promise(() => {})));

    expect(result.current.backendUnreachable).toBe(true);
    expect(result.current.status).toBe('IDLE');
  });
});

describe('useScrapeStatusPolling — a run already live at mount is joined (slice 6, task 6.3)', () => {
  it('flags that a run is in flight when the mount read finds one already RUNNING', async () => {
    // Landing on /splash after a resume — or after a reload mid-run — finds a live run nobody
    // in this tab started. Without the flag the screen showed RUNNING and nothing ever
    // navigated away when it finished.
    fetchStatus.mockResolvedValue(RUNNING);

    const { result } = await mountHook(vi.fn(() => new Promise(() => {})));

    expect(result.current.runInFlightAtMount).toBe(true);
  });

  it('carries that run\'s progress in from the mount read, not one event later', async () => {
    // The bar is drawn from `progreso`. Without this the screen showed a live run with an empty
    // bar until the first push landed.
    fetchStatus.mockResolvedValue(RUNNING);

    const { result } = await mountHook(vi.fn(() => new Promise(() => {})));

    expect(result.current.progreso).toEqual(RUNNING.progreso);
  });

  it('joins a run that the first snapshot reports, when the mount read has not answered', async () => {
    fetchStatus.mockReturnValue(new Promise(() => {}));
    const { s, open } = silentStream();
    const { result } = await mountHook(open);

    await act(async () => { s.frame('snapshot', snapshot(RUNNING)); });

    await waitFor(() => expect(result.current.runInFlightAtMount).toBe(true));
  });

  it('does not flag it when the mount read finds no run in flight', async () => {
    fetchStatus.mockResolvedValue({ status: 'IDLE', mensaje: '', tieneData: true });

    const { result } = await mountHook(vi.fn(() => new Promise(() => {})));

    expect(result.current.runInFlightAtMount).toBe(false);
  });

  it('does not flag it when the backend could not be reached at mount', async () => {
    // Unreachable is not "running": `backendUnreachable` already says what actually happened.
    fetchStatus.mockRejectedValue(new TypeError('Failed to fetch'));

    const { result } = await mountHook(vi.fn(() => new Promise(() => {})));

    expect(result.current.runInFlightAtMount).toBe(false);
  });

  it('is a one-shot start signal — an event that reports RUNNING never re-raises it', async () => {
    fetchStatus.mockResolvedValue({ status: 'IDLE', mensaje: '', tieneData: true });
    const { s, open } = silentStream();
    const { result } = await mountHook(open);
    expect(result.current.runInFlightAtMount).toBe(false);

    // A scrape launched from this very tab arrives as a RUNNING event; the flag must stay down.
    await act(async () => { s.frame('snapshot', snapshot({ status: 'IDLE', mensaje: '', tieneData: true })); });
    await act(async () => { s.frame('scrape.status', { status: 'RUNNING', mensaje: 'go' }); });

    await waitFor(() => expect(result.current.status).toBe('RUNNING'));
    expect(result.current.runInFlightAtMount).toBe(false);
  });

  it('keeps a snapshot that beat the mount read: the older read does not overwrite it', async () => {
    let release;
    fetchStatus.mockReturnValue(new Promise(r => { release = r; }));
    const { s, open } = silentStream();
    const { result } = await mountHook(open);
    await act(async () => { s.frame('snapshot', snapshot(RUNNING)); });
    await waitFor(() => expect(result.current.status).toBe('RUNNING'));

    await act(async () => { release({ status: 'IDLE', mensaje: '', tieneData: false }); });

    expect(result.current.status).toBe('RUNNING');
  });
});

describe('useScrapeStatusPolling — launching a run from this tab', () => {
  it('shows RUNNING at once on markRunning, before any event', async () => {
    fetchStatus.mockResolvedValue({ status: 'IDLE', mensaje: '', tieneData: true });
    const { open } = silentStream();
    const { result } = await mountHook(open);

    act(() => { result.current.markRunning(); });

    expect(result.current.status).toBe('RUNNING');
  });

  it('a rejected launch is corrected by the reconcile read, since no event will ever say so', async () => {
    fetchStatus.mockResolvedValue({ status: 'IDLE', mensaje: '', tieneData: true });
    const { open } = silentStream();
    const { result } = await mountHook(open);
    act(() => { result.current.markRunning(); });

    await act(async () => { result.current.watchRun(vi.fn(), { reconcile: true }); });

    await waitFor(() => expect(result.current.status).toBe('IDLE'));
  });

  it('a launch that already finished by the reconcile read calls onDone', async () => {
    fetchStatus.mockResolvedValue({ status: 'IDLE', mensaje: '', tieneData: true });
    const { open } = silentStream();
    const { result } = await mountHook(open);
    act(() => { result.current.markRunning(); });
    const onDone = vi.fn();
    fetchStatus.mockResolvedValue(DONE);

    await act(async () => { result.current.watchRun(onDone, { reconcile: true }); });

    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
  });
});

describe('useScrapeStatusPolling — a read never overwrites a newer push', () => {
  it('drops the reconcile answer when an event arrived while the read was in flight', async () => {
    fetchStatus.mockResolvedValue({ status: 'IDLE', mensaje: '', tieneData: true });
    const { s, open } = silentStream();
    const { result } = await mountHook(open);
    await act(async () => { s.frame('snapshot', snapshot({ status: 'IDLE', mensaje: '', tieneData: true })); });
    act(() => { result.current.markRunning(); });
    let answer;
    fetchStatus.mockReturnValue(new Promise(r => { answer = r; }));
    act(() => { result.current.watchRun(vi.fn(), { reconcile: true }); });

    await act(async () => { s.frame('scrape.status', { status: 'RUNNING', mensaje: 'el más nuevo' }); });
    await waitFor(() => expect(result.current.mensaje).toBe('el más nuevo'));
    await act(async () => { answer({ status: 'RUNNING', mensaje: 'el más viejo', tieneData: true }); });

    expect(result.current.mensaje).toBe('el más nuevo');
  });
});

describe('useScrapeStatusPolling — totalProds es el CATÁLOGO, no la cantidad de sitios', () => {
  // `progreso.total` es ProgressData(totalSitios, ...) — la cantidad de sitios
  // de la corrida. Leer de ahí hacía que el botón dijera "Ver 3 productos
  // disponibles" con 8000 productos en la base.
  it('lee st.total y NO st.progreso.total', async () => {
    fetchStatus.mockResolvedValue({
      status: 'DONE', mensaje: 'Listo', tieneData: true,
      total: 8000, progreso: { total: 3, completados: 3 },
    });

    const { result } = await mountHook(vi.fn(() => new Promise(() => {})));

    expect(result.current.totalProds).toBe(8000);
    expect(result.current.tieneData).toBe(true);
  });

  it('lo toma también del snapshot del stream', async () => {
    fetchStatus.mockReturnValue(new Promise(() => {}));
    const { s, open } = silentStream();
    const { result } = await mountHook(open);

    await act(async () => {
      s.frame('snapshot', snapshot({ status: 'DONE', mensaje: '', tieneData: true, total: 8000, progreso: { total: 3, completados: 3 } }));
    });

    await waitFor(() => expect(result.current.totalProds).toBe(8000));
  });

  // Estaba gateado en RUNNING, así que en reposo quedaba 0 y la salida al
  // catálogo del splash no se dibujaba nunca.
  it('lo levanta en la lectura de montaje, sin corrida en curso', async () => {
    fetchStatus.mockResolvedValue({ status: 'IDLE', mensaje: '', tieneData: true, total: 1234 });

    const { result } = await mountHook(vi.fn(() => new Promise(() => {})));

    expect(result.current.totalProds).toBe(1234);
  });

  it('sin catálogo, tieneData es false y no inventa un total', async () => {
    fetchStatus.mockResolvedValue({ status: 'IDLE', mensaje: '', tieneData: false });

    const { result } = await mountHook(vi.fn(() => new Promise(() => {})));

    expect(result.current.tieneData).toBe(false);
    expect(result.current.totalProds).toBe(0);
  });
});
