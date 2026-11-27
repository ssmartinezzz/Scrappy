import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useFailedLastRun } from './useFailedLastRun';
import { fetchUltimaCorridaFallida, startScrape } from '../api';

vi.mock('../api', () => ({
  fetchUltimaCorridaFallida: vi.fn(),
  startScrape: vi.fn(),
}));

const OFERTA = {
  hayCorrida: true,
  fallidos: [
    { sitio: 'city', error: 'Deadline global', porDeadline: true },
    { sitio: 'freres', error: 'timeout', porDeadline: false },
  ],
};

beforeEach(() => {
  fetchUltimaCorridaFallida.mockReset().mockResolvedValue(OFERTA);
  startScrape.mockReset().mockResolvedValue({ iniciado: true, mensaje: 'Scraping iniciado' });
});

describe('useFailedLastRun', () => {
  it('does not query the ADMIN-only endpoint for a viewer', async () => {
    renderHook(() => useFailedLastRun(false));
    await act(async () => { await Promise.resolve(); });

    expect(fetchUltimaCorridaFallida).not.toHaveBeenCalled();
  });

  it('offers only a completed run with failures', async () => {
    const { result } = renderHook(() => useFailedLastRun(true));

    await waitFor(() => expect(result.current.run).not.toBeNull());
    expect(result.current.run.fallidos).toHaveLength(2);
  });

  it('retries exactly the failed sites and clears the offer only after acceptance', async () => {
    const { result } = renderHook(() => useFailedLastRun(true));
    await waitFor(() => expect(result.current.run).not.toBeNull());

    let outcome;
    await act(async () => { outcome = await result.current.reintentar(); });

    expect(outcome).toBe(true);
    expect(startScrape).toHaveBeenCalledWith({ sitios: ['city', 'freres'] });
    expect(result.current.run).toBeNull();
  });

  it('keeps the offer visible when the scrape slot is busy', async () => {
    startScrape.mockResolvedValue({ iniciado: false, mensaje: 'Ya hay un scraping en curso' });
    const { result } = renderHook(() => useFailedLastRun(true));
    await waitFor(() => expect(result.current.run).not.toBeNull());

    await act(async () => { await result.current.reintentar(); });

    expect(result.current.run).not.toBeNull();
    expect(result.current.error).toMatch(/scraping en curso/i);
  });
});
