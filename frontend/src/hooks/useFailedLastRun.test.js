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
  localStorage.clear();
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

  it('retries only the sites the store did not block', async () => {
    fetchUltimaCorridaFallida.mockResolvedValue({
      hayCorrida: true,
      uuid: 'run-1',
      fallidos: [
        { sitio: 'allbirds', error: 'paginación cortada en p1: bloqueado por la tienda (HTTP 403)', porDeadline: false },
        { sitio: 'venex', error: 'catálogo cortado en inicio: HTTP 429', porDeadline: false },
        { sitio: 'freres', error: 'net::ERR_SSL_VERSION_OR_CIPHER_MISMATCH', porDeadline: false },
      ],
    });
    const { result } = renderHook(() => useFailedLastRun(true));
    await waitFor(() => expect(result.current.run).not.toBeNull());

    await act(async () => { await result.current.reintentar(); });

    expect(startScrape).toHaveBeenCalledWith({ sitios: ['freres'] });
  });

  it('does not start a scrape when every failed site was blocked', async () => {
    fetchUltimaCorridaFallida.mockResolvedValue({
      hayCorrida: true, uuid: 'run-1',
      fallidos: [{ sitio: 'allbirds', error: 'HTTP 403', porDeadline: false }],
    });
    const { result } = renderHook(() => useFailedLastRun(true));
    await waitFor(() => expect(result.current.run).not.toBeNull());

    let outcome;
    await act(async () => { outcome = await result.current.reintentar(); });

    expect(outcome).toBe(false);
    expect(startScrape).not.toHaveBeenCalled();
  });

  describe('dismissal', () => {
    const CON_UUID = uuid => ({ ...OFERTA, uuid });

    it('stays hidden after a reload for the same run', async () => {
      fetchUltimaCorridaFallida.mockResolvedValue(CON_UUID('run-1'));
      const first = renderHook(() => useFailedLastRun(true));
      await waitFor(() => expect(first.result.current.run).not.toBeNull());
      act(() => first.result.current.dismiss());
      expect(first.result.current.run).toBeNull();
      first.unmount();

      const second = renderHook(() => useFailedLastRun(true));
      await waitFor(() => expect(fetchUltimaCorridaFallida).toHaveBeenCalledTimes(2));
      await act(async () => { await Promise.resolve(); });

      expect(second.result.current.run).toBeNull();
    });

    it('shows again for a different run', async () => {
      localStorage.setItem('failedLastRun.dismissedUuid', 'run-1');
      fetchUltimaCorridaFallida.mockResolvedValue(CON_UUID('run-2'));

      const { result } = renderHook(() => useFailedLastRun(true));

      await waitFor(() => expect(result.current.run).not.toBeNull());
    });

    it('still renders and dismisses when storage throws', async () => {
      fetchUltimaCorridaFallida.mockResolvedValue(CON_UUID('run-1'));
      const get = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied'); });
      const set = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('denied'); });
      try {
        const { result } = renderHook(() => useFailedLastRun(true));
        await waitFor(() => expect(result.current.run).not.toBeNull());

        act(() => result.current.dismiss());

        expect(result.current.run).toBeNull();
      } finally {
        get.mockRestore();
        set.mockRestore();
      }
    });
  });
});
