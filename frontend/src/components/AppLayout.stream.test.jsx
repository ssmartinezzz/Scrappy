import { act, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useOutletContext } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import AppLayout from './AppLayout';
import { EventStreamProvider } from '../hooks/EventStreamProvider';
import { useAuth } from '../auth/AuthProvider';
import { fakeStream } from '../test/fakeEventStream';

vi.mock('../auth/AuthProvider', () => ({ useAuth: vi.fn() }));

const IDLE = { running: false, phase: 'idle', pct: 0, msg: '', startedAt: '' };
const ESTADO = { hasTextModel: true, textMeta: { accuracy: 0.9 }, training: IDLE };
const STATUS = { status: 'DONE', mensaje: 'Listo', tieneData: true, total: 10, mlRefinadas: 4 };
const training = patch => ({ kind: 'training', ...IDLE, ...patch });

let stream;
let estado;
let entrenar;

function json(data) {
  return { ok: true, status: 200, json: async () => ({ data }) };
}

function router() {
  return vi.fn().mockImplementation((url, init) => {
    const u = String(url);
    if (u.includes('/api/ml/renormalizar')) return Promise.resolve(json({ ok: true }));
    if (u.includes('/api/ml/entrenar')) return entrenar(init);
    if (u.includes('/api/status'))       return Promise.resolve(json(STATUS));
    if (u.includes('/api/ml/estado'))    return Promise.resolve(json(estado));
    if (u.includes('/api/ml/resultado')) return Promise.resolve(json({ running: false, done: false }));
    if (u.includes('/api/indices'))      return Promise.resolve(json({ ipc: {}, usd: {}, actualizado: null }));
    if (u.includes('/api/outfits/saved') || u.includes('/api/pcs/saved') || u.includes('/api/favoritos')) return Promise.resolve(json([]));
    if (u.includes('/api/tendencias'))   return Promise.resolve(json({}));
    if (u.includes('/api/agent/models')) return Promise.resolve(json({ models: [] }));
    if (u.includes('/api/facets'))       return Promise.resolve(json({}));
    if (u.includes('/api/data'))         return Promise.resolve(json({ productos: [], meta: {} }));
    throw new Error(`unexpected fetch in AppLayout stream test: ${u}`);
  });
}

function Probe() {
  const { triggerGpuTraining } = useOutletContext();
  return <button onClick={triggerGpuTraining}>entrenar</button>;
}

async function mountLayout() {
  useAuth.mockReturnValue({ authenticated: true, isAdmin: true, identity: { username: 'admin' }, logout: vi.fn() });
  stream = fakeStream();
  const open = vi.fn().mockResolvedValue(stream.response);
  render(
    <MemoryRouter initialEntries={['/catalogo']}>
      <EventStreamProvider open={open}>
        <Routes>
          <Route element={<AppLayout />}><Route path="/catalogo" element={<Probe />} /></Route>
        </Routes>
      </EventStreamProvider>
    </MemoryRouter>,
  );
  await act(async () => { stream.frame('snapshot', { status: STATUS, ml: estado }); });
}

const calls = part => global.fetch.mock.calls.filter(c => String(c[0]).includes(part)).length;

beforeEach(() => {
  vi.clearAllMocks();
  estado = ESTADO;
  entrenar = () => Promise.resolve(json({ status: 'started' }));
  global.fetch = router();
});

describe('AppLayout — the GPU overlay follows the stream (was a 2 s poll)', () => {
  async function launch() {
    await mountLayout();
    await act(async () => { screen.getByText('entrenar').click(); });
    await waitFor(() => expect(calls('/api/ml/entrenar')).toBe(1));
  }

  it('shows progress from ml.status events and the success line when training ends, polling nothing', async () => {
    await launch();
    const estadoCalls = calls('/api/ml/estado');

    await act(async () => { stream.frame('ml.status', training({ running: true, phase: 'image', pct: 37, msg: 'lote 4', startedAt: new Date().toISOString() })); });
    expect(await screen.findByText(/37%/)).toBeInTheDocument();
    expect(screen.getByText('lote 4')).toBeInTheDocument();
    await act(async () => { await new Promise(r => setTimeout(r, 2300)); });
    expect(calls('/api/ml/estado')).toBe(estadoCalls);
    expect(calls('/api/ml/resultado')).toBe(1);

    await act(async () => { stream.frame('ml.status', training({ phase: 'done', pct: 100, msg: 'Modelo 91%' })); });
    expect(await screen.findByText('✓ Listo — Modelo 91%')).toBeInTheDocument();
  });

  it('shows the error line and lets the user close it when training ends in the error phase', async () => {
    await launch();
    await act(async () => { stream.frame('ml.status', training({ running: true, phase: 'image', pct: 10 })); });

    await act(async () => { stream.frame('ml.status', training({ phase: 'error', msg: 'CUDA out of memory' })); });

    expect(await screen.findByText('✕ Error en el entrenamiento')).toBeInTheDocument();
    expect(screen.getByText('CUDA out of memory')).toBeInTheDocument();
    await act(async () => { screen.getByText('Cerrar').click(); });
    expect(screen.queryByText('✕ Error en el entrenamiento')).toBeNull();
  });

  it('still ends the overlay when the terminal event beats the launch response', async () => {
    let answer;
    entrenar = () => new Promise(r => { answer = r; });
    await mountLayout();
    await act(async () => { screen.getByText('entrenar').click(); });
    await waitFor(() => expect(calls('/api/ml/entrenar')).toBe(1));

    await act(async () => { stream.frame('ml.status', training({ running: true, phase: 'text', pct: 1 })); });
    await act(async () => { stream.frame('ml.status', training({ phase: 'done', pct: 100, msg: 'rápido' })); });
    await act(async () => { answer(json({ status: 'started' })); });

    expect(await screen.findByText('✓ Listo — rápido')).toBeInTheDocument();
  });

  it('does not show an overlay for training this tab did not start or recover', async () => {
    await mountLayout();

    await act(async () => { stream.frame('ml.status', training({ running: true, phase: 'text', pct: 20 })); });
    await act(async () => { await Promise.resolve(); });

    expect(screen.queryByText(/Re-entrenando modelo/)).toBeNull();
  });

  it('a rejected launch shows its error and later training events do not bring the overlay back', async () => {
    entrenar = () => Promise.resolve({ ok: false, status: 409, json: async () => ({ error: { code: 'conflict', message: 'busy' } }) });
    await mountLayout();
    await act(async () => { screen.getByText('entrenar').click(); });
    expect(await screen.findByText('✕ Error en el entrenamiento')).toBeInTheDocument();
    await act(async () => { screen.getByText('Cerrar').click(); });

    await act(async () => { stream.frame('ml.status', training({ running: true, phase: 'text', pct: 5 })); });
    await act(async () => { await Promise.resolve(); });

    expect(screen.queryByText(/Re-entrenando modelo/)).toBeNull();
  });

  it('recovers a training already running at load, and ends it from the stream', async () => {
    estado = { ...ESTADO, training: { running: true, phase: 'text', pct: 55, msg: 'en curso', startedAt: new Date().toISOString() } };
    await mountLayout();
    expect(await screen.findByText(/55%/)).toBeInTheDocument();

    await act(async () => { stream.frame('ml.status', training({ phase: 'done', pct: 100, msg: 'fin' })); });

    expect(await screen.findByText('✓ Listo — fin')).toBeInTheDocument();
  });
});

describe('AppLayout — scrape status and the ML banner come from the stream', () => {
  it('refetches /api/tendencias when a pushed scrape goes RUNNING -> DONE', async () => {
    await mountLayout();
    await waitFor(() => expect(calls('/api/tendencias')).toBe(1));

    await act(async () => { stream.frame('scrape.status', { status: 'RUNNING', mensaje: 'go' }); });
    expect(calls('/api/tendencias')).toBe(1);
    await act(async () => { stream.frame('scrape.status', { status: 'DONE', mensaje: 'fin' }); });

    await waitFor(() => expect(calls('/api/tendencias')).toBe(2));
  });

  it('updates the ML banner with the accuracy of a model trained while the page was open', async () => {
    await mountLayout();
    expect(await screen.findByText(/ML 90\.0% acc · 4 ref\./)).toBeInTheDocument();

    estado = { ...ESTADO, textMeta: { accuracy: 0.935 } };
    await act(async () => { stream.frame('ml.status', training({ running: true, phase: 'text', pct: 1 })); });
    await act(async () => { stream.frame('ml.status', training({ phase: 'done', pct: 100, msg: 'ok' })); });

    expect(await screen.findByText(/ML 93\.5% acc/)).toBeInTheDocument();
  });
});
