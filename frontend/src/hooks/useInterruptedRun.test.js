import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useInterruptedRun } from './useInterruptedRun';
import { descartarInterrumpida, fetchInterrumpida, retomarScrape } from '../api';

vi.mock('../api', () => ({
  descartarInterrumpida: vi.fn(),
  fetchInterrumpida: vi.fn(),
  retomarScrape: vi.fn(),
}));

const OFERTA = {
  hayInterrumpida: true,
  uuid: '4f1a2b3c-0000-4000-8000-000000000001',
  startedAt: '2026-08-24T18:20:00.049704Z',
  soloFaltaLaPasadaFinal: false,
  atendidos: ['freres', 'vcp'],
  pendientes: ['entreno'],
  salteados: [],
};

beforeEach(() => {
  fetchInterrumpida.mockReset().mockResolvedValue(OFERTA);
  retomarScrape.mockReset().mockResolvedValue({ retomando: true, mensaje: 'Retomando…' });
  descartarInterrumpida.mockReset().mockResolvedValue({ descartadas: 1, mensaje: 'ok' });
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
  it('really discards on dismiss, and never resumes to get rid of the offer', async () => {
    const { result } = renderHook(() => useInterruptedRun(true));
    await waitFor(() => expect(result.current.run).not.toBeNull());

    await act(async () => { await result.current.dismiss(); });

    expect(descartarInterrumpida).toHaveBeenCalledTimes(1);
    expect(retomarScrape).not.toHaveBeenCalled();
    expect(result.current.run).toBeNull();
  });

  it('still hides the offer when the discard cannot reach the server', async () => {
    // Keeping the banner up would trap a user whose backend is down in the one
    // notice they cannot close.
    descartarInterrumpida.mockRejectedValue(new TypeError('Failed to fetch'));
    const { result } = renderHook(() => useInterruptedRun(true));
    await waitFor(() => expect(result.current.run).not.toBeNull());

    await act(async () => { await result.current.dismiss(); });

    expect(result.current.run).toBeNull();
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
