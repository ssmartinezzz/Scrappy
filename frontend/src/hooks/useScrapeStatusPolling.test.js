import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { POLL_INTERVAL_MS, useScrapeStatusPolling } from './useScrapeStatusPolling';
import { fetchStatus } from '../api';

vi.mock('../api', () => ({ fetchStatus: vi.fn() }));

const RUNNING = { status: 'RUNNING', mensaje: 'Scrapeando…', progreso: { total: 3, completados: 1 }, tieneData: false };
const DONE    = { status: 'DONE',    mensaje: 'Listo',       progreso: { total: 3, completados: 3 }, tieneData: true  };

/** Advances exactly `times` poll intervals and flushes the async callback each time. */
async function tick(times = 1) {
  await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * times); });
}

/** Mounts the hook and settles the status read it fires on mount. */
async function mountHook() {
  const view = renderHook(() => useScrapeStatusPolling());
  await act(async () => { await Promise.resolve(); });
  return view;
}

beforeEach(() => {
  vi.useFakeTimers();
  fetchStatus.mockReset();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('useScrapeStatusPolling — interval lifecycle (slice 0, task 0.1/0.3/0.4)', () => {
  it('re-arming after a poll-driven re-render replaces the interval instead of leaking a second one', async () => {
    fetchStatus.mockResolvedValue(RUNNING);
    const { result } = await mountHook();

    act(() => { result.current.startPolling(); });
    await tick(); // one poll writes state, so the next startPolling comes from a NEW render's closure
    act(() => { result.current.startPolling(); });

    fetchStatus.mockClear();
    await tick();

    // The per-render `pollingRef = {current:null}` this replaces read a fresh
    // null here, failed to clear the first interval, and left two running.
    expect(fetchStatus).toHaveBeenCalledTimes(1);
  });

  it('clears the interval on unmount — no poll outlives the component', async () => {
    fetchStatus.mockResolvedValue(RUNNING);
    const { result, unmount } = await mountHook();

    act(() => { result.current.startPolling(); });
    await tick();

    fetchStatus.mockClear();
    unmount();
    await tick(3);

    expect(fetchStatus).not.toHaveBeenCalled();
  });

  it('stops polling and calls onDone exactly once when the run reaches DONE', async () => {
    fetchStatus.mockResolvedValue(RUNNING);
    const { result } = await mountHook();
    const onDone = vi.fn();

    act(() => { result.current.startPolling(onDone); });
    fetchStatus.mockResolvedValueOnce(DONE);
    await tick();

    expect(onDone).toHaveBeenCalledTimes(1);
    expect(result.current.status).toBe('DONE');

    fetchStatus.mockClear();
    await tick(2);
    expect(fetchStatus).not.toHaveBeenCalled();
  });
});

describe('useScrapeStatusPolling — an unreachable backend is a state, not silence (task 0.2/0.5)', () => {
  it('reports the backend unreachable when the fetch itself rejects, instead of freezing on the last RUNNING', async () => {
    fetchStatus.mockResolvedValue(RUNNING);
    const { result } = await mountHook();

    act(() => { result.current.startPolling(); });
    await tick();
    expect(result.current.status).toBe('RUNNING');
    expect(result.current.backendUnreachable).toBe(false);

    // The backend dies: `fetch` rejects, it does not resolve to a non-ok response.
    fetchStatus.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    await tick();

    expect(result.current.backendUnreachable).toBe(true);
  });

  it('reports the backend unreachable when the status response is not ok (fetchStatus resolves null)', async () => {
    fetchStatus.mockResolvedValue(RUNNING);
    const { result } = await mountHook();

    act(() => { result.current.startPolling(); });
    await tick();

    fetchStatus.mockResolvedValueOnce(null);
    await tick();

    expect(result.current.backendUnreachable).toBe(true);
  });

  it('keeps polling after a failure and clears the flag as soon as the backend answers again', async () => {
    fetchStatus.mockResolvedValue(RUNNING);
    const { result } = await mountHook();

    act(() => { result.current.startPolling(); });
    fetchStatus.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    await tick();
    expect(result.current.backendUnreachable).toBe(true);

    fetchStatus.mockResolvedValueOnce(RUNNING);
    await tick();

    expect(result.current.backendUnreachable).toBe(false);
    expect(result.current.status).toBe('RUNNING');
  });

  it('reports the backend unreachable when the status read on mount fails', async () => {
    fetchStatus.mockRejectedValue(new TypeError('Failed to fetch'));

    const { result } = await mountHook();

    expect(result.current.backendUnreachable).toBe(true);
    expect(result.current.status).toBe('IDLE');
  });
});
