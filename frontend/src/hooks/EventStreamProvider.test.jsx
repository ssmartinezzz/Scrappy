import { act, render, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  EventStreamProvider, reduceStatusEvent, useMlStatus, useScrapeStatus, useStreamEvent, useStreamState,
} from './EventStreamProvider';
import { fetchMlEstado, fetchStatus } from '../api';
import { useAuth } from '../auth/AuthProvider';
import { fakeStream, httpResponse } from '../test/fakeEventStream';

vi.mock('../api', () => ({
  fetchStatus: vi.fn(),
  fetchMlEstado: vi.fn(),
  openEventStream: vi.fn(),
}));
vi.mock('../auth/AuthProvider', () => ({ useAuth: vi.fn() }));

const NEVER = () => new Promise(() => {});
const OPTIONS = { sleep: NEVER };

const SNAPSHOT = {
  status: { status: 'IDLE', mensaje: '', progreso: null, tieneData: true, total: 500, mlRefinadas: 7 },
  ml: {
    hasTextModel: true, hasImageModel: false, textMeta: { accuracy: 0.9 },
    training: { running: false, phase: 'idle', pct: 0, msg: '', startedAt: '' },
  },
};

function harness(stream, { authenticated = true } = {}) {
  useAuth.mockReturnValue({ authenticated, identity: authenticated ? { username: 'santi' } : null });
  const open = vi.fn().mockResolvedValue(stream.response);
  const wrapper = ({ children }) => (
    <EventStreamProvider open={open} connectOptions={OPTIONS}>{children}</EventStreamProvider>
  );
  return { open, wrapper };
}

function probe() {
  return {
    scrape: useScrapeStatus(),
    ml: useMlStatus(),
    stream: useStreamState(),
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  fetchStatus.mockResolvedValue(null);
  fetchMlEstado.mockResolvedValue(null);
});

describe('EventStreamProvider — one connection for the whole app', () => {
  it('does not connect while nobody is logged in', async () => {
    const s = fakeStream();
    const { open, wrapper } = harness(s, { authenticated: false });

    const { result } = renderHook(probe, { wrapper });
    await act(async () => { await Promise.resolve(); });

    expect(open).not.toHaveBeenCalled();
    expect(result.current.scrape).toBeNull();
    expect(result.current.stream.phase).toBe('idle');
  });

  it('opens exactly one stream however many components read it', async () => {
    const s = fakeStream();
    const { open, wrapper } = harness(s);

    const { result } = renderHook(() => ({ a: probe(), b: probe() }), { wrapper });
    await act(async () => { s.frame('snapshot', SNAPSHOT); });

    await waitFor(() => expect(result.current.a.scrape).toEqual(SNAPSHOT.status));
    expect(result.current.b.scrape).toBe(result.current.a.scrape);
    expect(open).toHaveBeenCalledTimes(1);
  });

  it('closes the stream and forgets its state on logout', async () => {
    const s = fakeStream();
    const { open, wrapper } = harness(s);
    const { result, rerender } = renderHook(probe, { wrapper });
    await act(async () => { s.frame('snapshot', SNAPSHOT); });
    await waitFor(() => expect(result.current.scrape).not.toBeNull());
    const signal = open.mock.calls[0][0].signal;

    useAuth.mockReturnValue({ authenticated: false, identity: null });
    rerender();

    await waitFor(() => expect(result.current.scrape).toBeNull());
    expect(signal.aborted).toBe(true);
    expect(result.current.stream.phase).toBe('idle');
  });

  it('closes the stream when the provider unmounts', async () => {
    const s = fakeStream();
    const { open, wrapper } = harness(s);
    const { unmount } = renderHook(probe, { wrapper });
    await waitFor(() => expect(open).toHaveBeenCalled());
    const signal = open.mock.calls[0][0].signal;

    unmount();

    expect(signal.aborted).toBe(true);
  });

  it('reports the connection state: live once frames flow, reconnecting when it breaks', async () => {
    const s = fakeStream();
    const { wrapper } = harness(s);
    const { result } = renderHook(probe, { wrapper });
    await waitFor(() => expect(result.current.stream.phase).toBe('connecting'));

    await act(async () => { s.frame('snapshot', SNAPSHOT); });
    await waitFor(() => expect(result.current.stream.phase).toBe('live'));

    await act(async () => { s.fail(); });
    await waitFor(() => expect(result.current.stream.phase).toBe('reconnecting'));
  });

  it('reports closed/forbidden for a 403 instead of retrying', async () => {
    useAuth.mockReturnValue({ authenticated: true, identity: { username: 'santi' } });
    const open = vi.fn().mockResolvedValue(httpResponse(403));
    const wrapper = ({ children }) => <EventStreamProvider open={open} connectOptions={OPTIONS}>{children}</EventStreamProvider>;

    const { result } = renderHook(probe, { wrapper });

    await waitFor(() => expect(result.current.stream).toEqual({ phase: 'closed', reason: 'forbidden' }));
    expect(open).toHaveBeenCalledTimes(1);
  });
});

describe('EventStreamProvider — events fold into the state', () => {
  async function live() {
    const s = fakeStream();
    const { wrapper } = harness(s);
    const view = renderHook(probe, { wrapper });
    await act(async () => { s.frame('snapshot', SNAPSHOT); });
    await waitFor(() => expect(view.result.current.scrape).not.toBeNull());
    return { s, ...view };
  }

  it('replaces both states with the snapshot', async () => {
    const { result } = await live();

    expect(result.current.scrape).toEqual(SNAPSHOT.status);
    expect(result.current.ml.estado).toEqual(SNAPSHOT.ml);
    expect(result.current.ml.resultado).toEqual({ running: false, phase: 'idle', pct: 0, msg: '', done: false });
  });

  it('keeps tieneData and total when a status event changes only status and message', async () => {
    const { s, result } = await live();

    await act(async () => { s.frame('scrape.status', { status: 'RUNNING', mensaje: 'Scrapeando' }); });

    await waitFor(() => expect(result.current.scrape.status).toBe('RUNNING'));
    expect(result.current.scrape).toMatchObject({ mensaje: 'Scrapeando', tieneData: true, total: 500 });
  });

  it('applies progress, and drops the previous run\'s progress when a new run starts', async () => {
    const { s, result } = await live();
    await act(async () => { s.frame('scrape.status', { status: 'RUNNING', mensaje: 'go' }); });
    await act(async () => { s.frame('scrape.progress', { total: 3, completados: 2, productos: 10, sitios: [] }); });
    await waitFor(() => expect(result.current.scrape.progreso.completados).toBe(2));

    await act(async () => { s.frame('scrape.status', { status: 'DONE', mensaje: 'ok' }); });
    await act(async () => { s.frame('scrape.status', { status: 'RUNNING', mensaje: 'again' }); });

    await waitFor(() => expect(result.current.scrape.mensaje).toBe('again'));
    expect(result.current.scrape.progreso).toBeNull();
  });

  it('reads the status once after a run ends, for the fields no event carries', async () => {
    const { s, result } = await live();
    fetchStatus.mockResolvedValue({ status: 'DONE', mensaje: 'stale', progreso: null, tieneData: true, total: 900, mlRefinadas: 12 });

    await act(async () => { s.frame('scrape.status', { status: 'DONE', mensaje: 'Listo' }); });

    await waitFor(() => expect(result.current.scrape.total).toBe(900));
    expect(fetchStatus).toHaveBeenCalledTimes(1);
    expect(result.current.scrape).toMatchObject({ status: 'DONE', mensaje: 'Listo', mlRefinadas: 12 });
  });

  it('does not let that read overwrite a run that started in the meantime', async () => {
    const { s, result } = await live();
    let release;
    fetchStatus.mockReturnValue(new Promise(r => { release = r; }));

    await act(async () => { s.frame('scrape.status', { status: 'DONE', mensaje: 'Listo' }); });
    await act(async () => { s.frame('scrape.status', { status: 'RUNNING', mensaje: 'otra' }); });
    await act(async () => { release({ status: 'DONE', mensaje: 'Listo', total: 900, tieneData: true }); });

    expect(result.current.scrape).toMatchObject({ status: 'RUNNING', mensaje: 'otra', total: 500 });
  });

  it('follows training events and derives what /api/ml/resultado would answer', async () => {
    const { s, result } = await live();

    await act(async () => { s.frame('ml.status', { kind: 'training', running: true, phase: 'text', pct: 40, msg: 'x', startedAt: 't0' }); });
    await waitFor(() => expect(result.current.ml.training.pct).toBe(40));
    expect(result.current.ml.resultado).toEqual({ running: true, phase: 'text', pct: 40, msg: 'x', done: false });

    fetchMlEstado.mockResolvedValue({ ...SNAPSHOT.ml, hasImageModel: true });
    await act(async () => { s.frame('ml.status', { kind: 'training', running: false, phase: 'done', pct: 100, msg: 'ok 91%', startedAt: 't0' }); });
    await waitFor(() => expect(result.current.ml.estado.hasImageModel).toBe(true));
    expect(result.current.ml.resultado).toEqual({ running: false, phase: 'done', pct: 100, msg: 'ok 91%', done: true });
    expect(fetchMlEstado).toHaveBeenCalledTimes(1);
  });

  it('keeps the embeddings backfill apart from training', async () => {
    const { s, result } = await live();

    await act(async () => { s.frame('ml.status', { kind: 'backfill', running: true, phase: '', pct: 10, msg: 'emb', startedAt: '' }); });

    await waitFor(() => expect(result.current.ml.backfill).toMatchObject({ running: true, pct: 10 }));
    expect(result.current.ml.training.running).toBe(false);
    expect(fetchMlEstado).not.toHaveBeenCalled();
  });

  it('refetches both reads on resync and replaces the state with them', async () => {
    const { s, result } = await live();
    fetchStatus.mockResolvedValue({ status: 'RUNNING', mensaje: 'fresh', progreso: null, tieneData: true, total: 640 });
    fetchMlEstado.mockResolvedValue({ ...SNAPSHOT.ml, hasImageModel: true });

    await act(async () => { s.frame('resync', {}); });

    await waitFor(() => expect(result.current.scrape.total).toBe(640));
    expect(result.current.scrape.status).toBe('RUNNING');
    expect(result.current.ml.estado.hasImageModel).toBe(true);
  });
});

describe('useStreamEvent', () => {
  function subscribed(name, handler, stream) {
    const { wrapper } = harness(stream);
    return renderHook(() => useStreamEvent(name, handler), { wrapper });
  }

  it('hands db.changed payloads to a subscriber', async () => {
    const s = fakeStream();
    const handler = vi.fn();
    subscribed('db.changed', handler, s);

    await act(async () => { s.frame('db.changed', { table: 'cron_execution', op: 'INSERT', job: 3, status: 'RUNNING' }); });

    await waitFor(() => expect(handler).toHaveBeenCalledWith({ table: 'cron_execution', op: 'INSERT', job: 3, status: 'RUNNING' }));
  });

  it('calls the latest handler, not the one from the first render', async () => {
    const s = fakeStream();
    const { wrapper } = harness(s);
    const first = vi.fn();
    const second = vi.fn();
    const { rerender } = renderHook(({ fn }) => useStreamEvent('resync', fn), { wrapper, initialProps: { fn: first } });
    rerender({ fn: second });

    await act(async () => { s.frame('resync', {}); });

    await waitFor(() => expect(second).toHaveBeenCalledTimes(1));
    expect(first).not.toHaveBeenCalled();
  });

  it('stops delivering after the subscriber unmounts', async () => {
    const s = fakeStream();
    const { wrapper } = harness(s);
    const handler = vi.fn();
    const Sub = () => { useStreamEvent('db.changed', handler); return null; };
    const Toggle = ({ on }) => (on ? <Sub /> : null);
    const { rerender } = render(<Toggle on />, { wrapper });
    await act(async () => { s.frame('db.changed', { table: 'scrape_run', op: 'UPDATE', status: 'DONE' }); });
    await waitFor(() => expect(handler).toHaveBeenCalledTimes(1));

    rerender(<Toggle on={false} />);
    await act(async () => { s.frame('db.changed', { table: 'scrape_run', op: 'UPDATE', status: 'ERROR' }); });
    await act(async () => { await Promise.resolve(); });

    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('emits "training" for snapshots, training events and resync reads, but not for backfill', async () => {
    const s = fakeStream();
    const handler = vi.fn();
    subscribed('training', handler, s);
    fetchMlEstado.mockResolvedValue({ ...SNAPSHOT.ml, training: { running: true, phase: 'image', pct: 5, msg: '', startedAt: 't' } });

    await act(async () => { s.frame('snapshot', SNAPSHOT); });
    await act(async () => { s.frame('ml.status', { kind: 'backfill', running: true, phase: '', pct: 1, msg: '', startedAt: '' }); });
    await act(async () => { s.frame('ml.status', { kind: 'training', running: true, phase: 'text', pct: 2, msg: '', startedAt: 't' }); });
    await act(async () => { s.frame('resync', {}); });

    await waitFor(() => expect(handler).toHaveBeenCalledTimes(3));
    expect(handler.mock.calls.map(c => c[0].phase)).toEqual(['idle', 'text', 'image']);
  });
});

describe('the hooks outside a provider', () => {
  it('report "no stream" instead of throwing, so a panel still renders on its own read', () => {
    const { result } = renderHook(() => ({ scrape: useScrapeStatus(), ml: useMlStatus(), stream: useStreamState() }));

    expect(result.current.scrape).toBeNull();
    expect(result.current.ml.resultado).toBeNull();
    expect(result.current.stream.phase).toBe('idle');
  });
});

describe('reduceStatusEvent', () => {
  it('ignores an event it does not know and keeps the same state object', () => {
    const state = { scrape: null, estado: null, backfill: null };
    expect(reduceStatusEvent(state, { type: 'db.changed', data: {} })).toBe(state);
  });
});
