import { act, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import CronjobsPage from './CronjobsPage';
import CronJobCard from './cron/CronJobCard';
import { EventStreamProvider } from '../hooks/EventStreamProvider';
import { fetchCronExecutions, listCronJobs } from '../api';
import { fakeStream } from '../test/fakeEventStream';

vi.mock('../api', async importOriginal => ({
  ...(await importOriginal()),
  fetchStatus: vi.fn(() => Promise.resolve(null)),
  fetchMlEstado: vi.fn(() => Promise.resolve(null)),
  openEventStream: vi.fn(),
  listCronJobs: vi.fn(),
  fetchCronExecutions: vi.fn(),
  fetchSitios: vi.fn(() => Promise.resolve({ base: [], extras: [] })),
}));
vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ authenticated: true, identity: { username: 'admin' } }),
}));

const JOB = { id: 3, name: 'Nocturno', cronExpr: '0 0 3 * * *', enabled: true, sitios: [], lastRunAt: null, nextRunAt: null };
const snapshot = { status: {}, ml: { training: { running: false, phase: 'idle' } } };

let stream;

async function mount(ui) {
  stream = fakeStream();
  const open = vi.fn().mockResolvedValue(stream.response);
  render(<EventStreamProvider open={open}>{ui}</EventStreamProvider>);
  await act(async () => { stream.frame('snapshot', snapshot); });
}

beforeEach(() => {
  vi.clearAllMocks();
  listCronJobs.mockResolvedValue([JOB]);
  fetchCronExecutions.mockResolvedValue([]);
});

describe('CronjobsPage — the list follows cron executions pushed by the server', () => {
  it('re-reads the jobs, without the loading state, when an execution changes', async () => {
    await mount(<CronjobsPage />);
    await screen.findByText('Nocturno');
    expect(listCronJobs).toHaveBeenCalledTimes(1);
    listCronJobs.mockResolvedValue([{ ...JOB, name: 'Nocturno renombrado' }]);

    await act(async () => { stream.frame('db.changed', { table: 'cron_execution', op: 'UPDATE', job: 3, status: 'SUCCESS' }); });

    expect(await screen.findByText('Nocturno renombrado')).toBeInTheDocument();
    expect(listCronJobs).toHaveBeenCalledTimes(2);
  });

  it('ignores changes of other tables', async () => {
    await mount(<CronjobsPage />);
    await screen.findByText('Nocturno');

    await act(async () => { stream.frame('db.changed', { table: 'scrape_run', op: 'UPDATE', id: 9, status: 'DONE' }); });
    await act(async () => { await Promise.resolve(); });

    expect(listCronJobs).toHaveBeenCalledTimes(1);
  });

  it('re-reads on resync, since events may have been lost', async () => {
    await mount(<CronjobsPage />);
    await screen.findByText('Nocturno');

    await act(async () => { stream.frame('resync', {}); });

    await waitFor(() => expect(listCronJobs).toHaveBeenCalledTimes(2));
  });
});

describe('CronJobCard — the execution history follows the stream', () => {
  it('re-reads the executions of ITS job when one of them changes', async () => {
    await mount(<CronJobCard job={JOB} onClose={() => {}} onSaved={() => {}} />);
    await waitFor(() => expect(fetchCronExecutions).toHaveBeenCalledTimes(1));

    await act(async () => { stream.frame('db.changed', { table: 'cron_execution', op: 'INSERT', id: 88, job: 3, status: 'RUNNING' }); });

    await waitFor(() => expect(fetchCronExecutions).toHaveBeenCalledTimes(2));
    expect(fetchCronExecutions).toHaveBeenLastCalledWith(3);
  });

  it('does not re-read when the execution belongs to another job', async () => {
    await mount(<CronJobCard job={JOB} onClose={() => {}} onSaved={() => {}} />);
    await waitFor(() => expect(fetchCronExecutions).toHaveBeenCalledTimes(1));

    await act(async () => { stream.frame('db.changed', { table: 'cron_execution', op: 'INSERT', id: 89, job: 4, status: 'RUNNING' }); });
    await act(async () => { await Promise.resolve(); });

    expect(fetchCronExecutions).toHaveBeenCalledTimes(1);
  });

  it('a new job has no history to re-read', async () => {
    await mount(<CronJobCard job={{}} onClose={() => {}} onSaved={() => {}} />);
    await act(async () => { stream.frame('resync', {}); });
    await act(async () => { await Promise.resolve(); });

    expect(fetchCronExecutions).not.toHaveBeenCalled();
  });
});
