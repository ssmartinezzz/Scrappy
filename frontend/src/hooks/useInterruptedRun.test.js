import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useInterruptedRun } from './useInterruptedRun';
import { fetchInterrumpida, retomarScrape } from '../api';

vi.mock('../api', () => ({
  fetchInterrumpida: vi.fn(),
  retomarScrape: vi.fn(),
}));

const OFERTA = {
  hayInterrumpida: true,
  uuid: '4f1a2b3c-0000-4000-8000-000000000001',
  startedAt: '2026-08-24T18:20:00Z',
  soloFaltaLaPasadaFinal: false,
  atendidos: ['freres', 'vcp'],
  pendientes: ['entreno'],
  salteados: [],
};

beforeEach(() => {
  fetchInterrumpida.mockReset().mockResolvedValue(OFERTA);
  retomarScrape.mockReset().mockResolvedValue({ retomando: true, mensaje: 'Retomando…' });
});

describe('useInterruptedRun — a VIEWER is never notified (task 6.1, spec "VIEWER is not notified")', () => {
  it('never asks the backend when it is not enabled', async () => {
    const { result } = renderHook(() => useInterruptedRun(false));
    await act(async () => { await Promise.resolve(); });

    // Not "asks and hides the answer": an ADMIN-only route asked by a VIEWER
    // is a 403 in the log for a question that should never have been posed.
    expect(fetchInterrumpida).not.toHaveBeenCalled();
    expect(result.current.run).toBeNull();
  });
});

describe('useInterruptedRun — reading the offer', () => {
  it('exposes the interrupted run once the read settles', async () => {
    const { result } = renderHook(() => useInterruptedRun(true));

    await waitFor(() => expect(result.current.run).not.toBeNull());
    expect(result.current.run.pendientes).toEqual(['entreno']);
  });

  it('exposes no run when the backend says there is no interrupted one', async () => {
    fetchInterrumpida.mockResolvedValue({ hayInterrumpida: false });
    const { result } = renderHook(() => useInterruptedRun(true));

    await act(async () => { await Promise.resolve(); });
    expect(result.current.run).toBeNull();
  });

  it('exposes no run when the read fails, and does not reject', async () => {
    // `authedFetch` calls bare fetch, which REJECTS when nothing is listening —
    // fetchInterrumpida only resolves null on a non-ok response. Both shapes
    // have to be covered or a dead backend takes down the page (CLAUDE.md).
    fetchInterrumpida.mockRejectedValue(new TypeError('Failed to fetch'));
    const { result } = renderHook(() => useInterruptedRun(true));

    await act(async () => { await Promise.resolve(); });
    expect(result.current.run).toBeNull();
  });
});

describe('useInterruptedRun — dismissing and resuming', () => {
  it('hides the offer on dismiss without asking the backend to discard anything', async () => {
    // There is NO discard endpoint: `interrumpida` is only cleared by resuming.
    // Dismiss is therefore honest about its scope — it hides the banner for
    // this session, and a reload brings it back because the run is still
    // interrupted. Anything else would be inventing a backend surface.
    const { result } = renderHook(() => useInterruptedRun(true));
    await waitFor(() => expect(result.current.run).not.toBeNull());

    act(() => { result.current.dismiss(); });

    expect(result.current.run).toBeNull();
    expect(retomarScrape).not.toHaveBeenCalled();
  });

  it('clears the offer and reports success when the resume is accepted', async () => {
    const { result } = renderHook(() => useInterruptedRun(true));
    await waitFor(() => expect(result.current.run).not.toBeNull());

    let outcome;
    await act(async () => { outcome = await result.current.retomar(); });

    expect(outcome).toBe(true);
    expect(result.current.run).toBeNull();
  });

  it('keeps the offer on screen when the backend refuses the resume', async () => {
    // 200 with `retomando:false` — another scrape got in first. Clearing the
    // banner here would report a run that was never started as started.
    retomarScrape.mockResolvedValue({ retomando: false, mensaje: 'ya hay un scraping en curso' });
    const { result } = renderHook(() => useInterruptedRun(true));
    await waitFor(() => expect(result.current.run).not.toBeNull());

    let outcome;
    await act(async () => { outcome = await result.current.retomar(); });

    expect(outcome).toBe(false);
    expect(result.current.run).not.toBeNull();
    expect(result.current.error).toMatch(/scraping en curso/i);
  });

  it('keeps the offer on screen when the resume call itself fails', async () => {
    retomarScrape.mockRejectedValue(new TypeError('Failed to fetch'));
    const { result } = renderHook(() => useInterruptedRun(true));
    await waitFor(() => expect(result.current.run).not.toBeNull());

    let outcome;
    await act(async () => { outcome = await result.current.retomar(); });

    expect(outcome).toBe(false);
    expect(result.current.run).not.toBeNull();
  });
});
