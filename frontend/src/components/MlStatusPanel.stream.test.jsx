import { act, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import MlStatusPanel from './MlStatusPanel';
import { EventStreamProvider } from '../hooks/EventStreamProvider';
import { fetchMlEstado, fetchMlResultado } from '../api';
import { fakeStream } from '../test/fakeEventStream';

vi.mock('../api', () => ({
  fetchStatus: vi.fn(() => Promise.resolve(null)),
  fetchMlEstado: vi.fn(),
  fetchMlResultado: vi.fn(() => Promise.resolve(null)),
  startMlTraining: vi.fn(),
  aplicarModeloML: vi.fn(),
  openEventStream: vi.fn(),
}));
vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ authenticated: true, identity: { username: 'admin' } }),
}));

const IDLE = { running: false, phase: 'idle', pct: 0, msg: '', startedAt: '' };
const ESTADO = { hasTextModel: true, hasImageModel: false, textMeta: null, training: IDLE, embeddingsCount: 0 };
const SNAPSHOT = { status: { status: 'IDLE', mensaje: '' }, ml: ESTADO };
const training = patch => ({ kind: 'training', ...IDLE, ...patch });

let stream;

async function mountPanel() {
  stream = fakeStream();
  const open = vi.fn().mockResolvedValue(stream.response);
  render(<EventStreamProvider open={open}><MlStatusPanel /></EventStreamProvider>);
  await act(async () => { stream.frame('snapshot', SNAPSHOT); });
}

const toasts = () => [...document.querySelectorAll('.toast')].map(t => t.textContent);

beforeEach(() => {
  vi.clearAllMocks();
  document.body.innerHTML = '';
  fetchMlEstado.mockResolvedValue(ESTADO);
});

describe('MlStatusPanel — training is pushed, not polled', () => {
  it('shows the training bar as progress events arrive, without asking the backend again', async () => {
    await mountPanel();

    await act(async () => { stream.frame('ml.status', training({ running: true, phase: 'text', pct: 42, msg: 'epoch 3', startedAt: new Date().toISOString() })); });

    expect(await screen.findByText('Entrenando...')).toBeInTheDocument();
    expect(await screen.findByText(/42%/)).toBeInTheDocument();
    expect(screen.getByText('epoch 3')).toBeInTheDocument();
    await act(async () => { await new Promise(r => setTimeout(r, 2300)); });
    expect(fetchMlEstado).toHaveBeenCalledTimes(1);
    expect(fetchMlResultado).not.toHaveBeenCalled();
  });

  it('toasts the accuracy and leaves the running state when training ends', async () => {
    await mountPanel();
    await act(async () => { stream.frame('ml.status', training({ running: true, phase: 'text', pct: 10 })); });
    await screen.findByText('Entrenando...');

    await act(async () => { stream.frame('ml.status', training({ phase: 'done', pct: 100, msg: 'Modelo listo 91.5%' })); });

    await waitFor(() => expect(screen.queryByText('Entrenando...')).toBeNull());
    expect(toasts()).toEqual(['Modelo ML actualizado — 91.5% accuracy']);
  });

  it('toasts the failure when the run ends in the error phase', async () => {
    await mountPanel();
    await act(async () => { stream.frame('ml.status', training({ running: true, phase: 'text', pct: 10 })); });
    await screen.findByText('Entrenando...');

    await act(async () => { stream.frame('ml.status', training({ phase: 'error', msg: 'sin GPU' })); });

    await waitFor(() => expect(toasts()).toEqual(['Entrenamiento ML falló: sin GPU']));
    expect(document.querySelector('.toast-error')).not.toBeNull();
  });

  it('shows no toast for a training state that was never running here', async () => {
    await mountPanel();

    await act(async () => { stream.frame('ml.status', training({ phase: 'done', pct: 100, msg: 'viejo 80%' })); });
    await act(async () => { await Promise.resolve(); });

    expect(toasts()).toEqual([]);
  });

  it('reads the new model metadata after training, from the one estado the provider refetches', async () => {
    await mountPanel();
    await act(async () => { stream.frame('ml.status', training({ running: true, phase: 'text', pct: 10 })); });
    fetchMlEstado.mockResolvedValue({ ...ESTADO, hasImageModel: true, textMeta: { accuracy: 0.915, num_classes: 12, num_train: 4000 } });

    await act(async () => { stream.frame('ml.status', training({ phase: 'done', pct: 100, msg: 'ok' })); });

    expect(await screen.findByText('91.5%')).toBeInTheDocument();
  });
});
