import { act, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import Topbar from './Topbar';
import { EventStreamProvider } from '../hooks/EventStreamProvider';
import { fetchMlResultado } from '../api';
import { fakeStream } from '../test/fakeEventStream';

vi.mock('../api', async importOriginal => ({
  ...(await importOriginal()),
  fetchStatus: vi.fn(() => Promise.resolve(null)),
  fetchMlEstado: vi.fn(() => Promise.resolve(null)),
  fetchMlResultado: vi.fn(),
  fetchIndices: vi.fn(() => Promise.resolve(null)),
  openEventStream: vi.fn(),
}));
vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ authenticated: true, identity: { username: 'admin', roles: ['ADMIN'] }, logout: vi.fn() }),
}));

const IDLE = { running: false, phase: 'idle', pct: 0, msg: '', startedAt: '' };
const props = {
  meta: {}, facets: {}, sitioFiltro: '', rubroFiltro: '',
  onSitioChange: vi.fn(), onRubroChange: vi.fn(), onReScrape: vi.fn(),
  gymrat: false, onGymratToggle: vi.fn(),
};

let stream;

async function mountTopbar() {
  stream = fakeStream();
  const open = vi.fn().mockResolvedValue(stream.response);
  render(<EventStreamProvider open={open}><Topbar {...props} /></EventStreamProvider>);
  await act(async () => {
    stream.frame('snapshot', { status: {}, ml: { training: IDLE } });
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  fetchMlResultado.mockResolvedValue(null);
});

describe('Topbar — the ML indicator follows the stream instead of polling /api/ml/resultado', () => {
  it('shows "Entrenando ML..." while a training event says running, and "Modelo actualizado" once it ends', async () => {
    await mountTopbar();
    expect(screen.queryByText(/Entrenando ML/)).toBeNull();

    await act(async () => { stream.frame('ml.status', { kind: 'training', running: true, phase: 'text', pct: 5, msg: '', startedAt: 't' }); });
    expect(await screen.findByText(/Entrenando ML/)).toBeInTheDocument();

    await act(async () => { stream.frame('ml.status', { kind: 'training', running: false, phase: 'done', pct: 100, msg: 'ok', startedAt: 't' }); });
    expect(await screen.findByText(/Modelo actualizado/)).toBeInTheDocument();
    expect(screen.queryByText(/Entrenando ML/)).toBeNull();
  });

  it('reads /api/ml/resultado once and never again, however long the training runs', async () => {
    await mountTopbar();
    await act(async () => { stream.frame('ml.status', { kind: 'training', running: true, phase: 'text', pct: 5, msg: '', startedAt: 't' }); });

    await act(async () => { await new Promise(r => setTimeout(r, 4300)); });

    expect(fetchMlResultado).toHaveBeenCalledTimes(1);
  });

  it('still shows a run that the first read found, when the stream has not spoken yet', async () => {
    fetchMlResultado.mockResolvedValue({ running: true, phase: 'text', pct: 3, msg: '', done: false });

    render(<Topbar {...props} />);

    expect(await screen.findByText(/Entrenando ML/)).toBeInTheDocument();
  });
});
