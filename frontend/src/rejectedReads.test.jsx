import { act, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import AgentChatPanel from './components/AgentChatPanel';
import CronJobCard from './components/cron/CronJobCard';
import CronjobsPage from './components/CronjobsPage';
import MlStatusPanel from './components/MlStatusPanel';
import SplashPanel from './components/SplashPanel';
import { EventStreamProvider } from './hooks/EventStreamProvider';
import { fakeStream } from './test/fakeEventStream';
import { trackUnhandledRejections } from './test/unhandledRejections';
import { fetchAgentModels, fetchCronExecutions, fetchMlEstado, fetchSitios, listCronJobs } from './api';

// `authedFetch` calls bare fetch, which REJECTS when nothing is listening; every read below
// is fired and forgotten from an effect, so a rejection has nobody to land on.
vi.mock('./api', async importOriginal => ({
  ...(await importOriginal()),
  fetchAgentModels: vi.fn(),
  fetchCronExecutions: vi.fn(),
  fetchMlEstado: vi.fn(),
  fetchMlResultado: vi.fn(() => Promise.resolve(null)),
  fetchStatus: vi.fn(() => Promise.resolve(null)),
  openEventStream: vi.fn(),
  fetchSitios: vi.fn(),
  listCronJobs: vi.fn(),
}));
vi.mock('./auth/AuthProvider', () => ({
  useAuth: () => ({ authenticated: true, identity: { username: 'admin' } }),
}));
vi.mock('./components/MlStatusPanel', async importOriginal => importOriginal());

const OFFLINE = new TypeError('Failed to fetch');
const JOB = { id: 3, name: 'Nocturno', cronExpr: '0 0 3 * * *', enabled: true, sitios: [], lastRunAt: null, nextRunAt: null };

let rejections;

beforeEach(() => {
  vi.clearAllMocks();
  rejections = trackUnhandledRejections();
});

afterEach(() => rejections.stop());

async function expectNoUnhandledRejection() {
  await act(async () => { await rejections.settle(); });
  expect(rejections.seen).toEqual([]);
}

describe('fire-and-forget reads — a dead backend leaves no unhandled rejection', () => {
  it('CronjobsPage keeps the list it has when the quiet refresh rejects', async () => {
    listCronJobs.mockResolvedValueOnce([JOB]);
    const stream = fakeStream();
    const open = vi.fn().mockResolvedValue(stream.response);
    render(<EventStreamProvider open={open}><CronjobsPage /></EventStreamProvider>);
    await act(async () => { stream.frame('snapshot', { status: {}, ml: { training: { running: false, phase: 'idle' } } }); });
    await screen.findByText('Nocturno');

    listCronJobs.mockRejectedValue(OFFLINE);
    await act(async () => { stream.frame('resync', {}); });
    await expectNoUnhandledRejection();

    expect(screen.getByText('Nocturno')).toBeInTheDocument();
  });

  it('CronJobCard survives a rejected execution history read', async () => {
    fetchSitios.mockResolvedValue({ base: [], extras: [] });
    fetchCronExecutions.mockRejectedValue(OFFLINE);
    render(<CronJobCard job={JOB} onClose={() => {}} onSaved={() => {}} />);

    await expectNoUnhandledRejection();
    expect(fetchCronExecutions).toHaveBeenCalled();
  });

  it('CronJobCard survives a rejected site list read', async () => {
    fetchSitios.mockRejectedValue(OFFLINE);
    fetchCronExecutions.mockResolvedValue([]);
    render(<CronJobCard job={JOB} onClose={() => {}} onSaved={() => {}} />);

    await expectNoUnhandledRejection();
  });

  it('SplashPanel survives a rejected site list read', async () => {
    fetchSitios.mockRejectedValue(OFFLINE);
    render(<SplashPanel config={{ precioMin: 0, precioMax: 1 }} progreso={{ total: 0, completados: 0, sitios: [] }}
      onScrapeStart={() => {}} onWatchRun={() => {}} onGoToApp={() => {}} prods={[]} totalProds={0} />);

    await expectNoUnhandledRejection();
  });

  it('MlStatusPanel survives a rejected estado read', async () => {
    fetchMlEstado.mockRejectedValue(OFFLINE);
    render(<MlStatusPanel />);

    await expectNoUnhandledRejection();
  });

  it('AgentChatPanel survives a rejected model list read', async () => {
    fetchAgentModels.mockRejectedValue(OFFLINE);
    render(<AgentChatPanel />);

    await expectNoUnhandledRejection();
    await waitFor(() => expect(fetchAgentModels).toHaveBeenCalled());
  });
});
